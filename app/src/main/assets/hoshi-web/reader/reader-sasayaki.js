  collectSasayakiCueRanges: function(cues) {
    if (!cues.length) return [];
    var entries = [];
    var walker = this.createWalker();
    var node;
    while (node = walker.nextNode()) entries.push({ node: node, text: node.textContent });
    var domText = window.hoshiReaderDomText;
    var index = domText.createSasayakiTextIndex(document.body, entries);
    return cues.map(function(cue) {
      return { id: cue.id, ranges: domText.sasayakiSegments(index, index.range(cue.start, cue.length)) };
    });
  },
   applySasayakiCues: function(cues) {
     var activeCueId = this.activeCueId;
     this.resetSasayakiCues();
     var cueRanges = this.collectSasayakiCueRanges(cues);
     this.rememberSasayakiCueSources(cueRanges);
     if (this.isEInkMode()) {
       this.cueGeometryRanges = this.buildSasayakiGeometryRanges(cueRanges);
     }
     this.prepareSasayakiInlineTargets(cueRanges);
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
           sasayakiInlineTargetsForCue: function(cueId) {
             return this.cueWrappers.get(cueId) || [];
           },
           hasSasayakiCueTarget: function(cueId) {
             return (this.cueGeometryRanges.get(cueId) || []).length > 0 ||
               (this.cueWrappers.get(cueId) || []).length > 0;
           },
           ensureSasayakiInlineTargetsForCue: function(cueId) {
             if (this.isEInkMode()) return;
             var source = this.cueSourceRanges.get(cueId);
             if (!source) return;
             if (!this.sasayakiInlineTargetsForCue(cueId).length) {
               this.prepareSasayakiInlineTargets([source]);
             }
           },
           prepareSasayakiInlineTargets: function(cueRanges) {
            if (!this.isEInkMode()) {
              this.wrapSasayakiCueRanges(cueRanges);
            }
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
    if (!cue) return;
    var cueId = typeof cue === 'string' ? cue : cue.id;
    if (!cueId) return;
    var existing = this.cueGeometryRanges.get(cueId);
    if (existing && existing.length) return;
    if (typeof cue === 'string') {
      var targets = this.sasayakiInlineTargetsForCue(cueId);
      var targetRanges = [];
      for (var i = 0; i < targets.length; i++) {
        var targetRange = document.createRange();
        targetRange.selectNodeContents(targets[i]);
        targetRanges.push(targetRange);
      }
      if (targetRanges.length) this.cueGeometryRanges.set(cueId, targetRanges);
      return;
    }
     var cueRanges = this.collectSasayakiCueRanges([cue]);
     this.rememberSasayakiCueSources(cueRanges);
     var geometryRanges = this.buildSasayakiGeometryRanges(cueRanges).get(cueId) || [];
     if (geometryRanges.length) this.cueGeometryRanges.set(cueId, geometryRanges);
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
    var wrappers = this.cueWrappers.get(cueId) || [];
    wrappers.forEach(function(wrapper) { wrapper.classList.remove('hoshi-sasayaki-active'); });
  },
  applyInlineSasayakiCue: function(cueId) {
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
      this.ensureSasayakiCueGeometry(this.activeCueId);
      this.renderSasayakiOverlay();
     } else {
       this.clearSasayakiOverlay();
       this.ensureSasayakiInlineTargetsForCue(this.activeCueId);
       this.applyInlineSasayakiCue(this.activeCueId);
     }
   },
  sasayakiMediaElements: function() {
    return Array.from(document.body.querySelectorAll('img, svg, image, video, canvas, picture, table, iframe, object, embed')).filter(function(element) {
      var tag = String(element.tagName || '').toLowerCase();
      if (
        tag === 'img' &&
        window.hoshiReaderMediaSemantics.isGaijiImage(element)
      ) return false;
      var rect = element.getBoundingClientRect();
      return rect && rect.width > 0 && rect.height > 0;
    });
  },
  sasayakiMediaStopsBetween: function(startScroll, endScroll, includeStartBoundary, includeEndBoundary) {
    if (includeEndBoundary === undefined) includeEndBoundary = includeStartBoundary;
    var forward = startScroll <= endScroll;
    var seen = new Set();
    var stops = [];
    var elements = this.sasayakiMediaElements();
    for (var i = 0; i < elements.length; i++) {
      var scroll = this.sasayakiMediaScrollForElement(elements[i]);
      if (scroll === null || scroll === undefined) continue;
      if (forward) {
        if (includeStartBoundary ? scroll < startScroll - 0.5 : scroll <= startScroll + 0.5) continue;
        if (includeEndBoundary ? scroll > endScroll + 0.5 : scroll >= endScroll - 0.5) continue;
      } else {
        if (includeStartBoundary ? scroll > startScroll + 0.5 : scroll >= startScroll - 0.5) continue;
        if (includeEndBoundary ? scroll < endScroll - 0.5 : scroll <= endScroll + 0.5) continue;
      }
      var key = String(scroll);
      if (seen.has(key)) continue;
      seen.add(key);
      stops.push({ scroll: scroll });
    }
    stops.sort(function(a, b) { return a.scroll - b.scroll; });
    if (startScroll > endScroll) stops.reverse();
    return stops;
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
     this.cueSourceRanges.clear();
    this.cueGeometryRanges.clear();
    var self = this;
    this.cueWrappers.forEach(function(wrappers) { self.unwrap(wrappers); });
    this.cueWrappers.clear();
    this.activeCueId = null;
    this.clearSasayakiOverlay();
  },
