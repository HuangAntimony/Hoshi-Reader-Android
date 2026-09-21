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
    var startBase = window.hoshiReader.nodeStartOffsets.get(range.startContainer);
    var rawStartBase = window.hoshiReader.nodeStartRawOffsets.get(range.startContainer);
    var rawEndBase = window.hoshiReader.nodeStartRawOffsets.get(range.endContainer);
    if (startBase === undefined || rawStartBase === undefined || rawEndBase === undefined) {
      this.pendingRange = null;
      return null;
    }
    var startPrefix = range.startContainer.textContent.substring(0, range.startOffset);
    var endPrefix = range.endContainer.textContent.substring(0, range.endOffset);
    var start = startBase + window.hoshiReader.countChars(startPrefix);
    var rawStart = rawStartBase + window.hoshiReader.countRawChars(startPrefix);
    var rawEnd = rawEndBase + window.hoshiReader.countRawChars(endPrefix);
    if (rawEnd <= rawStart) {
      this.pendingRange = null;
      return null;
    }
    var existing = this.findHighlight(rawStart, rawEnd - rawStart);
    if (existing) {
      if (selection) selection.removeAllRanges();
      this.pendingRange = null;
      return this.updateHighlight(existing, color);
    }
    var texts = this.textForRange(rawStart, rawEnd - rawStart);
    if (!texts.text) {
      this.pendingRange = null;
      return null;
    }
    if (selection) selection.removeAllRanges();
    this.pendingRange = null;
    this.wrapHighlight({ id: id, color: color, offset: rawStart, text: texts.text });
    window.hoshiReader.buildNodeOffsets();
    requestAnimationFrame(function() {
      document.body.style.transform = 'translateZ(0)';
      requestAnimationFrame(function() { document.body.style.transform = ''; });
    });
    return { action: 'created', start: start, offset: rawStart, text: texts.text, textFurigana: texts.textFurigana };
  },
  findHighlight: function(offset, length) {
    for (var pair of this.highlights) {
      if (pair[1].offset === offset && pair[1].length === length) return pair[0];
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
  // VN substitutes source segments here; rendering still uses collectSegments.
  collectTextSegments: function(offset, length) {
    return this.collectSegments(offset, length);
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
  textForRange: function(offset, length) {
    var segments = this.collectTextSegments(offset, length);
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
  searchRawRange: function(offset, length) {
    var entries = [];
    var walker = window.hoshiReader.createWalker();
    var node;
    while ((node = walker.nextNode())) entries.push({ text: node.textContent });
    return window.hoshiReaderTextSemantics.searchRawRange(entries, offset, length);
  },
  showSearchHighlight: function(offset, length) {
    this.clearSearchHighlight();
    this.searchRange = this.searchRawRange(offset, length);
    this.refreshSearchHighlight();
  },
  refreshSearchHighlight: function() {
    if (typeof CSS === 'undefined' || !CSS.highlights || typeof Highlight === 'undefined') return;
    CSS.highlights.delete('hoshi-search');
    if (!this.searchRange) return;
    var highlight = new Highlight();
    var segments = this.collectSegments(this.searchRange.start, this.searchRange.end - this.searchRange.start);
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
  collectSegments: function(offset, length) {
    var end = offset + length;
    var segments = [];
    var cursor = 0;
    var segment = null;
    var flushSegment = function() {
      if (!segment) return;
      segments.push(segment);
      segment = null;
    };
    var walker = window.hoshiReader.createWalker();
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
  wrapHighlight: function(highlight) {
    var length = window.hoshiReader.countRawChars(highlight.text || '');
    var segments = this.collectSegments(highlight.offset, length);
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
    this.highlights.set(highlight.id, { color: highlight.color, offset: highlight.offset, length: length, wrappers: wrappers });
  },
  applyHighlights: function(highlights) {
    for (var i = 0; i < highlights.length; i++) {
      this.wrapHighlight(highlights[i]);
      // VN projects each subsequent range through offsets of the newly split nodes.
      window.hoshiReader.buildNodeOffsets();
    }
    this.refreshSearchHighlight();
  },
  removeHighlight: function(id) {
    var entry = this.highlights.get(id);
    if (!entry) return;
    window.hoshiReader.unwrap(entry.wrappers);
    this.highlights.delete(id);
    window.hoshiReader.buildNodeOffsets();
    requestAnimationFrame(function() {
      document.body.style.transform = 'translateZ(0)';
      requestAnimationFrame(function() { document.body.style.transform = ''; });
    });
  }
};
