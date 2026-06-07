window.hoshiReader = {
  pageHeight: 0,
  pageWidth: 0,
  nativeSelectionActive: false,
  nativeSelectionScrollPosition: null,
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
  paginationMetrics: null,
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
    this.warmPaginationMetrics();
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
    this.paginationMetrics = null;
  },
  countCharsBeforeViewport: function(node, context) {
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
      var start = context.vertical ? rect.top : rect.left;
      var end = context.vertical ? rect.bottom : rect.right;
      minStart = Math.min(minStart, start);
      maxEnd = Math.max(maxEnd, end);
    }
    if (maxEnd <= 0) return totalChars;
    if (minStart >= 0 || minStart === Infinity) return 0;
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
      if (this.isTextOffsetBeforeViewport(node, offsets[mid], text, context)) {
        low = mid + 1;
      } else {
        firstVisible = mid;
        high = mid - 1;
      }
    }
    return prefixCounts[firstVisible];
  },
  isTextOffsetBeforeViewport: function(node, offset, text, context) {
    var char = String.fromCodePoint(text.codePointAt(offset));
    if (!char) return false;
    var range = document.createRange();
    range.setStart(node, offset);
    range.setEnd(node, offset + char.length);
    var rect = this.getRect(range);
    if (!rect || rect.width <= 0 || rect.height <= 0) return false;
    return (context.vertical ? rect.bottom : rect.right) <= 0;
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
       var didScroll = reveal && geometryTarget && this.scrollToRange(geometryTarget);
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
    if (reveal) {
      var range = document.createRange();
      var scrollRange = targets.length ? targets[0] : rubyElements[0];
      if (scrollRange && scrollRange.nodeType === Node.ELEMENT_NODE && scrollRange.classList.contains('hoshi-sasayaki-cue')) {
        range.selectNodeContents(scrollRange);
        scrollRange = range;
      }
      if (this.scrollToRange(scrollRange)) {
        return this.calculateProgress();
      }
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
  getScrollContext: function() {
    var vertical = this.isVertical();
    var scrollEl = document.body;
    var pageSize = Math.max(1, vertical ? (this.pageHeight || window.innerHeight) : (this.pageWidth || window.innerWidth));
    var totalSize = vertical ? scrollEl.scrollHeight : scrollEl.scrollWidth;
    var maxScroll = Math.max(0, totalSize - pageSize);
    return { vertical: vertical, scrollEl: scrollEl, pageSize: pageSize, maxScroll: maxScroll };
  },
  getPagePosition: function(context) {
    return context.vertical ? context.scrollEl.scrollTop : context.scrollEl.scrollLeft;
  },
  lockRootViewport: function() {
    var root = document.documentElement;
    var didScroll = false;
    if (root.scrollTop !== 0) {
      root.scrollTop = 0;
      didScroll = true;
    }
    if (root.scrollLeft !== 0) {
      root.scrollLeft = 0;
      didScroll = true;
    }
    if (window.scrollX !== 0 || window.scrollY !== 0) {
      window.scrollTo(0, 0);
      didScroll = true;
    }
    return didScroll;
  },
  assignPagePosition: function(context, position) {
    if (context.vertical) {
      context.scrollEl.scrollTop = position;
    } else {
      context.scrollEl.scrollLeft = position;
    }
    this.lockRootViewport();
  },
  setPagePosition: function(context, position) {
    var clamped = Math.min(Math.max(0, position), context.maxScroll);
    window.lastPageScroll = clamped;
    this.assignPagePosition(context, clamped);
    this.refreshSasayakiCuePresentation();
    return clamped;
  },
  setNativeSelectionActive: function(active) {
    var context = this.getScrollContext();
    if (active) {
      this.nativeSelectionActive = true;
      this.nativeSelectionScrollPosition = this.getPagePosition(context);
      window.lastPageScroll = this.nativeSelectionScrollPosition;
      return;
    }
    if (this.nativeSelectionActive && this.nativeSelectionScrollPosition !== null && this.nativeSelectionScrollPosition !== undefined) {
      var lockedScroll = Math.min(Math.max(0, this.nativeSelectionScrollPosition), context.maxScroll);
      this.assignPagePosition(context, lockedScroll);
      window.lastPageScroll = lockedScroll;
    }
    this.nativeSelectionActive = false;
    this.nativeSelectionScrollPosition = null;
  },
  handlePagedBodyScroll: function() {
    this.lockRootViewport();
    var context = this.getScrollContext();
    if (context.pageSize <= 0) return;
    var currentScroll = this.getPagePosition(context);
    if (this.nativeSelectionActive) {
      var lockedScroll = this.nativeSelectionScrollPosition;
      if (lockedScroll === null || lockedScroll === undefined) {
        lockedScroll = window.lastPageScroll || 0;
      }
      lockedScroll = Math.min(Math.max(0, lockedScroll), context.maxScroll);
      if (Math.abs(currentScroll - lockedScroll) > 0.5) {
        this.assignPagePosition(context, lockedScroll);
      }
      window.lastPageScroll = lockedScroll;
      return;
    }
    var snappedScroll = Math.round(currentScroll / context.pageSize) * context.pageSize;
    snappedScroll = Math.min(Math.max(0, snappedScroll), context.maxScroll);
    if (Math.abs(currentScroll - snappedScroll) > 1) {
      this.assignPagePosition(context, window.lastPageScroll || 0);
    } else {
      window.lastPageScroll = snappedScroll;
    }
  },
  registerSnapScroll: function(initialScroll) {
    if (window.snapScrollRegistered) return;
    window.snapScrollRegistered = true;
    window.lastPageScroll = initialScroll;
    this.lockRootViewport();
    window.addEventListener('scroll', () => {
      if (this.lockRootViewport()) {
        requestAnimationFrame(() => this.lockRootViewport());
      }
    }, { passive: true });
    document.body.addEventListener('scroll', () => {
      this.handlePagedBodyScroll();
    }, { passive: true });
  },
  alignToPage: function(context, offset) {
    return Math.floor(Math.max(0, offset) / context.pageSize) * context.pageSize;
  },
  alignContentStartToPage: function(context, offset) {
    var safeOffset = Math.max(0, offset);
    var nearestPage = Math.round(safeOffset / context.pageSize) * context.pageSize;
    if (Math.abs(safeOffset - nearestPage) < 1) {
      return nearestPage;
    }
    return this.alignToPage(context, safeOffset);
  },
  scrollToRange: function(range) {
    var context = this.getScrollContext();
    if (context.pageSize <= 0) return false;
    var rect = this.getRect(range);
    var currentScroll = this.getPagePosition(context);
    var anchor = (context.vertical ? (rect.top + rect.bottom) / 2 : (rect.left + rect.right) / 2) + currentScroll;
    var targetScroll = this.alignToPage(context, anchor);
    if (targetScroll === currentScroll) return false;
    this.setPagePosition(context, targetScroll);
    var self = this;
    requestAnimationFrame(function() {
      self.setPagePosition(context, targetScroll);
    });
    return true;
  },
  contentLastPageScroll: function(context) {
    var metrics = this.paginationMetrics || this.buildPaginationMetrics();
    return metrics.maxScroll;
  },
  contentFirstPageScroll: function(context) {
    var metrics = this.paginationMetrics || this.buildPaginationMetrics();
    return metrics.minScroll;
  },
  warmPaginationMetrics: function() {
    if (this.paginationMetrics) return;
    var run = () => {
      if (this.paginationMetrics) return;
      this.buildPaginationMetrics();
    };
    if (window.requestIdleCallback) {
      window.requestIdleCallback(run, { timeout: 1000 });
    } else {
      setTimeout(run, 200);
    }
  },
  buildPaginationMetrics: function() {
    var context = this.getScrollContext();
    var currentScroll = this.getPagePosition(context);
    var maxAlignedScroll = Math.floor(context.maxScroll / context.pageSize) * context.pageSize;
    if (context.pageSize <= 0) {
      var emptyMetrics = { minScroll: 0, maxScroll: 0, totalChars: 0, progressStops: [] };
      this.paginationMetrics = emptyMetrics;
      return emptyMetrics;
    }
    var lastContentEdge = 0;
    var firstContentEdge = null;
    var progressStops = [];
    var exploredChars = 0;
    var totalChars = 0;
    var walker = this.createWalker();
    var node;
    while (node = walker.nextNode()) {
      var nodeLen = this.countChars(node.textContent);
      totalChars += nodeLen;
      if (nodeLen <= 0) continue;
      var range = document.createRange();
      range.selectNodeContents(node);
      var rects = range.getClientRects();
      var progressRect = this.getRect(range);
      var nodeStartEdge = progressRect && progressRect.width > 0 && progressRect.height > 0
        ? (context.vertical ? progressRect.top : progressRect.left) + currentScroll
        : null;
      for (var i = 0; i < rects.length; i++) {
        var rect = rects[i];
        if (rect.width <= 0 || rect.height <= 0) continue;
        var startEdge = (context.vertical ? rect.top : rect.left) + currentScroll;
        var endEdge = (context.vertical ? rect.bottom : rect.right) + currentScroll;
        firstContentEdge = firstContentEdge === null ? startEdge : Math.min(firstContentEdge, startEdge);
        lastContentEdge = Math.max(lastContentEdge, endEdge);
      }
      if (nodeStartEdge !== null) {
        progressStops.push({ scroll: nodeStartEdge, exploredChars: exploredChars + nodeLen });
      }
      exploredChars += nodeLen;
    }

    var media = document.querySelectorAll('img, svg, image, video, canvas');
    for (var j = 0; j < media.length; j++) {
      var mediaRect = media[j].getBoundingClientRect();
      if (mediaRect.width <= 0 || mediaRect.height <= 0) continue;
      var mediaStart = (context.vertical ? mediaRect.top : mediaRect.left) + currentScroll;
      var mediaEnd = (context.vertical ? mediaRect.bottom : mediaRect.right) + currentScroll;
      firstContentEdge = firstContentEdge === null ? mediaStart : Math.min(firstContentEdge, mediaStart);
      lastContentEdge = Math.max(lastContentEdge, mediaEnd);
    }

    var minScroll = firstContentEdge === null ? 0 : Math.min(maxAlignedScroll, this.alignContentStartToPage(context, firstContentEdge));
    var lastContentScroll = lastContentEdge <= 0 ? 0 : this.alignToPage(context, lastContentEdge - 1);
    var maxScroll = Math.min(context.maxScroll, lastContentScroll);
    progressStops.sort(function(a, b) { return a.scroll - b.scroll; });
    var metrics = {
      minScroll: minScroll,
      maxScroll: maxScroll,
      totalChars: totalChars,
      progressStops: progressStops
    };
    this.paginationMetrics = metrics;
    return metrics;
  },
  calculateProgress: function() {
    var context = this.getScrollContext();
    var walker = this.createWalker();
    var totalChars = 0;
    var exploredChars = 0;
    var node;
    while (node = walker.nextNode()) {
      var nodeLen = this.countChars(node.textContent);
      totalChars += nodeLen;
      if (nodeLen > 0) exploredChars += this.countCharsBeforeViewport(node, context);
    }
    return totalChars > 0 ? exploredChars / totalChars : 0;
  },
  restoreProgress: async function(progress) {
    await document.fonts.ready;
    var context = this.getScrollContext();
    if (context.pageSize <= 0) {
      this.registerSnapScroll(0);
      this.notifyRestoreComplete();
      return;
    }
    if (progress <= 0) {
      this.setPagePosition(context, 0);
      this.registerSnapScroll(0);
      this.notifyRestoreComplete();
      return;
    }
    if (progress >= 0.99) {
      var lastPage = this.contentLastPageScroll(context);
      lastPage = Math.max(0, lastPage);
      this.setPagePosition(context, lastPage);
      requestAnimationFrame(() => {
        this.setPagePosition(context, lastPage);
        this.registerSnapScroll(lastPage);
        requestAnimationFrame(() => this.notifyRestoreComplete());
      });
      return;
    }
    var walker = this.createWalker();
    var totalChars = 0;
    var node;
    while (node = walker.nextNode()) {
      totalChars += this.countChars(node.textContent);
    }
    var targetCharCount = Math.ceil(totalChars * progress);
    var runningSum = 0;
    var targetNode = null;
    var targetOffset = 0;
    walker = this.createWalker();
    while (node = walker.nextNode()) {
      var nodeLen = this.countChars(node.textContent);
      if ((runningSum + nodeLen) > targetCharCount) {
        targetNode = node;
        targetOffset = this.textOffsetForCharCount(node, Math.max(0, targetCharCount - runningSum));
        break;
      }
      runningSum += nodeLen;
    }
    if (targetNode) {
      var range = document.createRange();
      var targetText = targetNode.textContent || '';
      var targetChar = String.fromCodePoint(targetText.codePointAt(targetOffset));
      range.setStart(targetNode, targetOffset);
      range.setEnd(targetNode, Math.min(targetText.length, targetOffset + Math.max(1, targetChar.length)));
      var rect = this.getRect(range);
      var currentScroll = this.getPagePosition(context);
      var anchor = (context.vertical ? rect.top : rect.left) + currentScroll;
      var targetScroll = this.alignToPage(context, anchor);
      this.setPagePosition(context, targetScroll);
      requestAnimationFrame(() => {
        this.setPagePosition(context, targetScroll);
        this.registerSnapScroll(targetScroll);
      });
    } else {
      var firstPage = this.contentFirstPageScroll(context);
      this.setPagePosition(context, firstPage);
      this.registerSnapScroll(firstPage);
    }
    requestAnimationFrame(() => {
      requestAnimationFrame(() => this.notifyRestoreComplete());
    });
  },
  jumpToFragment: async function(fragment) {
    await document.fonts.ready;
    var context = this.getScrollContext();
    var rawFragment = (fragment || '').trim();
    var target = rawFragment && (document.getElementById(rawFragment) || document.getElementsByName(rawFragment)[0]);
    if (context.pageSize <= 0 || !target) {
      this.registerSnapScroll(this.getPagePosition(context));
      this.notifyRestoreComplete();
      return false;
    }
    var rect = this.getRect(target);
    var currentScroll = this.getPagePosition(context);
    var anchor = (context.vertical ? rect.top : rect.left) + currentScroll;
    var targetScroll = this.alignToPage(context, anchor);
    this.setPagePosition(context, targetScroll);
    requestAnimationFrame(() => {
      this.setPagePosition(context, targetScroll);
      this.registerSnapScroll(targetScroll);
      requestAnimationFrame(() => this.notifyRestoreComplete());
    });
    return true;
  },
  paginate: function(direction) {
    if (this.nativeSelectionActive) return "limit";
    var context = this.getScrollContext();
    if (context.pageSize <= 0) return "limit";
    var currentScroll = this.getPagePosition(context);
    var metrics = this.paginationMetrics || this.buildPaginationMetrics();
    var minAlignedScroll = metrics.minScroll;
    var maxPageScroll = metrics.maxScroll;
    if (direction === "forward") {
      if (currentScroll < (maxPageScroll - 1)) {
        var targetForward = Math.round((currentScroll + context.pageSize) / context.pageSize) * context.pageSize;
        targetForward = Math.min(targetForward, maxPageScroll);
        if (targetForward <= (currentScroll + 1)) return "limit";
        this.setPagePosition(context, targetForward);
        return "scrolled";
      }
      return "limit";
    } else {
      if (currentScroll > (minAlignedScroll + 1)) {
        var targetBack = Math.floor((currentScroll - 1) / context.pageSize) * context.pageSize;
        targetBack = Math.max(minAlignedScroll, targetBack);
        this.setPagePosition(context, targetBack);
        return "scrolled";
      }
      return "limit";
    }
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
  var pageHeight = window.innerHeight + __HOSHI_BOTTOM_OVERLAP_PX__;
  var pageWidth = window.innerWidth;
  document.documentElement.style.setProperty('--hoshi-vertical-padding-block', (window.innerHeight * __HOSHI_VERTICAL_PADDING_BLOCK_RATIO__) + 'px');
  document.documentElement.style.setProperty('--hoshi-vertical-padding-gap', (window.innerHeight * __HOSHI_VERTICAL_PADDING_GAP_RATIO__) + 'px');
  document.documentElement.style.setProperty('--page-height', pageHeight + 'px');
  document.documentElement.style.setProperty('--page-width', pageWidth + 'px');
  document.documentElement.style.setProperty('--hoshi-image-max-width', Math.max(1, Math.floor(pageWidth * __HOSHI_IMAGE_WIDTH_VIEWPORT_RATIO__) - __HOSHI_IMAGE_WIDTH_REDUCTION_PX__) + 'px');
  document.documentElement.style.setProperty('--hoshi-image-max-height', Math.max(1, pageHeight - __HOSHI_BOTTOM_OVERLAP_PX__) + 'px');
  window.hoshiReader.pageHeight = pageHeight;
  window.hoshiReader.pageWidth = pageWidth;
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
  var spacer = document.createElement('div');
  spacer.style.height = __HOSHI_TRAILING_SPACER_HEIGHT_LITERAL__;
  spacer.style.width = __HOSHI_TRAILING_SPACER_WIDTH_LITERAL__;
  spacer.style.display = 'block';
  spacer.style.breakInside = 'avoid';
  document.body.appendChild(spacer);
  window.hoshiReader.normalizeRubyTextNodes();
  window.hoshiReader.stabilizeRubyAdjacentTextNodes();
  Promise.all(imagePromises).then(function() {
    if (!images.length) return;
    return new Promise(function(resolve) { setTimeout(resolve, 50); });
  }).then(function() {
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
