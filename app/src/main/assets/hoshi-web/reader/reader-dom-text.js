(function(global) {
  'use strict';

  // Native handles may end in annotations or element boundaries. Read the body
  // slices without changing the Range or its native selection handles.
  function selectedTextSegments(range) {
    if (!range || range.collapsed) return [];
    var isAnnotation = function(node) { return !!node.parentElement?.closest('rt, rp'); };
    var walker = document.createTreeWalker(range.commonAncestorContainer, NodeFilter.SHOW_TEXT, {
      acceptNode: function(node) { return isAnnotation(node) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT; }
    });
    walker.currentNode = range.startContainer;
    var node = range.startContainer.nodeType === Node.TEXT_NODE ? range.startContainer : walker.nextNode();
    var segments = [];
    while (node) {
      if (range.comparePoint(node, 0) > 0) break;
      if (!isAnnotation(node) && range.intersectsNode(node)) {
        var start = node === range.startContainer ? range.startOffset : 0;
        var end = node === range.endContainer ? range.endOffset : node.textContent.length;
        if (end > start) segments.push({ node: node, start: start, end: end });
      }
      node = walker.nextNode();
    }
    return segments;
  }

  function normalizeReaderText(context, parent) {
    if (!parent) return;
    normalizeRubyTextNodes(parent);
    parent.normalize();
    stabilizeRubyAdjacentTextNodes(context, parent);
  }

  function normalizeRubyTextNodes(root) {
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
  }

  function textSemantics() {
    if (!global.hoshiReaderTextSemantics) {
      throw new Error('hoshiReaderTextSemantics is required for reader DOM text normalization');
    }
    return global.hoshiReaderTextSemantics;
  }

  function isJapaneseBreakCharacter(text) {
    return textSemantics().isJapaneseBreakCharacter(text);
  }

  function stabilizeRubyAdjacentTextNodes(context, root) {
    if (!context || typeof context.isVertical !== 'function' || !context.isVertical()) return;
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
        if (splitCount < splitLimit && isJapaneseBreakCharacter(char)) {
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
  }

  var sasayakiBlockTags = new Set([
    'address', 'article', 'aside', 'blockquote', 'body', 'dd', 'div', 'dl', 'dt',
    'fieldset', 'figcaption', 'footer', 'form', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
    'header', 'hr', 'li', 'main', 'nav', 'ol', 'p', 'pre', 'section', 'ul'
  ]);
  var sasayakiBarriers = new Set([
    'br', 'img', 'svg', 'image', 'video', 'audio', 'canvas', 'picture', 'figure',
    'table', 'iframe', 'object', 'embed', 'script', 'style'
  ]);

  function isSasayakiBoundary(node) {
    if (node.nodeType !== 1) return false;
    var tag = String(node.tagName || '').toLowerCase();
    if (sasayakiBlockTags.has(tag) || sasayakiBarriers.has(tag)) return true;
    var display = global.getComputedStyle ? String(global.getComputedStyle(node).display || '') : '';
    return /^(block|flow-root|flex|grid|list-item|table.*)$/.test(display);
  }

  // VN must capture computed CSS boundaries while the source is still attached.
  function captureSasayakiBoundaries(root) {
    var boundaries = new WeakSet();
    function visit(node) {
      if (isSasayakiBoundary(node)) boundaries.add(node);
      Array.from(node.childNodes || []).forEach(visit);
    }
    visit(root);
    return boundaries;
  }

  // Read structure independently of the text walker, which cannot see empty
  // paragraphs, br elements or images between two accepted text nodes.
  function createSasayakiTextIndex(root, entries, boundaries) {
    // The caller owns text filtering and character counts; never drop accepted entries.
    var accepted = new Map(entries.map(function(entry) { return [entry.node, entry]; }));
    var epoch = 0;
    var previousEpoch = -1;
    var ordered = [];
    function visit(node) {
      if (node.nodeType === 3) {
        var entry = accepted.get(node);
        if (entry) {
          ordered.push({ node: node, text: entry.text, boundaryBefore: epoch !== previousEpoch });
          previousEpoch = epoch;
        }
        return;
      }
      // Ruby annotations are outside the logical base-text flow, including their layout.
      var tag = String(node.tagName || '').toLowerCase();
      if (tag === 'rt' || tag === 'rp') return;
      var boundary = boundaries ? boundaries.has(node) : isSasayakiBoundary(node);
      if (boundary) epoch += 1;
      Array.from(node.childNodes || []).forEach(visit);
      if (boundary) epoch += 1;
    }
    visit(root);
    return textSemantics().createSasayakiTextIndex(ordered);
  }

  function sasayakiSegments(index, rawRange) {
    if (!rawRange) return [];
    var entries = index.entries;
    var low = 0;
    var high = entries.length;
    while (low < high) {
      var middle = (low + high) >>> 1;
      if (entries[middle].rawEnd <= rawRange.start) low = middle + 1;
      else high = middle;
    }
    var segments = [];
    for (var i = low; i < entries.length && entries[i].rawStart < rawRange.end; i++) {
      var entry = entries[i];
      var start = Math.max(rawRange.start, entry.rawStart) - entry.rawStart;
      var end = Math.min(rawRange.end, entry.rawEnd) - entry.rawStart;
      if (start < end) segments.push({ node: entry.node, start: entry.utf16Offsets[start], end: entry.utf16Offsets[end] });
    }
    return segments;
  }

  global.hoshiReaderDomText = {
    selectedTextSegments: selectedTextSegments,
    captureSasayakiBoundaries: captureSasayakiBoundaries,
    createSasayakiTextIndex: createSasayakiTextIndex,
    sasayakiSegments: sasayakiSegments,
    normalizeReaderText: normalizeReaderText,
    normalizeRubyTextNodes: normalizeRubyTextNodes,
    isJapaneseBreakCharacter: isJapaneseBreakCharacter,
    stabilizeRubyAdjacentTextNodes: stabilizeRubyAdjacentTextNodes
  };
})(window);
