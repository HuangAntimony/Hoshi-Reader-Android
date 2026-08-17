(function(global) {
  'use strict';

  var EMPHASIS_CLASS = 'hoshi-emphasis';
  var EMPHASIS_SHAPE_NAMES = ['double-circle', 'sesame', 'circle', 'dot', 'triangle'];
  var EMPHASIS_SHAPES = {
    'double-circle': ['◉', '◎'],
    sesame: ['﹅', '﹆'],
    circle: ['●', '○'],
    dot: ['•', '◦'],
    triangle: ['▲', '△']
  };
  var IGNORED_TAGS = ['SCRIPT', 'STYLE'];

  function documentForNode(node) {
    return (node && node.ownerDocument) || global.document || (typeof document !== 'undefined' ? document : null);
  }

  function emphasisMark(style, vertical) {
    var value = String(style || '').trim();
    if (!value || value === 'none') return null;
    var custom = value.match(/["'](.+?)["']/);
    if (custom) return custom[1];
    var shape = EMPHASIS_SHAPE_NAMES.find(function(name) {
      return value.indexOf(name) >= 0;
    }) || (vertical ? 'sesame' : 'circle');
    return EMPHASIS_SHAPES[shape][value.indexOf('open') >= 0 ? 1 : 0];
  }

  function computedEmphasisStyle(element) {
    if (!element || !global.getComputedStyle) return '';
    var style = global.getComputedStyle(element);
    if (!style) return '';
    return style.webkitTextEmphasisStyle ||
      style.textEmphasisStyle ||
      (style.getPropertyValue ? style.getPropertyValue('-webkit-text-emphasis-style') : '') ||
      '';
  }

  function elementMark(element, vertical) {
    return emphasisMark(computedEmphasisStyle(element), vertical);
  }

  function emphasisTextNodes(element) {
    var nodes = [];
    var visit = function(node) {
      if (node.nodeType === Node.TEXT_NODE) {
        nodes.push(node);
        return;
      }
      if (node.nodeType !== Node.ELEMENT_NODE) return;
      if (node.tagName === 'RUBY' || IGNORED_TAGS.indexOf(node.tagName) >= 0) return;
      Array.from(node.childNodes).forEach(visit);
    };
    Array.from(element.childNodes).forEach(visit);
    return nodes;
  }

  function markRuby(doc, char, mark) {
    var ruby = doc.createElement('ruby');
    ruby.className = EMPHASIS_CLASS;
    ruby.appendChild(doc.createTextNode(char));
    var rt = doc.createElement('rt');
    rt.textContent = mark;
    ruby.appendChild(rt);
    return ruby;
  }

  function wrapTextNode(doc, textNode, mark) {
    var fragment = doc.createDocumentFragment();
    Array.from(textNode.textContent || '').forEach(function(char) {
      if (/\s/.test(char)) {
        fragment.appendChild(doc.createTextNode(char));
        return;
      }
      fragment.appendChild(markRuby(doc, char, mark));
    });
    textNode.parentNode.replaceChild(fragment, textNode);
  }

  function normalizeTextEmphasis(root, options) {
    var doc = documentForNode(root);
    var scope = root || (doc && doc.body);
    if (!doc || !scope || !scope.querySelectorAll) return;
    var vertical = !!(options && options.vertical);
    Array.from(scope.querySelectorAll('*')).forEach(function(element) {
      if (element.closest('ruby')) return;
      var mark = elementMark(element, vertical);
      if (!mark) return;
      if (elementMark(element.parentElement, vertical)) return;
      emphasisTextNodes(element).forEach(function(textNode) {
        wrapTextNode(doc, textNode, mark);
      });
      element.style.webkitTextEmphasis = 'none';
      element.style.textEmphasis = 'none';
    });
  }

  global.hoshiReaderEmphasis = {
    emphasisClass: EMPHASIS_CLASS,
    emphasisMark: emphasisMark,
    normalizeTextEmphasis: normalizeTextEmphasis
  };
})(window);
