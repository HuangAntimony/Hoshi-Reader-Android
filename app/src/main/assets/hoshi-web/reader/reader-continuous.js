 window.hoshiReader = {
   cueWrappers: new Map(),
   cueSourceRanges: new Map(),
   cueRanges: new Map(),
   cueGeometryRanges: new Map(),
   cueRubyElements: new Map(),
  activeCueId: null,
  ttuRegexNegated: /[^0-9A-Za-z○◯々-〇〻ぁ-ゖゝ-ゞァ-ヺー０-９Ａ-Ｚａ-ｚｦ-ﾝ\p{Radical}\p{Unified_Ideograph}]+/gimu,
  ttuRegex: /[0-9A-Za-z○◯々-〇〻ぁ-ゖゝ-ゞァ-ヺー０-９Ａ-Ｚａ-ｚｦ-ﾝ\p{Radical}\p{Unified_Ideograph}]/iu,
  nodeStartOffsets: new WeakMap(),
  nodeStartRawOffsets: new WeakMap(),
  isVertical: function() {
    return window.getComputedStyle(document.body).writingMode === "vertical-rl";
  },
  readerCssVariable: function(name) {
    return window.getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  },
  isEInkMode: function() {
    return this.readerCssVariable('--hoshi-reader-eink-mode') === '1';
  },
  isFurigana: function(node) {
    var el = node.nodeType === Node.TEXT_NODE ? node.parentElement : node;
    return !!(el && el.closest('rt, rp'));
  },
  normalizeText: function(text) {
    return (text || '').replace(this.ttuRegexNegated, '');
  },
  countChars: function(text) {
    return Array.from(this.normalizeText(text)).length;
  },
  countRawChars: function(text) {
    return Array.from(text || '').length;
  },
  isMatchableChar: function(char) {
    return this.ttuRegex.test(char || '');
  },
  textOffsetForCharCount: function(node, targetCount) {
    var text = node.textContent || '';
    var count = 0;
    var offset = 0;
    var fallbackOffset = 0;
    while (offset < text.length) {
      var char = String.fromCodePoint(text.codePointAt(offset));
      if (this.isMatchableChar(char)) {
        if (count >= targetCount) return offset;
        fallbackOffset = offset;
        count += 1;
      }
      offset += char.length;
    }
    return fallbackOffset;
  },
   notifyRestoreComplete: function() {
     if (window.HoshiReaderRestore && window.HoshiReaderRestore.postMessage) {
       window.HoshiReaderRestore.postMessage(__HOSHI_RESTORE_TOKEN_LITERAL__);
     }
   },
  scrollToChapterStart: function() {
    var root = document.scrollingElement || document.documentElement;
    window.scrollTo(0, 0);
    root.scrollTop = 0;
    root.scrollLeft = 0;
    document.documentElement.scrollTop = 0;
    document.documentElement.scrollLeft = 0;
    document.body.scrollTop = 0;
    document.body.scrollLeft = 0;
  },
  createWalker: function(rootNode) {
    var root = rootNode || document.body;
    return document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode: (n) => this.isFurigana(n) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT
    });
  },
  getRect: function(target) {
    var rect = target.getClientRects()[0];
    return rect || target.getBoundingClientRect();
  },
  buildNodeOffsets: function() {
    var offsets = new WeakMap();
    var rawOffsets = new WeakMap();
    var walker = this.createWalker();
    var count = 0;
    var rawCount = 0;
    var node;
    while (node = walker.nextNode()) {
      offsets.set(node, count);
      rawOffsets.set(node, rawCount);
      count += this.countChars(node.textContent);
      rawCount += this.countRawChars(node.textContent);
    }
    this.nodeStartOffsets = offsets;
    this.nodeStartRawOffsets = rawOffsets;
  },
  countCharsBeforeViewport: function(node, vertical) {
    var text = node.textContent || '';
    var totalChars = this.countChars(text);
    if (totalChars <= 0) return 0;
    var range = document.createRange();
    range.selectNodeContents(node);
    var rects = range.getClientRects();
    if (!rects.length) return 0;
    var minStart = Infinity;
    var maxEnd = -Infinity;
    for (var i = 0; i < rects.length; i++) {
      var rect = rects[i];
      if (rect.width <= 0 || rect.height <= 0) continue;
      var start = vertical ? rect.left : rect.top;
      var end = vertical ? rect.right : rect.bottom;
      minStart = Math.min(minStart, start);
      maxEnd = Math.max(maxEnd, end);
    }
    if (vertical) {
      if (minStart >= window.innerWidth) return totalChars;
      if (maxEnd <= window.innerWidth || minStart === Infinity) return 0;
    } else {
      if (maxEnd <= 0) return totalChars;
      if (minStart >= 0 || minStart === Infinity) return 0;
    }
    var offsets = [];
    var prefixCounts = [0];
    var count = 0;
    var offset = 0;
    while (offset < text.length) {
      offsets.push(offset);
      var char = String.fromCodePoint(text.codePointAt(offset));
      offset += char.length;
      if (this.isMatchableChar(char)) count += 1;
      prefixCounts.push(count);
    }
    var low = 0;
    var high = offsets.length - 1;
    var firstVisible = offsets.length;
    while (low <= high) {
      var mid = Math.floor((low + high) / 2);
      if (this.isTextOffsetBeforeViewport(node, offsets[mid], text, vertical)) {
        low = mid + 1;
      } else {
        firstVisible = mid;
        high = mid - 1;
      }
    }
    return prefixCounts[firstVisible];
  },
  isTextOffsetBeforeViewport: function(node, offset, text, vertical) {
    var char = String.fromCodePoint(text.codePointAt(offset));
    if (!char) return false;
    var range = document.createRange();
    range.setStart(node, offset);
    range.setEnd(node, offset + char.length);
    var rect = this.getRect(range);
    if (!rect || rect.width <= 0 || rect.height <= 0) return false;
    return vertical ? rect.left >= window.innerWidth : rect.bottom <= 0;
  },
  scrollToTarget: function(target) {
    var rect = this.getRect(target);
    if (this.isVertical()) {
      if (rect.left >= 0 && rect.right <= window.innerWidth) return false;
    } else if (rect.top >= 0 && rect.bottom <= window.innerHeight) {
      return false;
    }
    if (typeof target.scrollIntoView === 'function') {
      target.scrollIntoView({ block: 'start', inline: 'nearest' });
    } else if (this.isVertical()) {
      var root = document.scrollingElement || document.documentElement;
      var currentLeft = window.scrollX;
      if (currentLeft === 0 && root.scrollLeft !== 0) currentLeft = root.scrollLeft;
      var targetLeft = currentLeft + rect.right - window.innerWidth;
      window.scrollTo({ left: targetLeft, top: window.scrollY, behavior: 'instant' });
      root.scrollLeft = targetLeft;
    } else {
      var root = document.scrollingElement || document.documentElement;
      var currentTop = root.scrollTop;
      if (currentTop === 0 && window.scrollY !== 0) currentTop = window.scrollY;
      var targetTop = currentTop + rect.top;
      window.scrollTo({ left: window.scrollX, top: targetTop, behavior: 'instant' });
      root.scrollTop = targetTop;
    }
    return true;
  },
  collectSasayakiCueRanges: function(cues) {
    var cueRanges = new Map();
    if (!cues.length) return [];
    var index = 0;
    var current = cues[0];
    var start = current.start;
    var end = start + current.length;
    var cursor = 0;
    var segment = null;
    var flushSegment = function(node) {
      if (!segment) return;
      var ranges = cueRanges.get(segment.id) || [];
      ranges.push({ node: node, start: segment.start, end: segment.end });
      cueRanges.set(segment.id, ranges);
      segment = null;
    };
    var advanceCue = function() {
      index += 1;
      current = cues[index];
      if (current) {
        start = current.start;
        end = start + current.length;
      }
    };
    var walker = this.createWalker();
    var node;
    while (current && (node = walker.nextNode())) {
      var text = node.textContent;
      var i = 0;
      while (i < text.length && current) {
        var char = String.fromCodePoint(text.codePointAt(i));
        var next = i + char.length;
        if (this.isMatchableChar(char)) {
          if (cursor >= start && cursor < end) {
            if (!segment) {
              segment = { id: current.id, start: i, end: next };
            } else {
              segment.end = next;
            }
          } else {
            flushSegment(node);
          }
          cursor += 1;
          if (cursor === end) {
            flushSegment(node);
            advanceCue();
          }
        } else if (segment) {
          segment.end = next;
        }
        i = next;
      }
      flushSegment(node);
    }
    return cues.map(function(cue) {
      return { id: cue.id, ranges: cueRanges.get(cue.id) || [] };
    });
  },
   applySasayakiCues: function(cues) {
     var activeCueId = this.activeCueId;
     this.resetSasayakiCues();
     var cueRanges = this.collectSasayakiCueRanges(cues);
     this.rememberSasayakiCueSources(cueRanges);
     this.cueGeometryRanges = this.buildSasayakiGeometryRanges(cueRanges);
     this.prepareSasayakiInlineTargets(cueRanges, true);
     this.buildNodeOffsets();
     if (activeCueId && this.hasSasayakiCueTarget(activeCueId)) {
       this.activeCueId = activeCueId;
       this.refreshSasayakiCuePresentation();
     }
   },
  wrapSasayakiCue: function(cue) {
    if (this.isEInkMode()) {
      this.ensureSasayakiCueGeometry(cue);
      return this.cueGeometryRanges.get(cue.id) || [];
    }
     var existing = this.sasayakiInlineTargetsForCue(cue.id);
     if (existing.length) return existing;
     var cueRanges = this.collectSasayakiCueRanges([cue]);
     this.rememberSasayakiCueSources(cueRanges);
     var geometryRanges = this.buildSasayakiGeometryRanges(cueRanges).get(cue.id) || [];
     if (geometryRanges.length) this.cueGeometryRanges.set(cue.id, geometryRanges);
     this.prepareSasayakiInlineTargets(cueRanges);
    this.buildNodeOffsets();
    return this.sasayakiInlineTargetsForCue(cue.id);
  },
  wrapSasayakiCueRanges: function(cueRanges) {
    var wrapped = new Map();
    var range = document.createRange();
    for (var i = cueRanges.length - 1; i >= 0; i--) {
      var id = cueRanges[i].id;
      var ranges = cueRanges[i].ranges;
      if (!ranges.length) continue;
      var wrappers = [];
      for (var j = ranges.length - 1; j >= 0; j--) {
        var segment = ranges[j];
        range.setStart(segment.node, segment.start);
        range.setEnd(segment.node, segment.end);
        var wrapper = document.createElement('span');
        wrapper.className = 'hoshi-sasayaki-cue';
        wrapper.appendChild(range.extractContents());
        range.insertNode(wrapper);
        wrappers.push(wrapper);
      }
      wrappers.reverse();
       this.cueWrappers.set(id, wrappers);
       wrapped.set(id, wrappers);
     }
     return wrapped;
   },
   rememberSasayakiCueSources: function(cueRanges) {
     for (var i = 0; i < cueRanges.length; i++) {
       this.cueSourceRanges.set(cueRanges[i].id, cueRanges[i]);
     }
   },
             supportsSasayakiRangeHighlights: function() {
            return !!(window.CSS && CSS.highlights && window.Highlight);
          },
           sasayakiInlineTargetsForCue: function(cueId) {
             var ranges = this.cueRanges.get(cueId) || [];
             if (ranges.length) return ranges;
             return this.cueWrappers.get(cueId) || [];
           },
           hasSasayakiCueTarget: function(cueId) {
             return (this.cueGeometryRanges.get(cueId) || []).length > 0 ||
               (this.cueRanges.get(cueId) || []).length > 0 ||
               (this.cueRubyElements.get(cueId) || []).length > 0 ||
               (this.cueWrappers.get(cueId) || []).length > 0;
           },
           ensureSasayakiInlineTargetsForCue: function(cueId) {
             if (this.isEInkMode()) return;
             var source = this.cueSourceRanges.get(cueId);
             if (!source) return;
             if (this.supportsSasayakiRangeHighlights() && this.cueRangeNeedsWrapper(source)) {
               this.cueRanges.delete(cueId);
               this.cueRubyElements.delete(cueId);
               if (!(this.cueWrappers.get(cueId) || []).length) {
                 this.wrapSasayakiCueRanges([source]);
               }
               return;
             }
             if (!this.sasayakiInlineTargetsForCue(cueId).length) {
               this.prepareSasayakiInlineTargets([source]);
             }
           },
           prepareSasayakiInlineTargets: function(cueRanges, replace) {
             if (this.supportsSasayakiRangeHighlights()) {
              var targets = this.buildSasayakiHighlightRanges(cueRanges);
              if (replace) {
                this.cueRanges = targets.ranges;
                this.cueRubyElements = targets.rubyElements;
              } else {
                targets.ranges.forEach(function(ranges, id) {
                  this.cueRanges.set(id, ranges);
                }, this);
                targets.rubyElements.forEach(function(rubyElements, id) {
                  this.cueRubyElements.set(id, rubyElements);
                }, this);
              }
              if (targets.wrapperCueRanges.length) {
                this.wrapSasayakiCueRanges(targets.wrapperCueRanges);
              }
              return;
            }
            if (!this.isEInkMode()) {
              this.wrapSasayakiCueRanges(cueRanges);
            }
          },
          textEmphasisElementForNode: function(node) {
            var el = node && node.nodeType === Node.TEXT_NODE ? node.parentElement : node;
            while (el && el !== document.body) {
              var style = window.getComputedStyle(el);
              var emphasisStyle = style.webkitTextEmphasisStyle || style.textEmphasisStyle || 'none';
              if (emphasisStyle && emphasisStyle !== 'none') return el;
              el = el.parentElement;
            }
            return null;
          },
          cueRangeNeedsWrapper: function(cueRange) {
            for (var i = 0; i < cueRange.ranges.length; i++) {
              if (this.textEmphasisElementForNode(cueRange.ranges[i].node)) return true;
            }
            return false;
          },
          buildSasayakiHighlightRanges: function(cueRanges) {
            var highlightedRanges = new Map();
            var highlightedRubyElements = new Map();
            var wrapperCueRanges = [];
            var rubyForNode = function(node) {
              var el = node.nodeType === Node.TEXT_NODE ? node.parentElement : node;
              return el && el.closest ? el.closest('ruby') : null;
            };
            for (var i = 0; i < cueRanges.length; i++) {
              var id = cueRanges[i].id;
              var ranges = cueRanges[i].ranges;
              if (!ranges.length) continue;
              if (!this.isEInkMode() && this.cueRangeNeedsWrapper(cueRanges[i])) {
                wrapperCueRanges.push(cueRanges[i]);
                continue;
              }
              var highlightRanges = [];
              var rubyElements = [];
              for (var j = 0; j < ranges.length; j++) {
                var segment = ranges[j];
                var ruby = rubyForNode(segment.node);
                if (ruby) {
                  if (rubyElements.indexOf(ruby) < 0) rubyElements.push(ruby);
                  continue;
                }
                var range = document.createRange();
                range.setStart(segment.node, segment.start);
                range.setEnd(segment.node, segment.end);
                highlightRanges.push(range);
              }
              if (highlightRanges.length) highlightedRanges.set(id, highlightRanges);
              if (rubyElements.length) highlightedRubyElements.set(id, rubyElements);
            }
            return { ranges: highlightedRanges, rubyElements: highlightedRubyElements, wrapperCueRanges: wrapperCueRanges };
          },
  buildSasayakiGeometryRanges: function(cueRanges) {
    var geometryRanges = new Map();
    for (var i = 0; i < cueRanges.length; i++) {
      var id = cueRanges[i].id;
      var ranges = cueRanges[i].ranges;
      if (!ranges.length) continue;
      var cueGeometryRanges = [];
      for (var j = 0; j < ranges.length; j++) {
        var segment = ranges[j];
        var range = document.createRange();
        range.setStart(segment.node, segment.start);
        range.setEnd(segment.node, segment.end);
        cueGeometryRanges.push(range);
      }
      if (cueGeometryRanges.length) geometryRanges.set(id, cueGeometryRanges);
    }
    return geometryRanges;
  },
  ensureSasayakiCueGeometry: function(cue) {
    if (!cue || typeof cue === 'string') return;
    var existing = this.cueGeometryRanges.get(cue.id);
    if (existing && existing.length) return;
     var cueRanges = this.collectSasayakiCueRanges([cue]);
     this.rememberSasayakiCueSources(cueRanges);
     var geometryRanges = this.buildSasayakiGeometryRanges(cueRanges).get(cue.id) || [];
     if (geometryRanges.length) this.cueGeometryRanges.set(cue.id, geometryRanges);
   },
  sasayakiOverlayRects: function(cueId) {
    var ranges = this.cueGeometryRanges.get(cueId) || [];
    var rects = [];
    ranges.forEach(function(range) {
      if (window.hoshiRubyGeometry) {
        window.hoshiRubyGeometry.rectsForRange(range).forEach(function(rect) { rects.push(rect); });
      } else {
        Array.from(range.getClientRects()).forEach(function(rect) {
          rects.push({ x: rect.x, y: rect.y, width: rect.width, height: rect.height });
        });
      }
    });
    return window.hoshiRubyGeometry ? window.hoshiRubyGeometry.mergeInlineRects(rects) : rects;
  },
  renderSasayakiOverlay: function() {
    if (!this.activeCueId || !this.isEInkMode()) {
      this.clearSasayakiOverlay();
      return;
    }
    window.hoshiReaderPopupHost?.renderSasayakiHighlight?.({
      rects: this.sasayakiOverlayRects(this.activeCueId),
      eInkMode: true,
      verticalWriting: this.isVertical()
    });
  },
  clearSasayakiOverlay: function() {
    window.hoshiReaderPopupHost?.clearSasayakiHighlight?.();
  },
  clearInlineSasayakiCue: function(cueId) {
    if (this.supportsSasayakiRangeHighlights()) {
      CSS.highlights.delete('hoshi-sasayaki');
      var rubyElements = this.cueRubyElements.get(cueId) || [];
      rubyElements.forEach(function(ruby) { ruby.classList.remove('hoshi-sasayaki-ruby-active'); });
    }
    var wrappers = this.cueWrappers.get(cueId) || [];
    wrappers.forEach(function(wrapper) { wrapper.classList.remove('hoshi-sasayaki-active'); });
  },
  applyInlineSasayakiCue: function(cueId) {
    if (this.supportsSasayakiRangeHighlights()) {
      var ranges = this.cueRanges.get(cueId) || [];
      if (ranges.length) {
        CSS.highlights.set('hoshi-sasayaki', new Highlight(...ranges));
      }
      var rubyElements = this.cueRubyElements.get(cueId) || [];
      rubyElements.forEach(function(ruby) { ruby.classList.add('hoshi-sasayaki-ruby-active'); });
      var wrappers = this.cueWrappers.get(cueId) || [];
      wrappers.forEach(function(wrapper) { wrapper.classList.add('hoshi-sasayaki-active'); });
      return ranges.length > 0 || rubyElements.length > 0 || wrappers.length > 0;
    }
    var wrappers = this.cueWrappers.get(cueId) || [];
    wrappers.forEach(function(wrapper) { wrapper.classList.add('hoshi-sasayaki-active'); });
    return wrappers.length > 0;
  },
  refreshSasayakiCuePresentation: function() {
    if (!this.activeCueId) {
      this.clearSasayakiOverlay();
      return;
    }
    this.clearInlineSasayakiCue(this.activeCueId);
    if (this.isEInkMode()) {
      this.renderSasayakiOverlay();
     } else {
       this.clearSasayakiOverlay();
       this.ensureSasayakiInlineTargetsForCue(this.activeCueId);
       this.applyInlineSasayakiCue(this.activeCueId);
     }
   },
  highlightSasayakiCue: function(cue, reveal) {
    this.clearSasayakiCue();
    var cueId = typeof cue === 'string' ? cue : cue.id;
    if (this.isEInkMode()) {
      this.ensureSasayakiCueGeometry(cue);
      var geometryRanges = this.cueGeometryRanges.get(cueId) || [];
      if (!geometryRanges.length) return null;
      this.activeCueId = cueId;
      var geometryTarget = geometryRanges[0];
      var didScroll = reveal && geometryTarget && this.scrollToTarget(geometryTarget);
      this.renderSasayakiOverlay();
      var self = this;
      requestAnimationFrame(function() { self.renderSasayakiOverlay(); });
      if (didScroll) return this.calculateProgress();
      return null;
    }
    var targets = this.sasayakiInlineTargetsForCue(cueId);
    var rubyElements = this.supportsSasayakiRangeHighlights() ? (this.cueRubyElements.get(cueId) || []) : [];
    if (!targets.length && !rubyElements.length && typeof cue !== 'string') {
      this.wrapSasayakiCue(cue);
      targets = this.sasayakiInlineTargetsForCue(cueId);
      rubyElements = this.supportsSasayakiRangeHighlights() ? (this.cueRubyElements.get(cueId) || []) : [];
    }
    if (!targets.length && !rubyElements.length) return null;
    this.activeCueId = cueId;
    this.applyInlineSasayakiCue(cueId);
    var scrollTarget = targets.length ? targets[0] : rubyElements[0];
    if (reveal && scrollTarget && this.scrollToTarget(scrollTarget)) {
      return this.calculateProgress();
    }
    return null;
  },
  clearSasayakiCue: function() {
    if (!this.activeCueId) {
      this.clearSasayakiOverlay();
      return;
    }
    this.clearInlineSasayakiCue(this.activeCueId);
    this.activeCueId = null;
    this.clearSasayakiOverlay();
  },
  resetSasayakiCues: function() {
    if (this.supportsSasayakiRangeHighlights()) {
      CSS.highlights.delete('hoshi-sasayaki');
    }
    this.cueRubyElements.forEach(function(rubyElements) {
      rubyElements.forEach(function(ruby) { ruby.classList.remove('hoshi-sasayaki-ruby-active'); });
    });
     this.cueRubyElements.clear();
     this.cueSourceRanges.clear();
     this.cueRanges.clear();
    this.cueGeometryRanges.clear();
    var self = this;
    this.cueWrappers.forEach(function(wrappers) { self.unwrap(wrappers); });
    this.cueWrappers.clear();
    this.activeCueId = null;
    this.clearSasayakiOverlay();
  },
  unwrap: function(wrappers) {
    var self = this;
    var parents = [];
    wrappers.forEach(function(wrapper) {
      var parent = wrapper.parentNode;
      if (!parent) return;
      while (wrapper.firstChild) {
        parent.insertBefore(wrapper.firstChild, wrapper);
      }
      parent.removeChild(wrapper);
      if (parents.indexOf(parent) < 0) parents.push(parent);
    });
    parents.forEach(function(parent) { self.normalizeReaderText(parent); });
  },
  normalizeReaderText: function(parent) {
    if (!parent) return;
    this.normalizeRubyTextNodes(parent);
    parent.normalize();
    this.stabilizeRubyAdjacentTextNodes(parent);
  },
  normalizeRubyTextNodes: function(root) {
    var rubyNodes = new Set();
    if (root && root.nodeType === Node.ELEMENT_NODE && String(root.tagName).toLowerCase() === 'ruby') {
      rubyNodes.add(root);
    }
    var scope = root && root.querySelectorAll ? root : document;
    Array.from(scope.querySelectorAll('ruby')).forEach(function(ruby) {
      rubyNodes.add(ruby);
    });
    rubyNodes.forEach(function(ruby) {
      Array.from(ruby.childNodes).forEach(function(node) {
        if (node.nodeType !== Node.TEXT_NODE) return;
        if (!node.nodeValue.trim()) {
          ruby.removeChild(node);
          return;
        }
        var wrapper = document.createElement('span');
        ruby.insertBefore(wrapper, node);
        wrapper.appendChild(node);
      });
    });
  },
  isJapaneseBreakCharacter: function(text) {
    var code = (text || '').codePointAt(0);
    return (code >= 0x3000 && code <= 0x303f) ||
      (code >= 0x3040 && code <= 0x30ff) ||
      (code >= 0x3400 && code <= 0x9fff) ||
      (code >= 0xf900 && code <= 0xfaff) ||
      (code >= 0xff00 && code <= 0xffef);
  },
  stabilizeRubyAdjacentTextNodes: function(root) {
    if (!this.isVertical()) return;
    var self = this;
    var splitLimit = 64;
    var scope = root && root.querySelectorAll ? root : document;
    var rubies = Array.from(scope.querySelectorAll('ruby'));
    if (root && root.tagName && root.tagName.toLowerCase() === 'ruby') {
      rubies.unshift(root);
    }
    rubies.forEach(function(ruby) {
      if (ruby.closest('rt, rp')) return;
      var node = ruby.nextSibling;
      while (node && node.nodeType === Node.TEXT_NODE && !node.nodeValue.trim()) {
        node = node.nextSibling;
      }
      if (!node || node.nodeType !== Node.TEXT_NODE || !node.nodeValue) return;
      var chars = Array.from(node.nodeValue);
      if (chars.length <= 1) return;
      var fragment = document.createDocumentFragment();
      var pending = '';
      var splitCount = 0;
      var flush = function() {
        if (!pending) return;
        fragment.appendChild(document.createTextNode(pending));
        pending = '';
      };
      chars.forEach(function(char) {
        if (splitCount < splitLimit && self.isJapaneseBreakCharacter(char)) {
          flush();
          fragment.appendChild(document.createTextNode(char));
          splitCount += 1;
        } else {
          pending += char;
        }
      });
      if (splitCount === 0) return;
      flush();
      node.replaceWith(fragment);
    });
  },
  calculateProgress: function() {
    var vertical = this.isVertical();
    var walker = this.createWalker();
    var totalChars = 0;
    var exploredChars = 0;
    var node;
    while (node = walker.nextNode()) {
      var nodeLen = this.countChars(node.textContent);
      totalChars += nodeLen;
      if (nodeLen > 0) exploredChars += this.countCharsBeforeViewport(node, vertical);
    }
    return totalChars > 0 ? exploredChars / totalChars : 0;
  },
  restoreProgress: async function(progress) {
    await document.fonts.ready;
    if (progress <= 0) {
      this.scrollToChapterStart();
      requestAnimationFrame(() => {
        this.scrollToChapterStart();
        this.notifyRestoreComplete();
      });
      return;
    }
    var walker = this.createWalker();
    var totalChars = 0;
    var node;
    while (node = walker.nextNode()) {
      totalChars += this.countChars(node.textContent);
    }
    if (totalChars <= 0) {
      this.notifyRestoreComplete();
      return;
    }
    var targetCharCount = Math.ceil(totalChars * progress);
    var runningSum = 0;
    var targetNode = null;
    var targetOffset = 0;
    var lastTargetNode = null;
    walker = this.createWalker();
    while (node = walker.nextNode()) {
      var nodeLen = this.countChars(node.textContent);
      if (nodeLen > 0) lastTargetNode = node;
      if ((runningSum + nodeLen) > targetCharCount) {
        targetNode = node;
        targetOffset = this.textOffsetForCharCount(node, Math.max(0, targetCharCount - runningSum));
        break;
      }
      runningSum += nodeLen;
    }
    if (!targetNode) targetNode = lastTargetNode;
    if (targetNode) {
      if (progress >= 0.999999 && targetNode.parentElement) {
        targetNode.parentElement.scrollIntoView({
          block: 'end',
          inline: 'nearest',
          behavior: 'instant'
        });
      } else {
      var range = document.createRange();
      var targetText = targetNode.textContent || '';
      var targetChar = String.fromCodePoint(targetText.codePointAt(targetOffset));
      range.setStart(targetNode, targetOffset);
      range.setEnd(targetNode, Math.min(targetText.length, targetOffset + Math.max(1, targetChar.length)));
      var marker = document.createElement('span');
      marker.style.display = 'inline-block';
      marker.style.width = '1px';
      marker.style.height = '1px';
      range.insertNode(marker);
      marker.scrollIntoView({
        block: progress >= 0.999999 ? 'end' : 'start',
        inline: 'nearest',
        behavior: 'instant'
      });
      var parent = marker.parentNode;
      marker.remove();
      this.normalizeReaderText(parent);
      }
    }
    requestAnimationFrame(() => {
      requestAnimationFrame(() => this.notifyRestoreComplete());
    });
  },
  jumpToFragment: async function(fragment) {
    await document.fonts.ready;
    var rawFragment = (fragment || '').trim();
    var target = rawFragment && (document.getElementById(rawFragment) || document.getElementsByName(rawFragment)[0]);
    if (!target) {
      this.notifyRestoreComplete();
      return false;
    }
    target.scrollIntoView();
    requestAnimationFrame(() => {
      requestAnimationFrame(() => this.notifyRestoreComplete());
    });
    return true;
  },
  paginate: function(direction) {
    var vertical = this.isVertical();
    var root = document.scrollingElement || document.documentElement;
    var before = vertical ? window.scrollX : root.scrollTop;
    if (vertical) {
      window.scrollBy({ left: direction === "forward" ? -window.innerWidth : window.innerWidth, behavior: "instant" });
    } else {
      window.scrollBy({ top: direction === "forward" ? window.innerHeight : -window.innerHeight, behavior: "instant" });
    }
    var after = vertical ? window.scrollX : root.scrollTop;
    return Math.abs(after - before) > 1 ? "scrolled" : "limit";
  }
};
__HOSHI_HIGHLIGHTS_SCRIPT__
window.hoshiReader.initialize = function() {
  if (window.hoshiReader.didInitialize) return;
  window.hoshiReader.didInitialize = true;
  var viewport = document.querySelector('meta[name="viewport"]');
  if (viewport) { viewport.remove(); }
  var newViewport = document.createElement('meta');
  newViewport.name = 'viewport';
  newViewport.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
  document.head.appendChild(newViewport);
  document.documentElement.style.setProperty('--hoshi-vertical-padding-block', (window.innerHeight * __HOSHI_VERTICAL_PADDING_BLOCK_RATIO__) + 'px');
  document.documentElement.style.setProperty('--hoshi-vertical-padding-gap', (window.innerHeight * __HOSHI_VERTICAL_PADDING_GAP_RATIO__) + 'px');
  document.documentElement.style.setProperty('--hoshi-continuous-height', window.innerHeight + 'px');
  document.documentElement.style.setProperty('--hoshi-image-max-width', Math.max(1, Math.floor(window.innerWidth * __HOSHI_IMAGE_WIDTH_VIEWPORT_RATIO__) - __HOSHI_IMAGE_WIDTH_REDUCTION_PX__) + 'px');
  document.documentElement.style.setProperty('--hoshi-image-max-height', Math.max(1, window.innerHeight - __HOSHI_BOTTOM_OVERLAP_PX__) + 'px');
  function setupReaderImage(element, src, wrap, blurElement) {
  if (!element || !src) return;
  blurElement = blurElement || element;
  if (__HOSHI_BLUR_IMAGES__) {
    blurElement.classList.add('blurred');
    if (wrap && !blurElement.parentElement?.classList.contains('blur-wrapper')) {
      var target = document.createElement('span');
      target.className = 'blur-wrapper';
      blurElement.parentNode.insertBefore(target, blurElement);
      target.appendChild(blurElement);
    }
  }
  element.addEventListener('click', function(event) {
    event.preventDefault();
    event.stopPropagation();
    if (blurElement.classList.contains('blurred')) {
      blurElement.classList.remove('blurred');
      return;
    }
    if (window.HoshiReaderImage && window.HoshiReaderImage.postMessage) {
      HoshiReaderImage.postMessage(new URL(src, document.baseURI).href);
    }
  });
}
  var svgImages = Array.from(document.querySelectorAll('svg image'));
  svgImages.forEach(function(svgImage) {
    var svg = svgImage.closest('svg');
    if (!svg) return;
    if (svg.getAttribute('preserveAspectRatio') === 'none') {
      svg.setAttribute('preserveAspectRatio', 'xMidYMid meet');
    }
    var svgImageSrc = svgImage.href && svgImage.href.baseVal ? svgImage.href.baseVal : (svgImage.getAttribute('href') || svgImage.getAttribute('xlink:href'));
    setupReaderImage(svgImage, svgImageSrc, false, svg);
  });
  var images = Array.from(document.querySelectorAll('img'));
  var imagePromises = images.map(function(img) {
    return new Promise(function(resolve) {
      var isGaiji = img.classList.contains('gaiji') || img.classList.contains('gaiji-line');
      var mark = function() {
        if (!isGaiji && (img.naturalWidth > 256 || img.naturalHeight > 256)) {
          img.classList.add('block-img');
          setupReaderImage(img, img.currentSrc || img.src, true);
        }
        resolve();
      };
      if (img.complete && img.naturalWidth > 0) {
        mark();
      } else {
        img.onload = mark;
        img.onerror = function() { resolve(); };
      }
    });
  });
  Promise.all(imagePromises).then(function() {
    if (!images.length) return;
    return new Promise(function(resolve) { setTimeout(resolve, 50); });
  }).then(function() {
    window.hoshiReader.normalizeRubyTextNodes();
    window.hoshiReader.stabilizeRubyAdjacentTextNodes();
    window.hoshiReader.buildNodeOffsets();
    __HOSHI_RESTORE_SCRIPTS__
  });
};
window.addEventListener('load', function() {
  window.hoshiReader.initialize();
});
if (document.readyState === 'complete') {
  window.hoshiReader.initialize();
}
