window.hoshiHighlights = {
  highlights: new Map(),
  pendingRange: null,
  prepareHighlightSelection: function() {
    var selection = window.getSelection();
    if (!selection || selection.rangeCount === 0) return false;
    var range = selection.getRangeAt(0);
    if (range.collapsed) return false;
    this.pendingRange = range.cloneRange();
    return true;
  },
  createHighlight: function(color, id) {
    var selection = window.getSelection();
    var range = selection && selection.rangeCount > 0 ? selection.getRangeAt(0) : null;
    if ((!range || range.collapsed) && this.pendingRange) {
      range = this.pendingRange;
    }
    if (!range || range.collapsed) {
      this.pendingRange = null;
      return null;
    }
    var segments = window.hoshiReaderDomText.selectedTextSegments(range);
    if (!segments.length) {
      this.pendingRange = null;
      return null;
    }
    var groups = [];
    for (var segment of segments) {
      var root = window.hoshiReader.chapterForNode
        ? window.hoshiReader.chapterForNode(segment.node) : document.body;
      if (!root) continue;
      if (!groups.length || groups[groups.length - 1].root !== root) groups.push({ root, segments: [] });
      groups[groups.length - 1].segments.push(segment);
    }
    // The sidecar identifies a chapter by a global counted character. Media-only
    // sections can contribute formatting whitespace between real selected text.
    var edits = groups.filter(group => group.root === document.body || window.hoshiReader.ensureChapterIndex(group.root).count > 0)
      .map(group => this.selectionEdit(group.root, group.segments));
    if (!edits.length || edits.some(edit => !edit)) {
      this.pendingRange = null;
      return null;
    }
    if (selection) selection.removeAllRanges();
    this.pendingRange = null;
    var changes = edits.map((edit, index) => {
      if (edit.existing) return this.updateHighlight(edit.existing, color);
      var partId = index === 0 ? id : crypto.randomUUID();
      this.wrapHighlight({ id: partId, color, offset: edit.offset, text: edit.text }, edit.root);
      window.hoshiReader.buildNodeOffsets(edit.root);
      return { action: 'created', id: partId, chapterIndex: edit.chapterIndex, start: edit.start,
        offset: edit.offset, text: edit.text, textFurigana: edit.textFurigana };
    });
    requestAnimationFrame(function() {
      document.body.style.transform = 'translateZ(0)';
      requestAnimationFrame(function() { document.body.style.transform = ''; });
    });
    return changes.length === 1 ? changes[0] : { action: 'batch', changes };
  },
  selectionEdit: function(root, segments) {
    var reader = window.hoshiReader;
    var first = segments[0];
    var last = segments[segments.length - 1];
    var startBase = reader.nodeStartOffsets.get(first.node);
    var rawStartBase = reader.nodeStartRawOffsets.get(first.node);
    var rawEndBase = reader.nodeStartRawOffsets.get(last.node);
    if (startBase === undefined || rawStartBase === undefined || rawEndBase === undefined) return null;
    var prefix = first.node.textContent.substring(0, first.start);
    var start = startBase + reader.countChars(prefix);
    var offset = rawStartBase + reader.countRawChars(prefix);
    var end = rawEndBase + reader.countRawChars(last.node.textContent.substring(0, last.end));
    if (end <= offset) return null;
    var existing = this.findHighlight(offset, end - offset, root);
    if (existing) return { existing };
    return { root, chapterIndex: root === document.body ? null : Number(root.dataset.hoshiChapter),
      start, offset, ...this.textForSegments(segments) };
  },
  findHighlight: function(offset, length, root = document.body) {
    for (var [id, entry] of this.highlights) {
      if (entry.root === root && entry.offset === offset && entry.length === length) return id;
    }
    return null;
  },
  updateHighlight: function(id, color) {
    var entry = this.highlights.get(id);
    if (!entry) return null;
    if (entry.color === color) {
      this.removeHighlight(id);
      return { action: 'removed', id: id };
    }
    entry.color = color;
    entry.wrappers.forEach(function(wrapper) {
      wrapper.className = 'hoshi-highlight hoshi-highlight-' + color;
    });
    return { action: 'recolored', id: id };
  },
  readingForNode: function(node) {
    var base = node;
    var ruby = node.parentElement && node.parentElement.closest('ruby');
    if (!ruby) return null;
    while (base && base.parentNode !== ruby) base = base.parentNode;
    for (var next = base && base.nextSibling; next; next = next.nextSibling) {
      if (next.nodeType === Node.ELEMENT_NODE && next.matches('rt')) return next;
    }
    return null;
  },
  textForSegments: function(segments) {
    var text = '';
    var annotated = '';
    var reading = null;
    var flushReading = function() {
      if (reading && reading.textContent) annotated += '(' + reading.textContent + ')';
    };
    for (var i = 0; i < segments.length; i++) {
      var segment = segments[i];
      var nextReading = this.readingForNode(segment.node);
      if (nextReading !== reading) {
        flushReading();
        reading = nextReading;
      }
      var part = segment.node.textContent.slice(segment.start, segment.end);
      text += part;
      annotated += part;
    }
    flushReading();
    return { text: text, textFurigana: annotated !== text ? annotated : null };
  },
  searchRange: null,
  searchRawRange: function(offset, length, root) {
    var entries = [];
    var walker = window.hoshiReader.createWalker(root);
    var node;
    while ((node = walker.nextNode())) entries.push({ text: node.textContent });
    return window.hoshiReaderTextSemantics.searchRawRange(entries, offset, length);
  },
  showSearchHighlight: function(offset, length, chapterIndex) {
    this.clearSearchHighlight();
    this.searchRoot = window.hoshiReader.chapterRoot ? window.hoshiReader.chapterRoot(chapterIndex) : document.body;
    if (!this.searchRoot) return;
    this.searchRange = this.searchRawRange(offset, length, this.searchRoot);
    this.refreshSearchHighlight();
  },
  refreshSearchHighlight: function() {
    if (typeof CSS === 'undefined' || !CSS.highlights || typeof Highlight === 'undefined') return;
    CSS.highlights.delete('hoshi-search');
    if (!this.searchRange) return;
    var highlight = new Highlight();
    var segments = this.collectSegments(this.searchRange.start, this.searchRange.end - this.searchRange.start, this.searchRoot);
    for (var segment of segments) {
      var range = document.createRange();
      range.setStart(segment.node, segment.start);
      range.setEnd(segment.node, segment.end);
      highlight.add(range);
    }
    CSS.highlights.set('hoshi-search', highlight);
  },
  clearSearchHighlight: function() {
    this.searchRange = null;
    if (typeof CSS !== 'undefined' && CSS.highlights) CSS.highlights.delete('hoshi-search');
  },
  collectSegments: function(offset, length, root) {
    var end = offset + length;
    var segments = [];
    var cursor = 0;
    var segment = null;
    var flushSegment = function() {
      if (!segment) return;
      segments.push(segment);
      segment = null;
    };
    var walker = window.hoshiReader.createWalker(root);
    var node;
    while (cursor < end && (node = walker.nextNode())) {
      var text = node.textContent || '';
      var i = 0;
      while (i < text.length && cursor < end) {
        var char = String.fromCodePoint(text.codePointAt(i));
        var next = i + char.length;
        if (cursor >= offset) {
          if (!segment || segment.node !== node) {
            flushSegment();
            segment = { node: node, start: i, end: next };
          } else {
            segment.end = next;
          }
        }
        cursor += 1;
        i = next;
      }
      flushSegment();
    }
    return segments;
  },
  wrapHighlight: function(highlight, root = document.body) {
    var length = window.hoshiReader.countRawChars(highlight.text || '');
    var segments = this.collectSegments(highlight.offset, length, root);
    var range = document.createRange();
    var wrappers = [];
    for (var i = segments.length - 1; i >= 0; i--) {
      var segment = segments[i];
      range.setStart(segment.node, segment.start);
      range.setEnd(segment.node, segment.end);
      var wrapper = document.createElement('span');
      wrapper.className = 'hoshi-highlight hoshi-highlight-' + highlight.color;
      wrapper.appendChild(range.extractContents());
      range.insertNode(wrapper);
      wrappers.push(wrapper);
    }
    wrappers.reverse();
    this.highlights.set(highlight.id, { color: highlight.color, offset: highlight.offset, text: highlight.text, length, wrappers, root });
  },
  applyHighlights: function(highlights, rebuildOffsets = true) {
    var groups = Array.isArray(highlights) ? [[document.body, highlights]] :
      Object.entries(highlights).map(([index, records]) => [window.hoshiReader.chapterRoot(Number(index)), records]);
    for (var [root, records] of groups) {
      if (!root) continue;
      for (var record of records) this.wrapHighlight(record, root);
      if (records.length && rebuildOffsets) window.hoshiReader.buildNodeOffsets(root);
    }
  },
  reconcileBookHighlights: function(chapters) {
    var incoming = new Set();
    var changedRoots = new Set();
    for (var [index, records] of Object.entries(chapters)) {
      var root = window.hoshiReader.chapterRoot(Number(index));
      if (!root) continue;
      for (var record of records) {
        incoming.add(record.id);
        var old = this.highlights.get(record.id);
        if (old && old.root === root && old.offset === record.offset && old.text === record.text) {
          if (old.color !== record.color) this.updateHighlight(record.id, record.color);
          continue;
        }
        if (old) {
          window.hoshiReader.unwrap(old.wrappers);
          changedRoots.add(old.root);
        }
        this.wrapHighlight(record, root);
        changedRoots.add(root);
      }
    }
    for (var [id, entry] of this.highlights) {
      if (incoming.has(id)) continue;
      window.hoshiReader.unwrap(entry.wrappers);
      this.highlights.delete(id);
      changedRoots.add(entry.root);
    }
    for (var root of changedRoots) window.hoshiReader.buildNodeOffsets(root);
  },
  removeHighlight: function(id) {
    var entry = this.highlights.get(id);
    if (!entry) return;
    window.hoshiReader.unwrap(entry.wrappers);
    this.highlights.delete(id);
    window.hoshiReader.buildNodeOffsets(entry.root);
    requestAnimationFrame(function() {
      document.body.style.transform = 'translateZ(0)';
      requestAnimationFrame(function() { document.body.style.transform = ''; });
    });
  }
};
