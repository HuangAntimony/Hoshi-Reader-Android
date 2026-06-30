__HOSHI_READER_TEXT_SEMANTICS_SCRIPT__
__HOSHI_READER_DOM_TEXT_SCRIPT__
__HOSHI_READER_MEDIA_SEMANTICS_SCRIPT__

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
__HOSHI_READER_SASAYAKI_SCRIPT__
  sasayakiScrollPosition: function() {
    var root = document.scrollingElement || document.documentElement;
    if (this.isVertical()) {
      var left = window.scrollX;
      if (left === 0 && root.scrollLeft !== 0) left = root.scrollLeft;
      return left;
    }
    var top = root.scrollTop;
    if (top === 0 && window.scrollY !== 0) top = window.scrollY;
    return top;
  },
  sasayakiScrollTargetForRect: function(rect) {
    var current = this.sasayakiScrollPosition();
    if (this.isVertical()) {
      return current + rect.right - window.innerWidth;
    }
    return current + rect.top;
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
    return this.isVertical()
      ? rect.left >= 0 && rect.right <= window.innerWidth
      : rect.top >= 0 && rect.bottom <= window.innerHeight;
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
    var root = document.scrollingElement || document.documentElement;
    var endScroll = this.isVertical()
      ? -Math.max(0, root.scrollWidth - window.innerWidth)
      : Math.max(0, root.scrollHeight - window.innerHeight);
    return this.sasayakiMediaStopsBetween(this.sasayakiScrollPosition(), endScroll, true, true);
  },
  showSasayakiMediaStop: function(stop) {
    var scroll = Number(stop && stop.scroll);
    if (!Number.isFinite(scroll)) return null;
    var root = document.scrollingElement || document.documentElement;
    if (this.isVertical()) {
      window.scrollTo({ left: scroll, top: window.scrollY, behavior: 'instant' });
      root.scrollLeft = scroll;
    } else {
      window.scrollTo({ left: window.scrollX, top: scroll, behavior: 'instant' });
      root.scrollTop = scroll;
    }
    return this.calculateProgress();
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
  document.documentElement.style.setProperty('--hoshi-reader-visible-height', window.innerHeight + 'px');
  document.documentElement.style.setProperty('--hoshi-image-max-width', Math.max(1, Math.floor(window.innerWidth * __HOSHI_IMAGE_WIDTH_VIEWPORT_RATIO__) - __HOSHI_IMAGE_WIDTH_REDUCTION_PX__) + 'px');
  document.documentElement.style.setProperty('--hoshi-image-max-height', Math.max(1, Math.floor(window.innerHeight * __HOSHI_IMAGE_HEIGHT_VIEWPORT_RATIO__)) + 'px');
  var images = Array.from(document.querySelectorAll('img'));
  var imageSetupPromise = window.hoshiReaderMediaSemantics.setupReaderImages(document, {
    blurImages: __HOSHI_BLUR_IMAGES__,
    imageBridge: window.HoshiReaderImage,
    waitForImages: true
  });
  imageSetupPromise.then(function() {
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
