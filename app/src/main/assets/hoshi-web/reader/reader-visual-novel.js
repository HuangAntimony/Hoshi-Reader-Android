window.hoshiReader = {
  revealSpeed: __HOSHI_VISUAL_NOVEL_REVEAL_SPEED__,
  screenMode: __HOSHI_VISUAL_NOVEL_SCREEN_MODE_LITERAL__,
  sentencesPerScreen: __HOSHI_VISUAL_NOVEL_SENTENCES_PER_SCREEN__,
  preserveDialogue: __HOSHI_VISUAL_NOVEL_PRESERVE_DIALOGUE__,
  mergeCrossScreenSasayakiCues: __HOSHI_VISUAL_NOVEL_MERGE_CROSS_SCREEN_SASAYAKI_CUES__,
  initialSasayakiCues: __HOSHI_INITIAL_SASAYAKI_CUES_JSON__,
  initialProgress: __HOSHI_INITIAL_PROGRESS__,
  initialFragment: __HOSHI_INITIAL_FRAGMENT_LITERAL__,
  initialHighlights: __HOSHI_INITIAL_HIGHLIGHTS_JSON__,
  nativeSelectionActive: false,
  activeCueId: null,
  sasayakiCues: [],
  sasayakiCueMap: new Map(),
  sasayakiCuesSignature: null,
  cueWrappers: new Map(),
  cueSourceRanges: new Map(),
  cueGeometryRanges: new Map(),
  nodeStartOffsets: new WeakMap(),
  nodeStartRawOffsets: new WeakMap(),
  sourceTextOffsets: new WeakMap(),
  sourceTextRawOffsets: new WeakMap(),
  sourceNodeStats: new WeakMap(),
  sourceOrderIndexes: new WeakMap(),
  cloneTextOffsets: new WeakMap(),
  cloneTextRawOffsets: new WeakMap(),
  ttuRegexNegated: /[^0-9A-Za-z○◯々-〇〻ぁ-ゖゝ-ゞァ-ヺー０-９Ａ-Ｚａ-ｚｦ-ﾝ\p{Radical}\p{Unified_Ideograph}]+/gimu,
  ttuRegex: /[0-9A-Za-z○◯々-〇〻ぁ-ゖゝ-ゞァ-ヺー０-９Ａ-Ｚａ-ｚｦ-ﾝ\p{Radical}\p{Unified_Ideograph}]/iu,
  sentenceDelimiters: '。！？.!?',
  totalChapterChars: 0,
  currentScreenIndex: 0,
  revealComplete: true,
  revealTimer: null,
  revealSegments: [],
  revealCursor: 0,
  readyPromise: null,

  isVertical: function() {
    var targets = [
      this.screen,
      document.querySelector('.hoshi-vn-content'),
      this.stage,
      document.body,
      document.documentElement
    ];
    for (var i = 0; i < targets.length; i++) {
      var target = targets[i];
      if (!target) continue;
      var writingMode = window.getComputedStyle(target).writingMode || '';
      if (writingMode.indexOf('vertical') === 0) return true;
    }
    return this.readerCssVariable('--hoshi-reader-vertical-writing') === '1';
  },
  readerCssVariable: function(name) {
    return window.getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  },
  setRevealSpeed: function(speed) {
    var parsed = Number(speed);
    this.revealSpeed = Number.isFinite(parsed) ? Math.min(120, Math.max(0, parsed)) : 0;
    if (!this.revealComplete) {
      this.clearRevealTimer();
      this.scheduleRevealTick();
    }
  },
  isEInkMode: function() {
    return this.readerCssVariable('--hoshi-reader-eink-mode') === '1';
  },
  isFurigana: function(node) {
    var el = node.nodeType === Node.TEXT_NODE ? node.parentElement : node;
    return !!(el && el.closest('rt, rp'));
  },
  isIgnoredElement: function(node) {
    var el = node && node.nodeType === Node.TEXT_NODE ? node.parentElement : node;
    return !!(el && el.closest('rt, rp, script, style'));
  },
  isUnrevealed: function(node) {
    var el = node && node.nodeType === Node.TEXT_NODE ? node.parentElement : node;
    return !!(el && el.closest('[data-hoshi-visual-novel-unrevealed]'));
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
  createWalker: function(rootNode) {
    var root = rootNode || this.screen || document.body;
    return document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode: (n) => {
        if (this.isIgnoredElement(n) || this.isUnrevealed(n)) return NodeFilter.FILTER_REJECT;
        return NodeFilter.FILTER_ACCEPT;
      }
    });
  },
  getRect: function(target) {
    var rect = target.getClientRects()[0];
    return rect || target.getBoundingClientRect();
  },
  notifyRestoreComplete: function() {
    if (window.HoshiReaderRestore && window.HoshiReaderRestore.postMessage) {
      window.HoshiReaderRestore.postMessage(__HOSHI_RESTORE_TOKEN_LITERAL__);
    }
  },
  buildNodeOffsets: function() {
    var offsets = new WeakMap();
    var rawOffsets = new WeakMap();
    var walker = this.createWalker();
    var currentScreen = this.screens && this.screens[this.currentScreenIndex];
    var fallbackCount = currentScreen ? (currentScreen.startCharCount || 0) : 0;
    var fallbackRawCount = currentScreen ? (currentScreen.startRawCount || 0) : 0;
    var node;
    while (node = walker.nextNode()) {
      var mappedCount = this.cloneTextOffsets.get(node);
      var mappedRawCount = this.cloneTextRawOffsets.get(node);
      var startCount = mappedCount !== undefined ? mappedCount : fallbackCount;
      var startRawCount = mappedRawCount !== undefined ? mappedRawCount : fallbackRawCount;
      offsets.set(node, startCount);
      rawOffsets.set(node, startRawCount);
      fallbackCount = startCount + this.countChars(node.textContent);
      fallbackRawCount = startRawCount + this.countRawChars(node.textContent);
    }
    this.nodeStartOffsets = offsets;
    this.nodeStartRawOffsets = rawOffsets;
  },
  waitForImages: function() {
    var images = this.sourceRoot && this.sourceRoot.querySelectorAll
      ? Array.from(this.sourceRoot.querySelectorAll('img'))
      : [];
    var promises = images.map(function(img) {
      return new Promise(function(resolve) {
        if (img.complete) {
          resolve();
          return;
        }
        img.onload = function() { resolve(); };
        img.onerror = function() { resolve(); };
      });
    });
    return Promise.all(promises);
  },
  initialize: function() {
    if (this.readyPromise) return this.readyPromise;
    this.readyPromise = Promise.resolve(document.fonts && document.fonts.ready)
      .then(() => {
        this.detachChapterSource();
        return this.waitForImages();
      })
      .then(() => {
        this.ensureStage();
        this.buildSourceIndexes();
        this.setSasayakiCueData(this.initialSasayakiCues);
        this.buildScreens();
        this.renderInitialScreen();
        this.notifyRestoreComplete();
      });
    return this.readyPromise;
  },
  ensureReady: function() {
    return this.readyPromise || this.initialize();
  },
  detachChapterSource: function() {
    if (this.sourceRoot) return;
    this.sourceRoot = document.createElement('div');
    var children = Array.from(document.body.childNodes);
    for (var i = 0; i < children.length; i++) {
      this.sourceRoot.appendChild(children[i]);
    }
    if (document.body.replaceChildren) {
      document.body.replaceChildren();
    } else {
      while (document.body.firstChild) document.body.removeChild(document.body.firstChild);
    }
  },
  ensureStage: function() {
    if (this.stage && this.screen) return;
    this.stage = document.createElement('div');
    this.stage.className = 'hoshi-vn-stage';
    this.screen = document.createElement('div');
    this.screen.className = 'hoshi-vn-screen';
    this.stage.appendChild(this.screen);
    document.body.appendChild(this.stage);
  },
  buildSourceIndexes: function() {
    this.sourceTextOffsets = new WeakMap();
    this.sourceTextRawOffsets = new WeakMap();
    this.sourceNodeStats = new WeakMap();
    this.sourceOrderIndexes = new WeakMap();
    this.sourceEntries = [];
    var topLevelNodes = Array.from(this.sourceRoot.childNodes || []);
    for (var order = 0; order < topLevelNodes.length; order++) {
      this.sourceOrderIndexes.set(topLevelNodes[order], order);
    }
    var walker = this.createWalker(this.sourceRoot);
    var count = 0;
    var rawCount = 0;
    var node;
    while (node = walker.nextNode()) {
      this.sourceTextOffsets.set(node, count);
      this.sourceTextRawOffsets.set(node, rawCount);
      var entry = {
        node: node,
        startChar: count,
        startRaw: rawCount,
        text: node.textContent || ''
      };
      count += this.countChars(entry.text);
      rawCount += this.countRawChars(entry.text);
      entry.endChar = count;
      entry.endRaw = rawCount;
      this.sourceEntries.push(entry);
      this.updateSourceNodeStats(node, entry);
    }
    this.totalChapterChars = count;
  },
  updateSourceNodeStats: function(node, entry) {
    var current = node;
    while (current && current !== this.sourceRoot) {
      var stats = this.sourceNodeStats.get(current);
      if (!stats) {
        this.sourceNodeStats.set(current, {
          hasText: true,
          startChar: entry.startChar,
          endChar: entry.endChar,
          startRaw: entry.startRaw,
          endRaw: entry.endRaw
        });
      } else {
        stats.startChar = Math.min(stats.startChar, entry.startChar);
        stats.endChar = Math.max(stats.endChar, entry.endChar);
        stats.startRaw = Math.min(stats.startRaw, entry.startRaw);
        stats.endRaw = Math.max(stats.endRaw, entry.endRaw);
      }
      current = current.parentNode;
    }
  },
  buildScreens: function() {
    var mode = String(this.screenMode || '').toLowerCase();
    var baseScreens;
    if (mode === 'sentence' || mode === 'sentences') {
      baseScreens = this.buildSentenceScreens();
    } else {
      baseScreens = this.buildBlockScreens();
    }
    this.baseScreens = baseScreens;
    this.screens = this.mergeSasayakiCrossScreenScreens(baseScreens);
    if (!this.screens.length) {
      this.screens.push({
        startCharCount: 0,
        endCharCount: 0,
        startRawCount: 0,
        endRawCount: 0,
        ids: new Set(),
        splittable: false,
        render: () => document.createDocumentFragment()
      });
    }
    this.screens = this.fitScreensToViewport(this.screens);
  },
  mergeSasayakiCrossScreenScreens: function(screens) {
    if (!this.mergeCrossScreenSasayakiCues || !Array.isArray(screens) || screens.length < 2) return screens || [];
    if (!Array.isArray(this.sasayakiCues) || !this.sasayakiCues.length) return screens;
    var cues = [];
    for (var cueIndex = 0; cueIndex < this.sasayakiCues.length; cueIndex++) {
      var cue = this.sasayakiCueForInput(this.sasayakiCues[cueIndex]);
      if (cue) cues.push(cue);
    }
    if (!cues.length) return screens;
    cues.sort((a, b) => {
      var startDelta = this.sasayakiCueStart(a) - this.sasayakiCueStart(b);
      if (startDelta !== 0) return startDelta;
      return this.sasayakiCueEnd(a) - this.sasayakiCueEnd(b);
    });
    var intervals = [];
    var searchStart = 0;
    for (var i = 0; i < cues.length; i++) {
      var cue = cues[i];
      var first = -1;
      var last = -1;
      var cueStart = this.sasayakiCueStart(cue);
      var cueEnd = this.sasayakiCueEnd(cue);
      var zeroLengthCue = cueEnd <= cueStart;
      while (searchStart < screens.length) {
        var screenEnd = Number(screens[searchStart].endCharCount) || 0;
        if (zeroLengthCue) {
          if (cueStart <= screenEnd) break;
        } else if (cueEnd > (Number(screens[searchStart].startCharCount) || 0)) {
          if (cueStart < screenEnd) break;
        }
        searchStart += 1;
      }
      for (var screenIndex = searchStart; screenIndex < screens.length; screenIndex++) {
        var screen = screens[screenIndex];
        var screenStart = Number(screen.startCharCount) || 0;
        var screenEnd = Number(screen.endCharCount) || 0;
        if (!this.sasayakiCueIntersectsScreen(cue, screen)) {
          if (zeroLengthCue ? cueStart < screenStart : cueEnd <= screenStart) break;
          continue;
        }
        if (first < 0) first = screenIndex;
        last = screenIndex;
        if (zeroLengthCue) {
          if (cueStart < screenEnd) break;
        } else if (cueEnd <= screenEnd) {
          break;
        }
      }
      if (first < 0 || last <= first) continue;
      var canMerge = true;
      for (var mergeIndex = first; mergeIndex <= last; mergeIndex++) {
        if (!screens[mergeIndex].splittable) {
          canMerge = false;
          break;
        }
      }
      if (canMerge) intervals.push({ start: first, end: last });
    }
    if (!intervals.length) return screens;
    intervals.sort(function(a, b) {
      if (a.start !== b.start) return a.start - b.start;
      return a.end - b.end;
    });
    var mergedIntervals = [];
    for (var intervalIndex = 0; intervalIndex < intervals.length; intervalIndex++) {
      var interval = intervals[intervalIndex];
      var current = mergedIntervals[mergedIntervals.length - 1];
      if (current && interval.start <= current.end) {
        current.end = Math.max(current.end, interval.end);
      } else {
        mergedIntervals.push({ start: interval.start, end: interval.end });
      }
    }
    var result = [];
    var cursor = 0;
    for (var mergedIndex = 0; mergedIndex < mergedIntervals.length; mergedIndex++) {
      var merged = mergedIntervals[mergedIndex];
      while (cursor < merged.start) {
        result.push(screens[cursor]);
        cursor += 1;
      }
      result.push(this.mergeScreenRange(screens, merged.start, merged.end));
      cursor = merged.end + 1;
    }
    while (cursor < screens.length) {
      result.push(screens[cursor]);
      cursor += 1;
    }
    return result;
  },
  mergeScreenRange: function(screens, start, end) {
    var parts = screens.slice(start, end + 1);
    var ids = new Set();
    parts.forEach(function(screen) {
      (screen.ids || new Set()).forEach(function(id) { ids.add(id); });
    });
    var first = parts[0];
    var last = parts[parts.length - 1];
    return {
      startCharCount: first.startCharCount,
      endCharCount: parts.reduce(function(max, screen) {
        return Math.max(max, screen.endCharCount);
      }, first.endCharCount),
      startRawCount: first.startRawCount,
      endRawCount: parts.reduce(function(max, screen) {
        return Math.max(max, screen.endRawCount);
      }, last.endRawCount),
      ids: ids,
      splittable: true,
      mediaStop: parts.some(function(screen) { return !!screen.mediaStop; }),
      render: () => {
        var fragment = document.createDocumentFragment();
        parts.forEach(function(screen) {
          fragment.appendChild(screen.render());
        });
        return fragment;
      }
    };
  },
  fitScreensToViewport: function(screens) {
    if (!screens || !screens.length || !this.stage || !this.screen) return screens || [];
    var measurement = this.createScreenMeasurement();
    if (!measurement) return screens;
    var fitted = [];
    try {
      for (var i = 0; i < screens.length; i++) {
        var screen = screens[i];
        if (!screen.splittable || this.measureScreenFits(screen, measurement)) {
          fitted.push(screen);
          continue;
        }
        var splitScreens = this.splitScreenToViewport(screen, measurement);
        if (splitScreens.length) {
          fitted = fitted.concat(splitScreens);
        } else {
          fitted.push(screen);
        }
      }
    } finally {
      if (measurement.root && measurement.root.parentNode) {
        measurement.root.parentNode.removeChild(measurement.root);
      }
    }
    return fitted.length ? fitted : screens;
  },
  createScreenMeasurement: function() {
    if (!this.stage || !this.screen || !document.createRange) return null;
    var root = document.createElement('div');
    root.className = 'hoshi-vn-screen';
    root.setAttribute('aria-hidden', 'true');
    root.style.position = 'fixed';
    root.style.left = '0';
    root.style.top = '0';
    root.style.zIndex = '-1';
    root.style.opacity = '0';
    root.style.pointerEvents = 'none';
    root.style.width = 'var(--page-width, 100vw)';
    root.style.height = 'var(--page-height, 100vh)';
    var content = document.createElement('div');
    content.className = 'hoshi-vn-content';
    root.appendChild(content);
    this.stage.appendChild(root);
    return { root: root, content: content };
  },
  measureScreenFits: function(screen, measurement) {
    if (!screen || !measurement || !measurement.content) return true;
    if (measurement.content.replaceChildren) {
      measurement.content.replaceChildren();
    } else {
      while (measurement.content.firstChild) measurement.content.removeChild(measurement.content.firstChild);
    }
    measurement.content.appendChild(screen.render());
    if (this.measurementScrollFits(measurement)) return true;
    var bounds = this.measurementBounds(measurement);
    if (!bounds) return true;
    return this.renderedTextFitsBounds(measurement.content, bounds);
  },
  measurementScrollFits: function(measurement) {
    var root = measurement && measurement.root;
    var content = measurement && measurement.content;
    if (!root || !content) return false;
    var width = Number(root.clientWidth) || Number(root.offsetWidth) || 0;
    var height = Number(root.clientHeight) || Number(root.offsetHeight) || 0;
    var scrollWidth = Number(content.scrollWidth) || 0;
    var scrollHeight = Number(content.scrollHeight) || 0;
    if (!width || !height || !scrollWidth || !scrollHeight) return false;
    var tolerance = 1;
    return scrollWidth <= width + tolerance && scrollHeight <= height + tolerance;
  },
  measurementBounds: function(measurement) {
    var content = measurement && measurement.content;
    if (!content || !content.getBoundingClientRect) return null;
    var bounds = content.getBoundingClientRect();
    if (!bounds || (!bounds.width && !bounds.height)) {
      bounds = {
        left: 0,
        top: 0,
        right: window.innerWidth || 0,
        bottom: window.innerHeight || 0,
        width: window.innerWidth || 0,
        height: window.innerHeight || 0
      };
    }
    if (!bounds.width && !bounds.height) return null;
    if (measurement.root && measurement.root.getBoundingClientRect) {
      var rootBounds = measurement.root.getBoundingClientRect();
      if (rootBounds && (rootBounds.width || rootBounds.height)) {
        if (this.isVertical()) {
          bounds = {
            left: bounds.left,
            right: bounds.right,
            top: rootBounds.top,
            bottom: rootBounds.bottom,
            width: bounds.width,
            height: rootBounds.bottom - rootBounds.top
          };
        } else {
          bounds = {
            left: rootBounds.left,
            right: rootBounds.right,
            top: bounds.top,
            bottom: bounds.bottom,
            width: rootBounds.right - rootBounds.left,
            height: bounds.height
          };
        }
      }
    }
    return bounds;
  },
  renderedTextFitsBounds: function(root, bounds) {
    var walker = this.createWalker(root);
    var range = document.createRange();
    var tolerance = 1;
    var node;
    while (node = walker.nextNode()) {
      if (!(node.textContent || '').trim()) continue;
      range.selectNodeContents(node);
      var rects = Array.from(range.getClientRects ? range.getClientRects() : []);
      for (var i = 0; i < rects.length; i++) {
        var rect = rects[i];
        if (!rect || (!rect.width && !rect.height)) continue;
        if (!this.rectFitsBounds(rect, bounds, tolerance)) {
          if (range.detach) range.detach();
          return false;
        }
      }
    }
    if (range.detach) range.detach();
    return true;
  },
  rectFitsBounds: function(rect, bounds, tolerance) {
    return rect.left >= bounds.left - tolerance &&
      rect.right <= bounds.right + tolerance &&
      rect.top >= bounds.top - tolerance &&
      rect.bottom <= bounds.bottom + tolerance;
  },
  splitScreenToViewport: function(screen, measurement) {
    var items = this.textItemsForScreen(screen);
    if (!items.length) return [];
    var result = [];
    var start = 0;
    while (start < items.length) {
      var low = start + 1;
      var high = items.length;
      var best = -1;
      while (low <= high) {
        var mid = Math.floor((low + high) / 2);
        var candidate = this.screenFromTextItems(items, start, mid, screen.ids);
        if (this.measureScreenFits(candidate, measurement)) {
          best = mid;
          low = mid + 1;
        } else {
          high = mid - 1;
        }
      }
      if (best <= start) best = start + 1;
      result.push(this.screenFromTextItems(items, start, best, screen.ids));
      start = best;
    }
    return result;
  },
  textItemsForScreen: function(screen) {
    if (!this.viewportFitTextItems) this.viewportFitTextItems = this.buildTextItems();
    var startRaw = Number(screen.startRawCount) || 0;
    var endRaw = Number(screen.endRawCount) || startRaw;
    var items = this.viewportFitTextItems;
    var startIndex = this.lowerBoundTextItemsByRawStart(items, startRaw);
    var result = [];
    for (var i = startIndex; i < items.length; i++) {
      var item = items[i];
      if (item.chapterRawStart >= endRaw) break;
      if (item.chapterRawEnd <= endRaw) result.push(item);
    }
    return result;
  },
  lowerBoundTextItemsByRawStart: function(items, targetRaw) {
    var low = 0;
    var high = items.length;
    while (low < high) {
      var mid = Math.floor((low + high) / 2);
      if (items[mid].chapterRawStart < targetRaw) {
        low = mid + 1;
      } else {
        high = mid;
      }
    }
    return low;
  },
  screenFromTextItems: function(items, start, end, baseIds) {
    var slice = items.slice(start, end);
    var ranges = this.rangesFromItems(slice);
    var ids = new Set(baseIds || []);
    ranges.forEach((range) => {
      this.collectIdsForTextNode(range.node).forEach((id) => ids.add(id));
    });
    var first = slice[0];
    return {
      startCharCount: first.chapterCharStart,
      endCharCount: slice.reduce(function(max, item) {
        return Math.max(max, item.chapterCharEnd);
      }, first.chapterCharStart),
      startRawCount: first.chapterRawStart,
      endRawCount: slice.reduce(function(max, item) {
        return Math.max(max, item.chapterRawEnd);
      }, first.chapterRawStart),
      ids: ids,
      splittable: false,
      mediaStop: false,
      render: () => this.cloneRangesWithOffsets(ranges)
    };
  },
  buildBlockScreens: function() {
    var screens = [];
    var runningEnd = 0;
    var runningRawEnd = 0;
    var sources = this.collectBlockScreenSources(this.sourceRoot);
    for (var i = 0; i < sources.length; i++) {
      let child = sources[i].node;
      var stats = this.statsForSourceNode(child);
      var hasStandaloneMedia = this.containsStandaloneMedia(child);
      var start = stats.hasText ? stats.startChar : runningEnd;
      var end = stats.hasText ? stats.endChar : start;
      var rawStart = stats.hasText ? stats.startRaw : runningRawEnd;
      var rawEnd = stats.hasText ? stats.endRaw : rawStart;
      runningEnd = end;
      runningRawEnd = rawEnd;
      screens.push({
        startCharCount: start,
        endCharCount: end,
        startRawCount: rawStart,
        endRawCount: rawEnd,
        ids: this.collectIdsForNode(child, sources[i].extraIds),
        splittable: stats.hasText && !hasStandaloneMedia,
        mediaStop: hasStandaloneMedia,
        render: () => {
          var fragment = document.createDocumentFragment();
          fragment.appendChild(this.cloneSourceNodeWithOffsets(child));
          return fragment;
        }
      });
    }
    return screens;
  },
  collectBlockScreenSources: function(root, inheritedIds) {
    var sources = [];
    var pendingIds = new Set(inheritedIds || []);
    var children = Array.from(root.childNodes || []);
    for (var i = 0; i < children.length; i++) {
      var child = children[i];
      if (!this.isRenderableBlockSource(child)) continue;
      if (child.nodeType === Node.ELEMENT_NODE && this.isSplittableBlockContainer(child)) {
        var nestedIds = this.mergeIds(pendingIds, this.ownIdsForNode(child));
        var nested = this.collectBlockScreenSources(child, nestedIds);
        if (nested.length) {
          sources = sources.concat(nested);
          pendingIds = new Set();
          continue;
        }
      }
      sources.push({
        node: child,
        extraIds: new Set(pendingIds)
      });
      pendingIds = new Set();
    }
    return sources;
  },
  isRenderableBlockSource: function(node) {
    if (!node) return false;
    if (node.nodeType === Node.TEXT_NODE) return !!(node.textContent || '').trim();
    if (node.nodeType !== Node.ELEMENT_NODE) return false;
    if (this.isIgnoredElement(node)) return false;
    return !!((node.textContent || '').trim() || this.containsStandaloneMedia(node));
  },
  isSplittableBlockContainer: function(node) {
    if (!node || node.nodeType !== Node.ELEMENT_NODE) return false;
    var tag = String(node.tagName || '').toLowerCase();
    if (['body', 'section', 'article', 'main', 'div'].indexOf(tag) < 0) return false;
    var candidates = this.renderableBlockChildren(node);
    if (candidates.length > 1) return true;
    return candidates.length === 1
      && candidates[0].nodeType === Node.ELEMENT_NODE
      && this.isSplittableBlockContainer(candidates[0]);
  },
  renderableBlockChildren: function(node) {
    var children = Array.from(node.childNodes || []);
    var result = [];
    for (var i = 0; i < children.length; i++) {
      var child = children[i];
      if (!this.isRenderableBlockSource(child)) continue;
      if (child.nodeType === Node.TEXT_NODE) {
        result.push(child);
        continue;
      }
      var tag = String(child.tagName || '').toLowerCase();
      if (this.isBlockScreenElement(tag) || this.isSplittableBlockContainer(child)) {
        result.push(child);
      }
    }
    return result;
  },
  isBlockScreenElement: function(tag) {
    return [
      'address',
      'aside',
      'blockquote',
      'canvas',
      'details',
      'dialog',
      'dl',
      'fieldset',
      'figcaption',
      'figure',
      'footer',
      'form',
      'h1',
      'h2',
      'h3',
      'h4',
      'h5',
      'h6',
      'header',
      'hr',
      'iframe',
      'img',
      'li',
      'object',
      'ol',
      'p',
      'picture',
      'pre',
      'section',
      'svg',
      'table',
      'ul',
      'video'
    ].indexOf(tag) >= 0;
  },
  mergeIds: function(first, second) {
    var merged = new Set();
    (first || new Set()).forEach(function(id) { merged.add(id); });
    (second || new Set()).forEach(function(id) { merged.add(id); });
    return merged;
  },
  ownIdsForNode: function(node) {
    var ids = new Set();
    if (node && node.nodeType === Node.ELEMENT_NODE) {
      var id = node.getAttribute && node.getAttribute('id');
      if (id) ids.add(id);
      var name = node.getAttribute && node.getAttribute('name');
      if (name) ids.add(name);
    }
    return ids;
  },
  buildSentenceScreens: function() {
    this.sentenceAtomicRoots = this.buildSentenceAtomicRoots();
    var units = this.buildSentenceUnits()
      .concat(this.buildStandaloneSentenceUnits())
      .sort(function(a, b) {
        if (a.order !== b.order) return a.order - b.order;
        return a.startRawCount - b.startRawCount;
      });
    var groupSize = this.clampSentenceCount(this.sentencesPerScreen);
    var screens = [];
    var pendingTextUnits = [];
    var flushTextUnits = () => {
      while (pendingTextUnits.length) {
        screens.push(this.screenFromSentenceUnits(pendingTextUnits.splice(0, groupSize)));
      }
    };
    for (var i = 0; i < units.length; i++) {
      var unit = units[i];
      if (unit.standalone) {
        flushTextUnits();
        screens.push(unit);
      } else {
        pendingTextUnits.push(unit);
        if (pendingTextUnits.length >= groupSize) flushTextUnits();
      }
    }
    flushTextUnits();
    return screens;
  },
  screenFromSentenceUnits: function(groupedUnits) {
    let ranges = [];
    let ids = new Set();
    groupedUnits.forEach((unit) => {
      ranges = ranges.concat(unit.ranges);
      unit.ids.forEach((id) => ids.add(id));
    });
    let firstUnit = groupedUnits[0];
    let lastUnit = groupedUnits[groupedUnits.length - 1];
    return {
      startCharCount: firstUnit.startCharCount,
      endCharCount: lastUnit.endCharCount,
      startRawCount: firstUnit.startRawCount,
      endRawCount: lastUnit.endRawCount,
      ids: ids,
      splittable: true,
      mediaStop: groupedUnits.some(function(unit) { return !!unit.mediaStop; }),
      render: () => this.cloneRangesWithOffsets(ranges)
    };
  },
  buildSentenceAtomicRoots: function() {
    var roots = new WeakSet();
    var children = Array.from(this.sourceRoot.childNodes);
    for (var i = 0; i < children.length; i++) {
      var child = children[i];
      if (child.nodeType === Node.TEXT_NODE && !(child.textContent || '').trim()) continue;
      if (child.nodeType === Node.ELEMENT_NODE && this.isIgnoredElement(child)) continue;
      if (child.nodeType === Node.ELEMENT_NODE && this.containsStandaloneMedia(child)) roots.add(child);
    }
    return roots;
  },
  buildStandaloneSentenceUnits: function() {
    var units = [];
    var children = Array.from(this.sourceRoot.childNodes);
    for (var i = 0; i < children.length; i++) {
      let child = children[i];
      if (child.nodeType === Node.TEXT_NODE && !(child.textContent || '').trim()) continue;
      if (child.nodeType === Node.ELEMENT_NODE && this.isIgnoredElement(child)) continue;
      var stats = this.statsForSourceNode(child);
      var atomic = this.sentenceAtomicRoots && this.sentenceAtomicRoots.has(child);
      if (!atomic && stats.hasText) continue;
      var position = stats.hasText ? stats : this.sourcePositionForTopLevelNode(i);
      var hasStandaloneMedia = child.nodeType === Node.ELEMENT_NODE && this.containsStandaloneMedia(child);
      units.push({
        standalone: true,
        order: i,
        startCharCount: position.startChar,
        endCharCount: position.endChar,
        startRawCount: position.startRaw,
        endRawCount: position.endRaw,
        ids: this.collectIdsForNode(child),
        splittable: false,
        mediaStop: hasStandaloneMedia,
        render: () => {
          var fragment = document.createDocumentFragment();
          fragment.appendChild(this.cloneSourceNodeWithOffsets(child));
          return fragment;
        }
      });
    }
    return units;
  },
  sourcePositionForTopLevelNode: function(order) {
    var previous = null;
    for (var i = 0; i < this.sourceEntries.length; i++) {
      var entry = this.sourceEntries[i];
      var entryOrder = this.sourceOrderForTextNode(entry.node);
      if (entryOrder > order) {
        return {
          hasText: false,
          startChar: entry.startChar,
          endChar: entry.startChar,
          startRaw: entry.startRaw,
          endRaw: entry.startRaw
        };
      }
      if (entryOrder < order) previous = entry;
    }
    var char = previous ? previous.endChar : 0;
    var raw = previous ? previous.endRaw : 0;
    return { hasText: false, startChar: char, endChar: char, startRaw: raw, endRaw: raw };
  },
  containsStandaloneMedia: function(root) {
    if (!root || root.nodeType !== Node.ELEMENT_NODE) return false;
    var tag = String(root.tagName || '').toLowerCase();
    if (this.isStandaloneMediaTag(tag)) return true;
    return !!(root.querySelector && root.querySelector('img, svg, image, video, canvas, audio, picture, table, iframe, object, embed'));
  },
  isStandaloneMediaTag: function(tag) {
    return [
      'img',
      'svg',
      'image',
      'video',
      'canvas',
      'audio',
      'picture',
      'table',
      'iframe',
      'object',
      'embed'
    ].indexOf(tag) >= 0;
  },
  buildSentenceUnits: function() {
    var items = this.buildTextItems();
    if (!items.length) return [];
    var units = [];
    var unitStart = 0;
    var dialogueDepth = 0;
    var segmenterBoundaries = this.sentenceSegmenterBoundaryIndexes(items);
    for (var i = 0; i < items.length; i++) {
      var char = items[i].char;
      var split = false;
      if (this.preserveDialogue && (char === '「' || char === '『')) {
        dialogueDepth += 1;
      } else if (this.preserveDialogue && (char === '」' || char === '』')) {
        if (dialogueDepth > 0) dialogueDepth -= 1;
        if (dialogueDepth === 0) split = true;
      } else if (this.sentenceDelimiters.indexOf(char) >= 0 && (!this.preserveDialogue || dialogueDepth === 0)) {
        split = true;
      } else if (!this.preserveDialogue && segmenterBoundaries && segmenterBoundaries.has(i + 1)) {
        split = true;
      }
      if (split) {
        this.pushSentenceUnit(units, items, unitStart, i + 1);
        unitStart = i + 1;
      }
    }
    this.pushSentenceUnit(units, items, unitStart, items.length);
    return units;
  },
  sentenceSegmenterBoundaryIndexes: function(items) {
    if (this.preserveDialogue || typeof Intl === 'undefined' || typeof Intl.Segmenter !== 'function') return null;
    try {
      var text = items.map(function(item) { return item.char; }).join('');
      var segmenter = new Intl.Segmenter(undefined, { granularity: 'sentence' });
      var boundaries = new Set();
      var cursor = 0;
      for (var segment of segmenter.segment(text)) {
        cursor += Array.from(segment.segment || '').length;
        if (cursor > 0) boundaries.add(cursor);
      }
      return boundaries.size ? boundaries : null;
    } catch (_error) {
      return null;
    }
  },
  pushSentenceUnit: function(units, items, start, end) {
    if (start >= end) return;
    var slice = items.slice(start, end);
    var text = slice.map(function(item) { return item.char; }).join('');
    if (!text.trim()) return;
    var ranges = this.rangesFromItems(slice);
    var ids = new Set();
    ranges.forEach((range) => {
      this.collectIdsForTextNode(range.node).forEach((id) => ids.add(id));
    });
    units.push({
      standalone: false,
      ranges: ranges,
      ids: ids,
      order: ranges.reduce(function(min, range) {
        return Math.min(min, range.order);
      }, Number.POSITIVE_INFINITY),
      startCharCount: slice[0].chapterCharStart,
      endCharCount: slice.reduce(function(max, item) {
        return Math.max(max, item.chapterCharEnd);
      }, slice[0].chapterCharStart),
      startRawCount: slice[0].chapterRawStart,
      endRawCount: slice.reduce(function(max, item) {
        return Math.max(max, item.chapterRawEnd);
      }, slice[0].chapterRawStart)
    });
  },
  buildTextItems: function() {
    var items = [];
    for (var e = 0; e < this.sourceEntries.length; e++) {
      var entry = this.sourceEntries[e];
      if (this.isSentenceAtomicTextNode(entry.node)) continue;
      var text = entry.text;
      var offset = 0;
      var rawOffset = 0;
      var matchableOffset = 0;
      while (offset < text.length) {
        var char = String.fromCodePoint(text.codePointAt(offset));
        var next = offset + char.length;
        var isMatchable = this.isMatchableChar(char);
        items.push({
          node: entry.node,
          order: this.sourceOrderForTextNode(entry.node),
          char: char,
          start: offset,
          end: next,
          chapterRawStart: entry.startRaw + rawOffset,
          chapterRawEnd: entry.startRaw + rawOffset + 1,
          chapterCharStart: entry.startChar + matchableOffset,
          chapterCharEnd: entry.startChar + matchableOffset + (isMatchable ? 1 : 0)
        });
        if (isMatchable) matchableOffset += 1;
        rawOffset += 1;
        offset = next;
      }
    }
    return items;
  },
  sourceOrderForTextNode: function(node) {
    var root = this.topLevelSourceNode(node);
    var order = this.sourceOrderIndexes && this.sourceOrderIndexes.get(root);
    if (order !== undefined) return order;
    return Array.from(this.sourceRoot.childNodes).indexOf(root);
  },
  topLevelSourceNode: function(node) {
    var current = node;
    while (current && current.parentNode && current.parentNode !== this.sourceRoot) {
      current = current.parentNode;
    }
    return current;
  },
  isSentenceAtomicTextNode: function(node) {
    if (!this.sentenceAtomicRoots) return false;
    var root = this.topLevelSourceNode(node);
    return !!(root && this.sentenceAtomicRoots.has(root));
  },
  rangesFromItems: function(items) {
    var ranges = [];
    for (var i = 0; i < items.length; i++) {
      var item = items[i];
      var last = ranges[ranges.length - 1];
      if (last && last.node === item.node && last.end === item.start) {
        last.end = item.end;
        last.endCharCount = Math.max(last.endCharCount, item.chapterCharEnd);
        last.chapterRawEnd = Math.max(last.chapterRawEnd, item.chapterRawEnd);
      } else {
        ranges.push({
          node: item.node,
          order: item.order,
          start: item.start,
          end: item.end,
          chapterCharStart: item.chapterCharStart,
          chapterRawStart: item.chapterRawStart,
          chapterRawEnd: item.chapterRawEnd,
          endCharCount: item.chapterCharEnd
        });
      }
    }
    return ranges;
  },
  clampSentenceCount: function(value) {
    var parsed = Number(value);
    if (!Number.isFinite(parsed)) return 1;
    return Math.min(12, Math.max(1, Math.floor(parsed)));
  },
  statsForSourceNode: function(root) {
    return this.sourceNodeStats.get(root) || { hasText: false, startChar: 0, endChar: 0, startRaw: 0, endRaw: 0 };
  },
  isDescendantOf: function(node, root) {
    var current = node;
    while (current) {
      if (current === root) return true;
      current = current.parentNode;
    }
    return false;
  },
  collectIdsForNode: function(root, extraIds) {
    var ids = new Set(extraIds || []);
    var visit = function(node) {
      if (node.nodeType === Node.ELEMENT_NODE) {
        var id = node.getAttribute && node.getAttribute('id');
        if (id) ids.add(id);
        var name = node.getAttribute && node.getAttribute('name');
        if (name) ids.add(name);
      }
      var children = node.childNodes || [];
      for (var i = 0; i < children.length; i++) visit(children[i]);
    };
    visit(root);
    return ids;
  },
  collectIdsForTextNode: function(node) {
    var ids = new Set();
    var current = node.parentNode;
    while (current && current !== this.sourceRoot) {
      if (current.nodeType === Node.ELEMENT_NODE) {
        var id = current.getAttribute && current.getAttribute('id');
        if (id) ids.add(id);
        var name = current.getAttribute && current.getAttribute('name');
        if (name) ids.add(name);
      }
      current = current.parentNode;
    }
    return ids;
  },
  cloneSourceNodeWithOffsets: function(sourceNode) {
    if (sourceNode.nodeType === Node.TEXT_NODE) {
      var cloneText = document.createTextNode(sourceNode.textContent || '');
      this.registerCloneTextOffset(cloneText, this.sourceTextOffsets.get(sourceNode), this.sourceTextRawOffsets.get(sourceNode));
      return cloneText;
    }
    if (sourceNode.nodeType !== Node.ELEMENT_NODE && sourceNode.nodeType !== Node.DOCUMENT_FRAGMENT_NODE) {
      return sourceNode.cloneNode ? sourceNode.cloneNode(true) : document.createTextNode('');
    }
    if (sourceNode.nodeType === Node.ELEMENT_NODE && this.isIgnoredElement(sourceNode)) {
      return document.createDocumentFragment();
    }
    var clone = sourceNode.cloneNode ? sourceNode.cloneNode(false) : document.createElement(sourceNode.tagName.toLowerCase());
    var children = Array.from(sourceNode.childNodes || []);
    for (var i = 0; i < children.length; i++) {
      clone.appendChild(this.cloneSourceNodeWithOffsets(children[i]));
    }
    return clone;
  },
  cloneRangesWithOffsets: function(ranges) {
    var fragment = document.createDocumentFragment();
    var cloneMap = new WeakMap();
    var ensureElementClone = (sourceElement) => {
      if (cloneMap.has(sourceElement)) return cloneMap.get(sourceElement);
      var clone = sourceElement.cloneNode ? sourceElement.cloneNode(false) : document.createElement(sourceElement.tagName.toLowerCase());
      cloneMap.set(sourceElement, clone);
      var parent = sourceElement.parentNode;
      if (!parent || parent === this.sourceRoot) {
        fragment.appendChild(clone);
      } else {
        ensureElementClone(parent).appendChild(clone);
      }
      return clone;
    };
    for (var i = 0; i < ranges.length; i++) {
      var range = ranges[i];
      var text = (range.node.textContent || '').slice(range.start, range.end);
      if (!text) continue;
      var cloneText = document.createTextNode(text);
      this.registerCloneTextOffset(cloneText, range.chapterCharStart, range.chapterRawStart);
      var parent = range.node.parentNode;
      if (!parent || parent === this.sourceRoot) {
        fragment.appendChild(cloneText);
      } else {
        ensureElementClone(parent).appendChild(cloneText);
      }
    }
    return fragment;
  },
  registerCloneTextOffset: function(node, charOffset, rawOffset) {
    this.cloneTextOffsets.set(node, charOffset === undefined ? 0 : charOffset);
    this.cloneTextRawOffsets.set(node, rawOffset === undefined ? 0 : rawOffset);
  },
  setupReaderImage: function(element, src, wrap, blurElement) {
    if (!element || !src || element.hoshiReaderImageSetup) return;
    element.hoshiReaderImageSetup = true;
    blurElement = blurElement || element;
    if (__HOSHI_BLUR_IMAGES__) {
      blurElement.classList.add('blurred');
      if (wrap && !(blurElement.parentElement && blurElement.parentElement.classList.contains('blur-wrapper'))) {
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
        window.HoshiReaderImage.postMessage(new URL(src, document.baseURI).href);
      }
    });
  },
  setupReaderImages: function(root) {
    var scope = root || this.screen;
    if (!scope || !scope.querySelectorAll) return;
    var svgImages = Array.from(scope.querySelectorAll('svg image'));
    svgImages.forEach((svgImage) => {
      var svg = svgImage.closest('svg');
      if (!svg) return;
      if (svg.getAttribute('preserveAspectRatio') === 'none') {
        svg.setAttribute('preserveAspectRatio', 'xMidYMid meet');
      }
      var svgImageSrc = svgImage.href && svgImage.href.baseVal
        ? svgImage.href.baseVal
        : (svgImage.getAttribute('href') || svgImage.getAttribute('xlink:href'));
      this.setupReaderImage(svgImage, svgImageSrc, false, svg);
    });
    var images = Array.from(scope.querySelectorAll('img'));
    images.forEach((img) => {
      var isGaiji = img.classList.contains('gaiji') || img.classList.contains('gaiji-line');
      var mark = () => {
        if (!isGaiji && (img.naturalWidth > 256 || img.naturalHeight > 256)) {
          img.classList.add('block-img');
          this.setupReaderImage(img, img.currentSrc || img.src || img.getAttribute('src'), true);
        }
      };
      if (img.complete) {
        if ((img.naturalWidth || 0) > 0) mark();
      } else {
        img.onload = mark;
      }
    });
  },
  renderInitialScreen: function() {
    var index = 0;
    if (this.initialFragment) {
      var fragmentIndex = this.screenIndexForFragment(this.initialFragment);
      if (fragmentIndex >= 0) index = fragmentIndex;
    } else if (this.initialProgress > 0) {
      index = this.screenIndexForProgress(this.initialProgress);
    }
    this.renderScreen(index, !!this.initialFragment || index !== 0 || this.revealSpeed <= 0 || this.initialProgress > 0);
  },
  renderScreen: function(index, fullyRevealed) {
    if (!this.screens.length) return;
    var safeIndex = Math.min(Math.max(0, index), this.screens.length - 1);
    this.clearRevealTimer();
    this.currentScreenIndex = safeIndex;
    this.clearCurrentSasayakiScreenTargets();
    if (this.screen.replaceChildren) {
      this.screen.replaceChildren();
    } else {
      while (this.screen.firstChild) this.screen.removeChild(this.screen.firstChild);
    }
    var content = document.createElement('div');
    content.className = 'hoshi-vn-content';
    content.appendChild(this.screens[safeIndex].render());
    this.screen.appendChild(content);
    if (fullyRevealed || this.revealSpeed <= 0) {
      this.revealComplete = true;
    } else {
      this.hideCurrentScreenForReveal();
    }
    this.setupReaderImages(this.screen);
    this.buildNodeOffsets();
    if (this.revealComplete) this.applyCurrentScreenHighlights();
    if (this.revealComplete) this.refreshSasayakiCuePresentation();
  },
  hideCurrentScreenForReveal: function() {
    this.revealSegments = [];
    this.revealCursor = 0;
    var walker = this.createWalker();
    var textNodes = [];
    var node;
    while (node = walker.nextNode()) {
      if (node.textContent) textNodes.push(node);
    }
    for (var i = 0; i < textNodes.length; i++) {
      this.prepareTextNodeForReveal(textNodes[i]);
    }
    if (!this.revealSegments.length) {
      this.revealComplete = true;
      return;
    }
    this.revealComplete = false;
    this.scheduleRevealTick();
  },
  prepareTextNodeForReveal: function(node) {
    var parent = node.parentNode;
    if (!parent) return;
    var text = node.textContent || '';
    if (!text) return;
    var charOffset = this.cloneTextOffsets.get(node);
    var rawOffset = this.cloneTextRawOffsets.get(node);
    var visible = document.createTextNode('');
    var hidden = document.createElement('span');
    hidden.setAttribute('data-hoshi-visual-novel-unrevealed', '');
    hidden.setAttribute('aria-hidden', 'true');
    hidden.appendChild(document.createTextNode(text));
    parent.insertBefore(visible, node);
    parent.insertBefore(hidden, node);
    parent.removeChild(node);
    this.registerCloneTextOffset(visible, charOffset, rawOffset);
    this.revealSegments.push({
      visible: visible,
      hidden: hidden,
      hiddenText: hidden.firstChild,
      chars: Array.from(text),
      revealed: 0
    });
  },
  scheduleRevealTick: function() {
    var speed = Number(this.revealSpeed);
    if (!Number.isFinite(speed) || speed <= 0) {
      this.completeCurrentReveal();
      return;
    }
    var delay = Math.max(1, 1000 / speed);
    this.revealTimer = setTimeout(() => this.revealNextCharacter(), delay);
  },
  revealNextCharacter: function() {
    this.revealTimer = null;
    if (this.revealComplete) return;
    if (!this.revealOneCharacter()) {
      this.completeCurrentReveal();
      return;
    }
    if (this.revealCursor >= this.totalRevealCharacters()) {
      this.completeCurrentReveal();
      return;
    }
    this.scheduleRevealTick();
  },
  revealOneCharacter: function() {
    for (var i = 0; i < this.revealSegments.length; i++) {
      var segment = this.revealSegments[i];
      if (segment.revealed >= segment.chars.length) continue;
      segment.revealed += 1;
      segment.visible.textContent = segment.chars.slice(0, segment.revealed).join('');
      segment.hiddenText.textContent = segment.chars.slice(segment.revealed).join('');
      this.revealCursor += 1;
      return true;
    }
    return false;
  },
  totalRevealCharacters: function() {
    return this.revealSegments.reduce(function(total, segment) {
      return total + segment.chars.length;
    }, 0);
  },
  clearRevealTimer: function() {
    if (this.revealTimer !== null && this.revealTimer !== undefined) {
      clearTimeout(this.revealTimer);
    }
    this.revealTimer = null;
  },
  completeCurrentReveal: function() {
    this.clearRevealTimer();
    this.revealSegments.forEach(function(segment) {
      segment.visible.textContent = segment.chars.join('');
      if (segment.hidden.parentNode) {
        segment.hidden.parentNode.removeChild(segment.hidden);
      }
    });
    this.revealSegments = [];
    this.revealCursor = 0;
    this.revealComplete = true;
    this.buildNodeOffsets();
    this.applyCurrentScreenHighlights();
    this.refreshSasayakiCuePresentation();
  },
  patchHighlightsForVisualNovel: function() {
    var highlights = window.hoshiHighlights;
    if (!highlights || highlights.hoshiVisualNovelPatched) return;
    var reader = this;
    var originalCreateHighlight = typeof highlights.createHighlight === 'function'
      ? highlights.createHighlight.bind(highlights)
      : null;
    var originalRemoveHighlight = typeof highlights.removeHighlight === 'function'
      ? highlights.removeHighlight.bind(highlights)
      : null;
    highlights.collectSegments = function(offset, length) {
      return reader.highlightSegmentsForChapterRawRange(offset, length);
    };
    if (originalCreateHighlight) {
      highlights.createHighlight = function(color, id) {
        var result = originalCreateHighlight(color, id);
        if (result) reader.rememberCreatedHighlight(id, color, result);
        return result;
      };
    }
    if (originalRemoveHighlight) {
      highlights.removeHighlight = function(id) {
        reader.forgetHighlight(id);
        return originalRemoveHighlight(id);
      };
    }
    highlights.hoshiVisualNovelPatched = true;
  },
  highlightSegmentsForChapterRawRange: function(offset, length) {
    var start = Number(offset) || 0;
    var end = start + Math.max(0, Number(length) || 0);
    var segments = [];
    var walker = this.createWalker();
    var node;
    while (node = walker.nextNode()) {
      var nodeStart = this.nodeStartRawOffsets.get(node);
      if (nodeStart === undefined) continue;
      var text = node.textContent || '';
      var rawCursor = nodeStart;
      var i = 0;
      var segment = null;
      var flushSegment = function() {
        if (!segment) return;
        segments.push(segment);
        segment = null;
      };
      while (i < text.length && rawCursor < end) {
        var char = String.fromCodePoint(text.codePointAt(i));
        var next = i + char.length;
        if (rawCursor >= start) {
          if (!segment) {
            segment = { node: node, start: i, end: next };
          } else {
            segment.end = next;
          }
        }
        rawCursor += 1;
        i = next;
      }
      flushSegment();
    }
    return segments;
  },
  rememberCreatedHighlight: function(id, color, result) {
    var highlights = Array.isArray(this.initialHighlights) ? this.initialHighlights.slice() : [];
    highlights = highlights.filter(function(highlight) { return highlight.id !== id; });
    highlights.push({
      id: id,
      color: color,
      offset: result.offset,
      text: result.text
    });
    this.initialHighlights = highlights;
  },
  forgetHighlight: function(id) {
    if (!Array.isArray(this.initialHighlights)) return;
    this.initialHighlights = this.initialHighlights.filter(function(highlight) {
      return highlight.id !== id;
    });
  },
  clearCurrentHighlightWrappers: function() {
    var highlights = window.hoshiHighlights;
    if (!highlights || !highlights.wrappers || typeof highlights.wrappers.forEach !== 'function') return;
    var wrapperGroups = [];
    highlights.wrappers.forEach(function(wrappers) {
      wrapperGroups.push(wrappers);
    });
    highlights.wrappers.clear();
    for (var i = 0; i < wrapperGroups.length; i++) {
      this.unwrap(wrapperGroups[i]);
    }
  },
  applyCurrentScreenHighlights: function() {
    var highlights = Array.isArray(this.initialHighlights) ? this.initialHighlights : [];
    if (!highlights.length || !window.hoshiHighlights || typeof window.hoshiHighlights.applyHighlights !== 'function') return;
    this.patchHighlightsForVisualNovel();
    this.clearCurrentHighlightWrappers();
    window.hoshiHighlights.applyHighlights(highlights);
  },
  paginate: function(direction) {
    if (this.nativeSelectionActive) return "limit";
    if (!this.screens.length) return "limit";
    if (direction === "forward") {
      if (!this.revealComplete) {
        this.completeCurrentReveal();
        return "revealed";
      }
      if (this.currentScreenIndex >= this.screens.length - 1) return "limit";
      this.renderScreen(this.currentScreenIndex + 1, false);
      return "scrolled";
    }
    if (this.currentScreenIndex <= 0) return "limit";
    this.renderScreen(this.currentScreenIndex - 1, true);
    return "scrolled";
  },
  calculateProgress: function() {
    if (!this.totalChapterChars || !this.screens.length) return 0;
    var screen = this.screens[this.currentScreenIndex];
    return Math.min(1, Math.max(0, screen.endCharCount / this.totalChapterChars));
  },
  screenIndexForProgress: function(progress) {
    if (!this.screens.length) return 0;
    var target = Math.ceil(this.totalChapterChars * Math.min(1, Math.max(0, Number(progress) || 0)));
    for (var i = 0; i < this.screens.length; i++) {
      if (this.screens[i].endCharCount >= target) return i;
    }
    return this.screens.length - 1;
  },
  restoreProgress: async function(progress) {
    await this.ensureReady();
    this.renderScreen(this.screenIndexForProgress(progress), true);
    this.notifyRestoreComplete();
  },
  screenIndexForFragment: function(fragment) {
    var raw = (fragment || '').trim();
    if (!raw) return -1;
    for (var i = 0; i < this.screens.length; i++) {
      if (this.screens[i].ids.has(raw)) return i;
    }
    return -1;
  },
  jumpToFragment: async function(fragment) {
    await this.ensureReady();
    var index = this.screenIndexForFragment(fragment);
    if (index < 0) {
      this.notifyRestoreComplete();
      return false;
    }
    this.renderScreen(index, true);
    this.notifyRestoreComplete();
    return true;
  },
  setNativeSelectionActive: function(active) {
    this.nativeSelectionActive = !!active;
  },
  sasayakiCueSignature: function(cues) {
    var items = Array.isArray(cues) ? cues : [];
    return JSON.stringify(items.map((cue) => ({
      id: cue && cue.id ? String(cue.id) : '',
      start: this.sasayakiCueStart(cue),
      length: Math.max(0, Number(cue && cue.length) || 0)
    })));
  },
  sasayakiCueDataChanged: function(cues) {
    return this.sasayakiCueSignature(cues) !== this.sasayakiCuesSignature;
  },
  setSasayakiCueData: function(cues) {
    this.sasayakiCues = Array.isArray(cues) ? cues : [];
    this.sasayakiCueMap = new Map();
    for (var i = 0; i < this.sasayakiCues.length; i++) {
      var cue = this.sasayakiCues[i];
      if (cue && cue.id) this.sasayakiCueMap.set(cue.id, cue);
    }
    this.sasayakiCuesSignature = this.sasayakiCueSignature(this.sasayakiCues);
  },
  sasayakiCueForInput: function(cue) {
    if (!cue) return null;
    if (typeof cue === 'string') return this.sasayakiCueMap.get(cue) || null;
    if (cue.id) {
      this.sasayakiCueMap.set(cue.id, cue);
    }
    return cue;
  },
  sasayakiCueStart: function(cue) {
    return Math.max(0, Number(cue && cue.start) || 0);
  },
  sasayakiCueEnd: function(cue) {
    var start = this.sasayakiCueStart(cue);
    return start + Math.max(0, Number(cue && cue.length) || 0);
  },
  sasayakiCueIntersectsScreen: function(cue, screen) {
    if (!cue || !screen) return false;
    var start = this.sasayakiCueStart(cue);
    var end = this.sasayakiCueEnd(cue);
    if (end <= start) return start >= screen.startCharCount && start <= screen.endCharCount;
    return end > screen.startCharCount && start < screen.endCharCount;
  },
  screenIndexForSasayakiCue: function(cue) {
    if (!cue || !this.screens || !this.screens.length) return -1;
    var start = this.sasayakiCueStart(cue);
    for (var i = 0; i < this.screens.length; i++) {
      var screen = this.screens[i];
      if (start >= screen.startCharCount && start < screen.endCharCount) return i;
    }
    for (var j = 0; j < this.screens.length; j++) {
      if (this.sasayakiCueIntersectsScreen(cue, this.screens[j])) return j;
    }
    return -1;
  },
  collectSasayakiCueRanges: function(cues) {
    var result = [];
    for (var i = 0; i < cues.length; i++) {
      var cue = this.sasayakiCueForInput(cues[i]);
      if (!cue || !cue.id) continue;
      result.push({ id: cue.id, ranges: this.currentScreenRangesForSasayakiCue(cue) });
    }
    return result;
  },
  currentScreenRangesForSasayakiCue: function(cue) {
    var start = this.sasayakiCueStart(cue);
    var end = this.sasayakiCueEnd(cue);
    var ranges = [];
    if (end <= start) return ranges;
    var walker = this.createWalker();
    var node;
    while (node = walker.nextNode()) {
      var nodeStart = this.nodeStartOffsets.get(node);
      if (nodeStart === undefined) continue;
      var text = node.textContent || '';
      var cursor = nodeStart;
      var offset = 0;
      var segment = null;
      var flushSegment = function() {
        if (!segment) return;
        ranges.push(segment);
        segment = null;
      };
      while (offset < text.length && cursor < end) {
        var char = String.fromCodePoint(text.codePointAt(offset));
        var next = offset + char.length;
        if (this.isMatchableChar(char)) {
          if (cursor >= start && cursor < end) {
            if (!segment) {
              segment = { node: node, start: offset, end: next };
            } else {
              segment.end = next;
            }
          } else {
            flushSegment();
          }
          cursor += 1;
          if (cursor === end) flushSegment();
        } else if (segment) {
          segment.end = next;
        }
        offset = next;
      }
      flushSegment();
    }
    return ranges;
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
  prepareSasayakiInlineTargets: function(cueRanges) {
    if (!this.isEInkMode()) {
      this.wrapSasayakiCueRanges(cueRanges);
      this.buildNodeOffsets();
    }
  },
  ensureSasayakiInlineTargetsForCue: function(cueId) {
    if (this.isEInkMode() || this.sasayakiInlineTargetsForCue(cueId).length) return;
    var cue = this.sasayakiCueMap.get(cueId);
    if (!cue) return;
    var cueRanges = this.collectSasayakiCueRanges([cue]);
    this.rememberSasayakiCueSources(cueRanges);
    this.prepareSasayakiInlineTargets(cueRanges);
  },
  ensureSasayakiCueGeometry: function(cue) {
    var cueId = typeof cue === 'string' ? cue : cue && cue.id;
    if (!cueId) return;
    var existing = this.cueGeometryRanges.get(cueId);
    if (existing && existing.length) return;
    var cueObject = this.sasayakiCueForInput(cue) || this.sasayakiCueMap.get(cueId);
    if (!cueObject) return;
    var cueRanges = this.collectSasayakiCueRanges([cueObject]);
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
    if (window.hoshiReaderPopupHost && window.hoshiReaderPopupHost.renderSasayakiHighlight) {
      window.hoshiReaderPopupHost.renderSasayakiHighlight({
        rects: this.sasayakiOverlayRects(this.activeCueId),
        eInkMode: true,
        verticalWriting: this.isVertical()
      });
    }
  },
  clearSasayakiOverlay: function() {
    if (window.hoshiReaderPopupHost && window.hoshiReaderPopupHost.clearSasayakiHighlight) {
      window.hoshiReaderPopupHost.clearSasayakiHighlight();
    }
  },
  clearInlineSasayakiCue: function(cueId) {
    var clearWrappers = function(wrappers) {
      wrappers.forEach(function(wrapper) {
        wrapper.classList.remove('hoshi-sasayaki-active');
      });
    };
    if (cueId) {
      clearWrappers(this.cueWrappers.get(cueId) || []);
      return;
    }
    this.cueWrappers.forEach(clearWrappers);
  },
  applyInlineSasayakiCue: function(cueId) {
    var wrappers = this.cueWrappers.get(cueId) || [];
    wrappers.forEach(function(wrapper) {
      wrapper.classList.add('hoshi-sasayaki-active');
    });
    return wrappers.length > 0;
  },
  clearSasayakiCuePresentation: function() {
    this.clearInlineSasayakiCue();
    this.clearSasayakiOverlay();
  },
  clearCurrentSasayakiScreenTargets: function() {
    this.cueSourceRanges.clear();
    this.cueGeometryRanges.clear();
    this.cueWrappers.clear();
    this.clearSasayakiOverlay();
  },
  clearSasayakiTargets: function() {
    this.clearSasayakiCuePresentation();
    var self = this;
    this.cueWrappers.forEach(function(wrappers) {
      self.unwrap(wrappers);
    });
    this.cueWrappers.clear();
    this.cueSourceRanges.clear();
    this.cueGeometryRanges.clear();
    this.buildNodeOffsets();
  },
  applySasayakiCues: function(cues) {
    var activeCueId = this.activeCueId;
    var nextCues = Array.isArray(cues) ? cues : [];
    var shouldRebuildScreens = this.mergeCrossScreenSasayakiCues && this.sasayakiCueDataChanged(nextCues);
    var progress = shouldRebuildScreens ? this.calculateProgress() : null;
    this.clearSasayakiTargets();
    this.setSasayakiCueData(nextCues);
    this.activeCueId = activeCueId && this.sasayakiCueMap.has(activeCueId) ? activeCueId : null;
    if (shouldRebuildScreens) {
      this.buildScreens();
      this.renderScreen(this.screenIndexForProgress(progress), true);
      return;
    }
    this.buildNodeOffsets();
    if (this.activeCueId) this.refreshSasayakiCuePresentation();
  },
  sasayakiMediaStopsBetweenScreens: function(startIndex, endIndex) {
    if (!this.screens || !this.screens.length) return [];
    if (startIndex === endIndex) return [];
    var step = startIndex < endIndex ? 1 : -1;
    var stops = [];
    for (var i = startIndex; i !== endIndex; i += step) {
      if (this.screens[i] && this.screens[i].mediaStop) {
        stops.push({ screenIndex: i });
      }
    }
    return stops;
  },
  sasayakiMediaStopsBeforeCue: function(cue) {
    var cueObject = this.sasayakiCueForInput(cue);
    var targetIndex = this.screenIndexForSasayakiCue(cueObject);
    if (targetIndex < 0) return [];
    return this.sasayakiMediaStopsBetweenScreens(this.currentScreenIndex, targetIndex);
  },
  sasayakiMediaStopsToChapterEnd: function() {
    if (!this.screens || !this.screens.length) return [];
    var stops = [];
    for (var i = this.currentScreenIndex; i < this.screens.length; i++) {
      if (this.screens[i] && this.screens[i].mediaStop) {
        stops.push({ screenIndex: i });
      }
    }
    return stops;
  },
  showSasayakiMediaStop: function(stop) {
    var index = Number(stop && stop.screenIndex);
    if (!Number.isFinite(index) || !this.screens || !this.screens.length) return null;
    var safeIndex = Math.min(Math.max(0, Math.floor(index)), this.screens.length - 1);
    if (!this.screens[safeIndex].mediaStop) return null;
    this.renderScreen(safeIndex, true);
    return this.calculateProgress();
  },
  highlightSasayakiCue: function(cue, reveal) {
    var cueObject = this.sasayakiCueForInput(cue);
    var cueId = typeof cue === 'string' ? cue : cueObject && cueObject.id;
    if (!cueId) return null;
    this.clearSasayakiCuePresentation();
    this.activeCueId = cueId;
    if (!cueObject) {
      this.refreshSasayakiCuePresentation();
      return null;
    }
    var targetIndex = this.screenIndexForSasayakiCue(cueObject);
    if (targetIndex < 0) {
      this.refreshSasayakiCuePresentation();
      return null;
    }
    if (targetIndex !== this.currentScreenIndex) {
      if (!reveal) {
        this.refreshSasayakiCuePresentation();
        return null;
      }
      this.renderScreen(targetIndex, true);
      this.refreshSasayakiCuePresentation();
      return this.calculateProgress();
    }
    if (!this.revealComplete) this.completeCurrentReveal();
    this.refreshSasayakiCuePresentation();
    return null;
  },
  clearSasayakiCue: function() {
    this.clearSasayakiCuePresentation();
    this.activeCueId = null;
  },
  refreshSasayakiCuePresentation: function() {
    if (!this.activeCueId) {
      this.clearSasayakiOverlay();
      return;
    }
    this.clearInlineSasayakiCue(this.activeCueId);
    var cue = this.sasayakiCueMap.get(this.activeCueId);
    var screen = this.screens && this.screens[this.currentScreenIndex];
    if (!cue || !this.sasayakiCueIntersectsScreen(cue, screen) || !this.revealComplete) {
      this.clearSasayakiOverlay();
      return;
    }
    if (this.isEInkMode()) {
      this.ensureSasayakiCueGeometry(cue);
      this.renderSasayakiOverlay();
    } else {
      this.clearSasayakiOverlay();
      this.ensureSasayakiInlineTargetsForCue(this.activeCueId);
      this.applyInlineSasayakiCue(this.activeCueId);
    }
  },
  resetSasayakiCues: function() {
    this.clearSasayakiTargets();
    this.setSasayakiCueData([]);
    this.activeCueId = null;
  },
  unwrap: function(wrappers) {
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
    parents.forEach(function(parent) {
      if (parent.normalize) parent.normalize();
    });
    this.buildNodeOffsets();
  }
};

__HOSHI_HIGHLIGHTS_SCRIPT__

window.addEventListener('load', function() {
  window.hoshiReader.initialize();
});
if (document.readyState === 'complete') {
  window.hoshiReader.initialize();
}
