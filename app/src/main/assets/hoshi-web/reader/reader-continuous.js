__HOSHI_READER_VIEWPORT_SCRIPT__
__HOSHI_READER_TEXT_SEMANTICS_SCRIPT__
__HOSHI_READER_DOM_TEXT_SCRIPT__
__HOSHI_READER_MEDIA_SEMANTICS_SCRIPT__
__HOSHI_READER_LAYOUT_SEMANTICS_SCRIPT__

 window.hoshiReader = {
   cueWrappers: new Map(),
   cueSourceRanges: new Map(),
   cueGeometryRanges: new Map(),
  activeCueId: null,
  nodeStartOffsets: new WeakMap(),
  nodeStartRawOffsets: new WeakMap(),
  isVertical: function() {
    return window.getComputedStyle(document.body).writingMode === "vertical-rl";
  },
  contentViewport: function() {
    return document.body.getBoundingClientRect();
  },
  scrollBy: function(delta) {
    document.body.scrollBy(delta);
  },
  scrollTo: function(position) {
    document.body.scrollTo(position);
  },
  isAtChapterBoundary: function(direction) {
    var root = document.body;
    var vertical = this.isVertical();
    var position = vertical ? -root.scrollLeft : root.scrollTop;
    var extent = vertical ? root.scrollWidth - root.clientWidth : root.scrollHeight - root.clientHeight;
    return direction === 'forward' ? position >= extent - 1 : position <= 1;
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
  textSemantics: function() {
    if (!window.hoshiReaderTextSemantics) {
      throw new Error('hoshiReaderTextSemantics is required for reader text semantics');
    }
    return window.hoshiReaderTextSemantics;
  },
  normalizeText: function(text) {
    return this.textSemantics().normalizeText(text);
  },
  countChars: function(text) {
    return this.textSemantics().countChars(text);
  },
  countRawChars: function(text) {
    return this.textSemantics().countRawChars(text);
  },
  isMatchableChar: function(char) {
    return this.textSemantics().isMatchableChar(char);
  },
  domText: function() {
    if (!window.hoshiReaderDomText) {
      throw new Error('hoshiReaderDomText is required for reader DOM text normalization');
    }
    return window.hoshiReaderDomText;
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
    window.scrollTo(0, 0);
    document.documentElement.scrollTop = 0;
    document.documentElement.scrollLeft = 0;
    this.scrollTo({ left: 0, top: 0, behavior: 'instant' });
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
    // Wrapping/unwrapping moves text nodes and invalidates live CSS Ranges.
    if (window.hoshiHighlights?.searchRange) window.hoshiHighlights.refreshSearchHighlight();
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
    var viewport = this.contentViewport();
    if (vertical) {
      if (minStart >= viewport.right) return totalChars;
      if (maxEnd <= viewport.right || minStart === Infinity) return 0;
    } else {
      if (maxEnd <= viewport.top) return totalChars;
      if (minStart >= viewport.top || minStart === Infinity) return 0;
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
    // Font/ruby metric boxes can overlap adjacent lines by a fraction of a pixel.
    // A clipped sliver must not become the saved first line on every reopen.
    var viewport = this.contentViewport();
    return vertical ? (rect.left + rect.right) / 2 >= viewport.right : (rect.top + rect.bottom) / 2 <= viewport.top;
  },
  restoreLineEdge: function(targetNode, targetRect, vertical) {
    var scope = targetNode.parentElement;
    while (scope && scope !== document.body) {
      var display = window.getComputedStyle(scope).display || '';
      if (/^(block|flow-root|list-item|table.*|flex|grid)$/.test(display)) break;
      scope = scope.parentElement;
    }
    var edge = vertical ? targetRect.right : targetRect.top;
    var inTargetLine = function(rect) {
      if (rect.width <= 0 || rect.height <= 0) return false;
      // Adjacent metric boxes can overlap at tight line heights; their centers
      // still belong to different lines. Ruby uses its base segment's center.
      return vertical
        ? Math.abs((rect.left + rect.right) - (targetRect.left + targetRect.right)) < Math.min(rect.width, targetRect.width)
        : Math.abs((rect.top + rect.bottom) - (targetRect.top + targetRect.bottom)) < Math.min(rect.height, targetRect.height);
    };
    var walker = document.createTreeWalker(scope || document.body, NodeFilter.SHOW_TEXT);
    var range = document.createRange();
    var node;
    while (node = walker.nextNode()) {
      if (node === targetNode) continue;
      var annotation = node.parentElement && node.parentElement.closest('rt');
      var annotationInLine = false;
      if (annotation) {
        // Match the annotation's base segment, since rt can lie wholly outside
        // the base metric box. Adjacent ruby segments may occupy another line.
        for (var base = annotation.previousSibling; base; base = base.previousSibling) {
          var tag = base.nodeType === Node.ELEMENT_NODE ? base.tagName.toLowerCase() : '';
          if (tag === 'rt') break;
          if (tag === 'rp') continue;
          range.selectNodeContents(base);
          if (Array.from(range.getClientRects()).some(inTargetLine)) {
            annotationInLine = true;
            break;
          }
        }
        if (!annotationInLine) continue;
      }
      range.selectNodeContents(node);
      var rects = range.getClientRects();
      for (var i = 0; i < rects.length; i++) {
        var rect = rects[i];
        if (rect.width <= 0 || rect.height <= 0) continue;
        // Include same-line text and annotations, never a neighboring line.
        if (annotationInLine || inTargetLine(rect)) {
          edge = vertical ? Math.max(edge, rect.right) : Math.min(edge, rect.top);
        }
      }
    }
    return edge;
  },
  scrollToTarget: function(target) {
    var rect = this.getRect(target);
    if (this.sasayakiCueRectIsVisible(rect)) return false;
    if (typeof target.scrollIntoView === 'function') {
      target.scrollIntoView({ block: 'start', inline: 'nearest', behavior: 'instant' });
    } else {
      var viewport = this.contentViewport();
      this.scrollBy({
        left: this.isVertical() ? rect.right - viewport.right : 0,
        top: this.isVertical() ? 0 : rect.top - viewport.top,
        behavior: 'instant'
      });
    }
    return true;
  },
__HOSHI_READER_SASAYAKI_SCRIPT__
  sasayakiScrollPosition: function() {
    return this.isVertical() ? document.body.scrollLeft : document.body.scrollTop;
  },
  sasayakiScrollTargetForRect: function(rect) {
    var viewport = this.contentViewport();
    var current = this.sasayakiScrollPosition();
    return this.isVertical() ? current + rect.right - viewport.right : current + rect.top - viewport.top;
  },
  sasayakiTargetForCue: function(cue) {
    var cueId = typeof cue === 'string' ? cue : cue.id;
    if (!cueId) return null;
    if (this.isEInkMode()) {
      this.ensureSasayakiCueGeometry(cue);
      return (this.cueGeometryRanges.get(cueId) || [])[0] || null;
    }
    var targets = this.sasayakiInlineTargetsForCue(cueId);
    if (!targets.length && typeof cue !== 'string') {
      this.wrapSasayakiCue(cue);
      targets = this.sasayakiInlineTargetsForCue(cueId);
    }
    return targets[0] || null;
  },
  sasayakiRectForTarget: function(target) {
    if (!target) return null;
    if (target.nodeType === Node.ELEMENT_NODE && target.classList.contains('hoshi-sasayaki-cue')) {
      var range = document.createRange();
      range.selectNodeContents(target);
      var rangeRect = this.getRect(range);
      if (rangeRect && rangeRect.width > 0 && rangeRect.height > 0) {
        return rangeRect;
      }
    }
    return this.getRect(target);
  },
  sasayakiCueRect: function(cue) {
    var cueId = typeof cue === 'string' ? cue : cue.id;
    if (!cueId) return null;
    var target = this.sasayakiTargetForCue(cue);
    if (!target) return null;
    var rect = this.sasayakiRectForTarget(target);
    if (rect && rect.width > 0 && rect.height > 0) return rect;
    this.ensureSasayakiCueGeometry(cue);
    var geometryTarget = (this.cueGeometryRanges.get(cueId) || [])[0];
    if (!geometryTarget) return null;
    var geometryRect = this.sasayakiRectForTarget(geometryTarget);
    if (!geometryRect || geometryRect.width <= 0 || geometryRect.height <= 0) return null;
    return geometryRect;
  },
  sasayakiCueRectIsVisible: function(rect) {
    if (!rect || rect.width <= 0 || rect.height <= 0) return false;
    var viewport = this.contentViewport();
    return this.isVertical()
      ? rect.left >= viewport.left && rect.right <= viewport.right
      : rect.top >= viewport.top && rect.bottom <= viewport.bottom;
  },
  sasayakiCueScrollTarget: function(cue) {
    var rect = this.sasayakiCueRect(cue);
    if (!rect) return null;
    return this.sasayakiScrollTargetForRect(rect);
  },
  sasayakiMediaScrollForElement: function(element) {
    var rect = element.getBoundingClientRect();
    if (!rect || rect.width <= 0 || rect.height <= 0) return null;
    return this.sasayakiScrollTargetForRect(rect);
  },
  sasayakiMediaStopsBeforeCue: function(cue) {
    var targetRect = this.sasayakiCueRect(cue);
    if (!targetRect || this.sasayakiCueRectIsVisible(targetRect)) return [];
    var targetScroll = this.sasayakiScrollTargetForRect(targetRect);
    if (targetScroll === null || targetScroll === undefined) return [];
    return this.sasayakiMediaStopsBetween(this.sasayakiScrollPosition(), targetScroll, true, false);
  },
  sasayakiMediaStopsToChapterEnd: function() {
    var root = document.body;
    var endScroll = this.isVertical()
      ? -Math.max(0, root.scrollWidth - root.clientWidth)
      : Math.max(0, root.scrollHeight - root.clientHeight);
    return this.sasayakiMediaStopsBetween(this.sasayakiScrollPosition(), endScroll, true, true);
  },
  showSasayakiMediaStop: function(stop) {
    window.hoshiHighlights?.clearSearchHighlight?.();
    var scroll = Number(stop && stop.scroll);
    if (!Number.isFinite(scroll)) return null;
    this.scrollTo({
      left: this.isVertical() ? scroll : 0,
      top: this.isVertical() ? 0 : scroll,
      behavior: 'instant'
    });
    return this.calculateProgress();
  },
  highlightSasayakiCue: function(cue, reveal) {
    if (reveal) window.hoshiHighlights?.clearSearchHighlight?.();
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
    if (!targets.length && typeof cue !== 'string') {
      this.wrapSasayakiCue(cue);
      targets = this.sasayakiInlineTargetsForCue(cueId);
    }
    if (!targets.length) return null;
    this.activeCueId = cueId;
    this.applyInlineSasayakiCue(cueId);
    var scrollTarget = targets[0];
    if (reveal && scrollTarget && this.scrollToTarget(scrollTarget)) {
      return this.calculateProgress();
    }
    return null;
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
    this.domText().normalizeReaderText(this, parent);
  },
  normalizeRubyTextNodes: function(root) {
    this.domText().normalizeRubyTextNodes(root);
  },
  isJapaneseBreakCharacter: function(text) {
    return this.domText().isJapaneseBreakCharacter(text);
  },
  stabilizeRubyAdjacentTextNodes: function(root) {
    this.domText().stabilizeRubyAdjacentTextNodes(this, root);
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
        // An inline marker aligns its own baseline box, clipping half the text line.
        // Use the existing glyph geometry without changing the chapter's layout.
        var rect = this.getRect(range);
        var vertical = this.isVertical();
        var edge = this.restoreLineEdge(targetNode, rect, vertical);
        var viewport = this.contentViewport();
        this.scrollBy({
          left: vertical ? edge - viewport.right : 0,
          top: vertical ? 0 : edge - viewport.top,
          behavior: 'instant'
        });
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
    window.hoshiHighlights?.clearSearchHighlight?.();
    var vertical = this.isVertical();
    var root = document.body;
    var viewport = this.contentViewport();
    var before = vertical ? root.scrollLeft : root.scrollTop;
    this.scrollBy({
      left: vertical ? (direction === 'forward' ? -viewport.width : viewport.width) : 0,
      top: vertical ? 0 : (direction === 'forward' ? viewport.height : -viewport.height),
      behavior: 'instant'
    });
    var after = vertical ? root.scrollLeft : root.scrollTop;
    return Math.abs(after - before) > 1 ? "scrolled" : "limit";
  }
};
__HOSHI_HIGHLIGHTS_SCRIPT__
window.hoshiReader.initialize = function() {
  if (window.hoshiReader.didInitialize) return;
  window.hoshiReader.didInitialize = true;
  window.hoshiReaderViewport.ensureDeviceViewport();
  document.body.addEventListener('scroll', function() {
    window.HoshiReaderScroll?.postMessage(__HOSHI_RESTORE_TOKEN_LITERAL__);
  });
  var style = document.documentElement.style;
  style.setProperty('--page-width', window.innerWidth + 'px');
  style.setProperty('--page-height', window.innerHeight + 'px');
  style.setProperty('--hoshi-continuous-left', (window.innerWidth * __HOSHI_CONTINUOUS_HORIZONTAL_PADDING_RATIO__) + 'px');
  style.setProperty('--hoshi-continuous-top', (window.innerHeight * __HOSHI_CONTINUOUS_VERTICAL_PADDING_RATIO__) + 'px');
  style.setProperty('--hoshi-continuous-width', (window.innerWidth * (1 - 2 * __HOSHI_CONTINUOUS_HORIZONTAL_PADDING_RATIO__)) + 'px');
  style.setProperty('--hoshi-continuous-height', (window.innerHeight * (1 - 2 * __HOSHI_CONTINUOUS_VERTICAL_PADDING_RATIO__)) + 'px');
  window.hoshiReaderDocumentPreparation?.resume();
  var viewport = window.hoshiReader.contentViewport();
  document.documentElement.style.setProperty('--hoshi-vertical-padding-block', (viewport.height * __HOSHI_VERTICAL_PADDING_BLOCK_RATIO__) + 'px');
  document.documentElement.style.setProperty('--hoshi-vertical-padding-gap', (viewport.height * __HOSHI_VERTICAL_PADDING_GAP_RATIO__) + 'px');
  document.documentElement.style.setProperty('--hoshi-continuous-height', viewport.height + 'px');
  document.documentElement.style.setProperty('--hoshi-reader-visible-height', viewport.height + 'px');
  document.documentElement.style.setProperty('--hoshi-image-max-width', Math.max(1, Math.floor(viewport.width * __HOSHI_IMAGE_WIDTH_VIEWPORT_RATIO__) - __HOSHI_IMAGE_WIDTH_REDUCTION_PX__) + 'px');
  document.documentElement.style.setProperty('--hoshi-image-max-height', Math.max(1, Math.floor(viewport.height * __HOSHI_IMAGE_HEIGHT_VIEWPORT_RATIO__)) + 'px');
  var images = Array.from(document.querySelectorAll('img'));
  var imageSetupPromise = window.hoshiReaderMediaSemantics.setupReaderImages(document, {
    blurImages: __HOSHI_BLUR_IMAGES__,
    imageBridge: window.HoshiReaderImage,
    waitForImages: true
  });
  Promise.all([
    Promise.resolve(document.fonts && document.fonts.ready),
    imageSetupPromise
  ]).then(function() {
    if (!images.length) return;
    return new Promise(function(resolve) { setTimeout(resolve, 50); });
  }).then(function() {
    window.hoshiReaderLayoutSemantics.sanitizeInlineBlocks(document, window.hoshiReader.isVertical());
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
