window.hoshiReader = {
  revealSpeed: __HOSHI_VISUAL_NOVEL_REVEAL_SPEED__,
  screenMode: __HOSHI_VISUAL_NOVEL_SCREEN_MODE_LITERAL__,
  sentencesPerScreen: __HOSHI_VISUAL_NOVEL_SENTENCES_PER_SCREEN__,
  preserveDialogue: __HOSHI_VISUAL_NOVEL_PRESERVE_DIALOGUE__,
  initialProgress: __HOSHI_INITIAL_PROGRESS__,
  initialFragment: __HOSHI_INITIAL_FRAGMENT_LITERAL__,
  initialHighlights: __HOSHI_INITIAL_HIGHLIGHTS_JSON__,
  nativeSelectionActive: false,
  activeCueId: null,
  cueWrappers: new Map(),
  cueSourceRanges: new Map(),
  cueGeometryRanges: new Map(),
  nodeStartOffsets: new WeakMap(),
  nodeStartRawOffsets: new WeakMap(),
  sourceTextOffsets: new WeakMap(),
  sourceTextRawOffsets: new WeakMap(),
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
    this.sourceEntries = [];
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
    }
    this.totalChapterChars = count;
  },
  buildScreens: function() {
    var mode = String(this.screenMode || '').toLowerCase();
    if (mode === 'sentence' || mode === 'sentences') {
      this.screens = this.buildSentenceScreens();
    } else {
      this.screens = this.buildBlockScreens();
    }
    if (!this.screens.length) {
      this.screens.push({
        startCharCount: 0,
        endCharCount: 0,
        startRawCount: 0,
        endRawCount: 0,
        ids: new Set(),
        render: () => document.createDocumentFragment()
      });
    }
  },
  buildBlockScreens: function() {
    var screens = [];
    var runningEnd = 0;
    var children = Array.from(this.sourceRoot.childNodes);
    for (var i = 0; i < children.length; i++) {
      let child = children[i];
      if (child.nodeType === Node.TEXT_NODE && !(child.textContent || '').trim()) continue;
      if (child.nodeType === Node.ELEMENT_NODE && this.isIgnoredElement(child)) continue;
      var stats = this.statsForSourceNode(child);
      var start = stats.hasText ? stats.startChar : runningEnd;
      var end = stats.hasText ? stats.endChar : start;
      runningEnd = end;
      screens.push({
        startCharCount: start,
        endCharCount: end,
        startRawCount: stats.hasText ? stats.startRaw : start,
        endRawCount: stats.hasText ? stats.endRaw : start,
        ids: this.collectIdsForNode(child),
        render: () => {
          var fragment = document.createDocumentFragment();
          fragment.appendChild(this.cloneSourceNodeWithOffsets(child));
          return fragment;
        }
      });
    }
    return screens;
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
      units.push({
        standalone: true,
        order: i,
        startCharCount: position.startChar,
        endCharCount: position.endChar,
        startRawCount: position.startRaw,
        endRawCount: position.endRaw,
        ids: this.collectIdsForNode(child),
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
    var result = { hasText: false, startChar: 0, endChar: 0, startRaw: 0, endRaw: 0 };
    for (var i = 0; i < this.sourceEntries.length; i++) {
      var entry = this.sourceEntries[i];
      if (!this.isDescendantOf(entry.node, root)) continue;
      if (!result.hasText) {
        result.hasText = true;
        result.startChar = entry.startChar;
        result.startRaw = entry.startRaw;
      }
      result.endChar = entry.endChar;
      result.endRaw = entry.endRaw;
    }
    return result;
  },
  isDescendantOf: function(node, root) {
    var current = node;
    while (current) {
      if (current === root) return true;
      current = current.parentNode;
    }
    return false;
  },
  collectIdsForNode: function(root) {
    var ids = new Set();
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
    if (this.screen.replaceChildren) {
      this.screen.replaceChildren();
    } else {
      while (this.screen.firstChild) this.screen.removeChild(this.screen.firstChild);
    }
    this.screen.appendChild(this.screens[safeIndex].render());
    if (fullyRevealed || this.revealSpeed <= 0) {
      this.revealComplete = true;
    } else {
      this.hideCurrentScreenForReveal();
    }
    this.setupReaderImages(this.screen);
    this.buildNodeOffsets();
    if (this.revealComplete) this.applyCurrentScreenHighlights();
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
  applySasayakiCues: function(cues) {
    this.sasayakiCues = Array.isArray(cues) ? cues : [];
    this.activeCueId = null;
    this.buildNodeOffsets();
  },
  highlightSasayakiCue: function(cue) {
    this.activeCueId = typeof cue === 'string' ? cue : cue && cue.id;
    return null;
  },
  clearSasayakiCue: function() {
    this.activeCueId = null;
  },
  refreshSasayakiCuePresentation: function() {},
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
