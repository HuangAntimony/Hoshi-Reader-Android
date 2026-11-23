__HOSHI_READER_VIEWPORT_SCRIPT__
__HOSHI_READER_TEXT_SEMANTICS_SCRIPT__
__HOSHI_READER_DOM_TEXT_SCRIPT__
__HOSHI_READER_MEDIA_SEMANTICS_SCRIPT__

window.hoshiReader = {
  cueWrappers: new Map(),
  cueSourceRanges: new Map(),
  cueGeometryRanges: new Map(),
  activeCueId: null,
  restoreToken: __HOSHI_RESTORE_TOKEN_LITERAL__,
  activeChapterIndex: __HOSHI_INITIAL_CHAPTER_INDEX__,
  chapterIndexes: new WeakMap(),
  nodeStartOffsets: { get(node) { return window.hoshiReader.indexForNode(node)?.offsets.get(node); } },
  nodeStartRawOffsets: { get(node) { return window.hoshiReader.indexForNode(node)?.rawOffsets.get(node); } },
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
  configureViewport: function(horizontalInsetRatio, verticalInsetRatio, verticalBlockRatio, verticalGapRatio,
                              imageWidthRatio, imageWidthReductionPx, imageHeightRatio) {
    var width = window.innerWidth;
    var height = window.innerHeight;
    var contentWidth = width * (1 - 2 * horizontalInsetRatio);
    var contentHeight = height * (1 - 2 * verticalInsetRatio);
    var style = document.documentElement.style;
    style.setProperty('--page-width', width + 'px');
    style.setProperty('--page-height', height + 'px');
    style.setProperty('--hoshi-continuous-left', (width * horizontalInsetRatio) + 'px');
    style.setProperty('--hoshi-continuous-top', (height * verticalInsetRatio) + 'px');
    style.setProperty('--hoshi-continuous-width', contentWidth + 'px');
    style.setProperty('--hoshi-continuous-height', contentHeight + 'px');
    style.setProperty('--hoshi-vertical-padding-block', (contentHeight * verticalBlockRatio) + 'px');
    style.setProperty('--hoshi-vertical-padding-gap', (contentHeight * verticalGapRatio) + 'px');
    style.setProperty('--hoshi-reader-visible-height', contentHeight + 'px');
    style.setProperty('--hoshi-image-max-width', Math.max(1, Math.floor(contentWidth * imageWidthRatio) - imageWidthReductionPx) + 'px');
    style.setProperty('--hoshi-image-max-height', Math.max(1, Math.floor(contentHeight * imageHeightRatio)) + 'px');
  },
  chapterRoot: function(index = this.activeChapterIndex) {
    return this.chapters[index];
  },
  chapterForNode: function(node) {
    return node.parentElement?.closest('[data-hoshi-chapter]');
  },
  // Chapter roots are in spine order. Read their live geometry so late layout
  // changes (ruby, images, details, fonts) need no scroll probes or cached bounds.
  visibleChapterIndex: function() {
    var chapters = this.chapters;
    var vertical = this.isVertical();
    var viewport = this.contentViewport();
    var low = 0;
    var high = chapters.length - 1;
    while (low < high) {
      var mid = (low + high) >>> 1;
      var rect = chapters[mid].getBoundingClientRect();
      var before = vertical ? rect.left >= viewport.right - 0.5 : rect.bottom <= viewport.top + 0.5;
      if (before) low = mid + 1;
      else high = mid;
    }
    return low;
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
  notifyRestoreComplete: function(token = this.restoreToken) {
    if (token !== this.restoreToken) return;
    this.progressRestored = true;
    this.savedScrollOffset = this.sasayakiScrollPosition();
    window.HoshiReaderRestore?.postMessage?.(token);
  },
  scrollToChapterStart: function(root) {
    window.scrollTo(0, 0);
    document.documentElement.scrollTop = 0;
    document.documentElement.scrollLeft = 0;
    root.scrollIntoView({ block: 'start', inline: 'nearest', behavior: 'instant' });
  },
  createWalker: function(root = this.chapterRoot()) {
    return document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode: (n) => this.isFurigana(n) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT
    });
  },
  getRect: function(target) {
    var rect = target.getClientRects()[0];
    return rect || target.getBoundingClientRect();
  },
  indexForNode: function(node) {
    var root = this.chapterForNode(node);
    return root && this.ensureChapterIndex(root);
  },
  ensureChapterIndex: function(root) {
    return this.chapterIndexes.get(root) || this.indexChapter(root);
  },
  indexChapter: function(root) {
    var offsets = new WeakMap();
    var rawOffsets = new WeakMap();
    var entries = [];
    var walker = this.createWalker(root);
    var count = 0;
    var rawCount = 0;
    var node;
    while (node = walker.nextNode()) {
      offsets.set(node, count);
      rawOffsets.set(node, rawCount);
      var length = this.countChars(node.textContent);
      if (length) entries.push({ node, start: count, length });
      count += length;
      rawCount += this.countRawChars(node.textContent);
    }
    var index = { offsets, rawOffsets, entries, count };
    this.chapterIndexes.set(root, index);
    return index;
  },
  buildNodeOffsets: function(root = this.chapterRoot()) {
    this.indexChapter(root);
    // Wrapping/unwrapping moves text nodes and invalidates live CSS Ranges.
    if (window.hoshiHighlights?.searchRange) window.hoshiHighlights.refreshSearchHighlight();
  },
  countCharsBeforeViewport: function(entry, rect, vertical, range, viewport) {
    if (vertical ? rect.left >= viewport.right : rect.bottom <= viewport.top) return entry.length;
    if (vertical ? rect.right <= viewport.right : rect.top >= viewport.top) return 0;
    var node = entry.node;
    var text = node.textContent;
    var low = 0;
    var high = text.length;
    while (low < high) {
      var mid = (low + high) >>> 1;
      // DOM Range offsets are UTF-16. Keep each probe on a code-point boundary.
      if (mid > low && text.charCodeAt(mid) >= 0xDC00 && text.charCodeAt(mid) <= 0xDFFF &&
          text.codePointAt(mid - 1) > 0xFFFF) mid--;
      if (this.isTextOffsetBeforeViewport(node, mid, text, vertical, range, viewport)) {
        low = mid + (text.codePointAt(mid) > 0xFFFF ? 2 : 1);
      } else {
        high = mid;
      }
    }
    return this.countChars(text.slice(0, low));
  },
  isTextOffsetBeforeViewport: function(node, offset, text, vertical, range, viewport) {
    var char = String.fromCodePoint(text.codePointAt(offset));
    if (!char) return false;
    range.setStart(node, offset);
    range.setEnd(node, offset + char.length);
    var rect = this.getRect(range);
    if (!rect || rect.width <= 0 || rect.height <= 0) return false;
    // Font/ruby metric boxes can overlap adjacent lines by a fraction of a pixel.
    // A clipped sliver must not become the saved first line on every reopen.
    return vertical ? (rect.left + rect.right) / 2 >= viewport.right : (rect.top + rect.bottom) / 2 <= viewport.top;
  },
  restoreLineEdge: function(targetNode, targetRect, vertical) {
    var scope = targetNode.parentElement;
    var root = this.chapterRoot();
    while (scope && scope !== root) {
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
    var walker = document.createTreeWalker(scope || root, NodeFilter.SHOW_TEXT);
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
  sasayakiMediaScrollForElement: function(element) {
    var rect = element.getBoundingClientRect();
    if (!rect || rect.width <= 0 || rect.height <= 0) return null;
    return this.sasayakiScrollTargetForRect(rect);
  },
  sasayakiMediaStopsBeforeCue: function(cue) {
    this.selectSasayakiChapter(cue);
    var targetRect = this.sasayakiCueRect(cue);
    if (!targetRect || this.sasayakiCueRectIsVisible(targetRect)) return [];
    var targetScroll = this.sasayakiScrollTargetForRect(targetRect);
    if (targetScroll === null || targetScroll === undefined) return [];
    var visible = this.visibleChapterIndex();
    var target = Number(this.sasayakiRoot.dataset.hoshiChapter);
    var roots = this.chapters.slice(Math.min(visible, target), Math.max(visible, target) + 1);
    return this.sasayakiMediaStopsBetween(this.sasayakiScrollPosition(), targetScroll, true, false, roots);
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
    return this.postScrollPosition(false);
  },
  highlightSasayakiCue: function(cue, reveal) {
    this.selectSasayakiChapter(cue);
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
      if (didScroll) return this.postScrollPosition(false);
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
      return this.postScrollPosition(false);
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
  // Locate the first text run crossing the leading viewport edge. A cached
  // chapter-local index supplies the prefix count; one Range serves every probe.
  calculateProgress: function(chapterIndex = this.visibleChapterIndex()) {
    var root = this.chapterRoot(chapterIndex);
    var index = this.ensureChapterIndex(root);
    var vertical = this.isVertical();
    var viewport = this.contentViewport();
    if (!index.count) {
      var rect = root.getBoundingClientRect();
      var distance = vertical ? rect.right - viewport.right : viewport.top - rect.top;
      return Math.max(0, Math.min(1, distance / (vertical ? rect.width : rect.height) || 0));
    }
    var range = document.createRange();
    var entries = index.entries;
    var low = 0;
    var high = entries.length;
    var candidate = null;
    var counted = 0;
    while (low < high) {
      var mid = (low + high) >>> 1;
      var probe = mid;
      // Hidden publisher nodes have offsets but no visual position.
      while (probe < high) {
        range.selectNodeContents(entries[probe].node);
        var rect = range.getBoundingClientRect();
        if (rect.width > 0 && rect.height > 0) break;
        probe++;
      }
      if (probe === high) {
        high = mid;
        continue;
      }
      var entry = entries[probe];
      var before = this.countCharsBeforeViewport(entry, rect, vertical, range, viewport);
      if (before === entry.length) {
        counted = entry.start + before;
        low = probe + 1;
      } else if (before === 0) {
        candidate = entry;
        high = mid;
      } else {
        return (entry.start + before) / index.count;
      }
    }
    if (candidate) counted = candidate.start;
    return counted / index.count;
  },
  visiblePosition: function() {
    var chapterIndex = this.visibleChapterIndex();
    this.activeChapterIndex = chapterIndex;
    return { chapterIndex, progress: this.calculateProgress(chapterIndex) };
  },
  positionOnExit: function() {
    return this.progressRestored && this.sasayakiScrollPosition() !== this.savedScrollOffset
      ? this.visiblePosition() : null;
  },
  postScrollPosition: function(settled) {
    var position = this.visiblePosition();
    if (this.progressRestored) {
      if (settled) this.savedScrollOffset = this.sasayakiScrollPosition();
      window.HoshiReaderScroll?.postProgress?.(this.restoreToken, position.chapterIndex, position.progress, settled);
    }
    return position.progress;
  },
  cancelScrollReports: function() {
    clearTimeout(this.scrollProgressTimer);
    clearTimeout(this.scrollSettledTimer);
    this.scrollProgressTimer = null;
  },
  scheduleScrollProgress: function() {
    if (!this.progressRestored) return;
    if (this.scrollProgressTimer == null) {
      this.scrollProgressTimer = setTimeout(() => {
        this.scrollProgressTimer = null;
        this.postScrollPosition(false);
      }, 80);
    }
    clearTimeout(this.scrollSettledTimer);
    this.scrollSettledTimer = setTimeout(() => {
      this.cancelScrollReports();
      this.postScrollPosition(true);
    }, 180);
  },
  alignProgress: function(root, progress) {
    if (!(progress > 0)) {
      this.scrollToChapterStart(root);
      return;
    }
    var index = this.ensureChapterIndex(root);
    var vertical = this.isVertical();
    if (!index.count) {
      this.scrollToChapterStart(root);
      var rect = root.getBoundingClientRect();
      // Keep the leading edge inside this section even at its final bookmark.
      var size = vertical ? rect.width : rect.height;
      var distance = Math.min(size * progress, Math.max(0, size - 1));
      this.scrollBy({ left: vertical ? -distance : 0, top: vertical ? 0 : distance, behavior: 'instant' });
      return;
    }
    var target = Math.min(index.count - 1, Math.ceil(index.count * progress));
    var entries = index.entries;
    var low = 0;
    var high = entries.length - 1;
    while (low < high) {
      var mid = (low + high) >>> 1;
      if (entries[mid].start + entries[mid].length <= target) low = mid + 1;
      else high = mid;
    }
    var entry = entries[low];
    var node = entry.node;
    if (progress >= 1 && node.parentElement) {
      node.parentElement.scrollIntoView({ block: 'end', inline: 'nearest', behavior: 'instant' });
      if (this.visibleChapterIndex() !== this.activeChapterIndex) this.scrollToChapterStart(root);
      return;
    }
    var offset = this.textOffsetForCharCount(node, target - entry.start);
    var range = document.createRange();
    range.setStart(node, offset);
    range.setEnd(node, offset + String.fromCodePoint(node.textContent.codePointAt(offset)).length);
    var edge = this.restoreLineEdge(node, this.getRect(range), vertical);
    var viewport = this.contentViewport();
    this.scrollBy({ left: vertical ? edge - viewport.right : 0,
      top: vertical ? 0 : edge - viewport.top, behavior: 'instant' });
  },
  jumpToChapter: async function(chapterIndex, progress, fragment, token = this.restoreToken) {
    var root = this.chapterRoot(chapterIndex);
    if (!root) return false;
    this.restoreToken = token;
    if (window.hoshiSelection) window.hoshiSelection.options.nativeSelectionToken = token;
    this.progressRestored = false;
    this.cancelScrollReports();
    this.activeChapterIndex = chapterIndex;
    var generation = this.restoreGeneration = (this.restoreGeneration || 0) + 1;
    await document.fonts.ready;
    if (generation !== this.restoreGeneration) return false;
    var target = fragment && Array.from(root.querySelectorAll('[data-hoshi-id], [id], [name]'))
      .find(element => element.dataset.hoshiId === fragment || element.id === fragment || element.getAttribute('name') === fragment);
    if (target) target.scrollIntoView({ block: 'start', inline: 'nearest', behavior: 'instant' });
    else this.alignProgress(root, progress);
    window.hoshiReaderMediaSemantics.observeBookImagesAtReadingPosition();
    requestAnimationFrame(() => requestAnimationFrame(() => {
      if (generation === this.restoreGeneration) this.notifyRestoreComplete(token);
    }));
    return !fragment || !!target;
  },
  restoreProgress: function(progress) {
    return this.jumpToChapter(this.activeChapterIndex, progress, null);
  },
  jumpToFragment: function(fragment) {
    return this.jumpToChapter(this.activeChapterIndex, 0, fragment);
  },
  paginateWithProgress: function(direction) {
    var result = this.paginate(direction);
    return result === 'scrolled' ? this.postScrollPosition(false) : result;
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
  var reader = this;
  reader.chapters = Array.from(document.querySelectorAll('body > [data-hoshi-chapter]'));
  document.body.addEventListener('scroll', () => {
    reader.scheduleScrollProgress();
    if (reader.activeCueId && reader.isEInkMode()) reader.renderSasayakiOverlay();
  }, { passive: true });
  window.addEventListener('pagehide', () => {
    reader.cancelScrollReports();
    reader.postScrollPosition(true);
  });
  var selectedFontReady = window.hoshiReaderViewport.preloadBodyFont();
  var imageReferencesReady = window.hoshiReaderMediaSemantics.loadImageReferenceCounts();
  window.hoshiReaderMediaSemantics.setupReaderImages(document, {
    blurImages: __HOSHI_BLUR_IMAGES__,
    liveBlur: true,
    imageBridge: window.HoshiReaderImage,
    waitForImages: false
  });
  window.hoshiReader.normalizeRubyTextNodes();
  window.hoshiReader.stabilizeRubyAdjacentTextNodes();
  __HOSHI_CONFIGURE_CONTINUOUS_VIEWPORT__;
  Promise.resolve(selectedFontReady).then(function() {
    window.hoshiReaderDocumentPreparation?.resume();
    return document.fonts.ready;
  }).then(async function() {
    var installHighlights = __HOSHI_INITIAL_HIGHLIGHTS__;
    if (installHighlights) {
      await imageReferencesReady;
      installHighlights();
    }
    reader.buildNodeOffsets();
    return imageReferencesReady;
  }).then(function() {
    __HOSHI_RESTORE_SCRIPTS__
  });
};
window.addEventListener('load', function() {
  window.hoshiReader.initialize();
});
if (document.readyState === 'complete') {
  window.hoshiReader.initialize();
}
