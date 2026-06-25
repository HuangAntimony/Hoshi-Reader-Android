(function(global) {
  'use strict';

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

  function isJapaneseBreakCharacter(text) {
    var code = (text || '').codePointAt(0);
    return (code >= 0x3000 && code <= 0x303f) ||
      (code >= 0x3040 && code <= 0x30ff) ||
      (code >= 0x3400 && code <= 0x9fff) ||
      (code >= 0xf900 && code <= 0xfaff) ||
      (code >= 0xff00 && code <= 0xffef);
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

  global.hoshiReaderDomText = {
    normalizeReaderText: normalizeReaderText,
    normalizeRubyTextNodes: normalizeRubyTextNodes,
    isJapaneseBreakCharacter: isJapaneseBreakCharacter,
    stabilizeRubyAdjacentTextNodes: stabilizeRubyAdjacentTextNodes
  };
})(window);
