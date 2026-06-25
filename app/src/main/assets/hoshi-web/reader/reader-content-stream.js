(function(global) {
  'use strict';

  var TEXT_NODE = 3;
  var ELEMENT_NODE = 1;
  var DOCUMENT_FRAGMENT_NODE = 11;
  var ignoredTags = new Set(['rt', 'rp', 'script', 'style']);
  var mediaTags = new Set(['img', 'svg', 'image', 'video', 'canvas', 'audio', 'picture', 'figure', 'table', 'iframe', 'object', 'embed']);
  var ttuRegexNegated = /[^0-9A-Za-z○◯々-〇〻ぁ-ゖゝ-ゞァ-ヺー０-９Ａ-Ｚａ-ｚｦ-ﾝ\p{Radical}\p{Unified_Ideograph}]+/gimu;
  var ttuRegex = /[0-9A-Za-z○◯々-〇〻ぁ-ゖゝ-ゞァ-ヺー０-９Ａ-Ｚａ-ｚｦ-ﾝ\p{Radical}\p{Unified_Ideograph}]/iu;

  function tagName(node) {
    return node && node.nodeType === ELEMENT_NODE ? String(node.tagName || '').toLowerCase() : '';
  }

  function childrenOf(node) {
    return Array.from(node && node.childNodes ? node.childNodes : []);
  }

  function normalizeText(text) {
    return String(text || '').replace(ttuRegexNegated, '');
  }

  function isMatchableChar(char) {
    return ttuRegex.test(char || '');
  }

  function countChars(text) {
    return Array.from(normalizeText(text)).length;
  }

  function countRawChars(text) {
    return Array.from(text || '').length;
  }

  function ownIdsForNode(node) {
    var ids = new Set();
    if (node && node.nodeType === ELEMENT_NODE) {
      var id = node.getAttribute && node.getAttribute('id');
      if (id) ids.add(id);
      var name = node.getAttribute && node.getAttribute('name');
      if (name) ids.add(name);
    }
    return ids;
  }

  function mergeIds(into, from) {
    (from || new Set()).forEach(function(id) { into.add(id); });
    return into;
  }

  function hasClass(node, className) {
    if (!node || node.nodeType !== ELEMENT_NODE) return false;
    if (node.classList && node.classList.contains) return node.classList.contains(className);
    var value = node.getAttribute && node.getAttribute('class');
    return String(value || '').split(/\s+/).indexOf(className) >= 0;
  }

  function isStandaloneMediaNode(node) {
    var tag = tagName(node);
    if (tag === 'img' && (hasClass(node, 'gaiji') || hasClass(node, 'gaiji-line'))) return false;
    return mediaTags.has(tag);
  }

  function isIgnoredNode(node, root) {
    var current = node && node.nodeType === TEXT_NODE ? node.parentNode : node;
    while (current && current !== root) {
      if (current.nodeType === ELEMENT_NODE && ignoredTags.has(tagName(current))) return true;
      current = current.parentNode;
    }
    return !!(current && current.nodeType === ELEMENT_NODE && ignoredTags.has(tagName(current)));
  }

  function isContainerNode(node) {
    return !!(node && (node.nodeType === ELEMENT_NODE || node.nodeType === DOCUMENT_FRAGMENT_NODE));
  }

  function topLevelNodeFor(root, node) {
    var current = node;
    while (current && current.parentNode && current.parentNode !== root) {
      current = current.parentNode;
    }
    return current;
  }

  function nearestMediaRenderRoot(root, node) {
    var current = node;
    var candidate = node;
    while (current && current.parentNode && current.parentNode !== root) {
      var parent = current.parentNode;
      if (parent.nodeType === ELEMENT_NODE && !mediaTags.has(tagName(parent))) {
        candidate = parent;
      }
      current = parent;
    }
    return candidate || node;
  }

  function closestAncestor(node, root, targetTag) {
    var current = node && node.nodeType === TEXT_NODE ? node.parentNode : node;
    while (current && current !== root) {
      if (current.nodeType === ELEMENT_NODE && tagName(current) === targetTag) return current;
      current = current.parentNode;
    }
    return current && current.nodeType === ELEMENT_NODE && tagName(current) === targetTag ? current : null;
  }

  function ReaderContentStream(root, options) {
    this.root = root;
    this.options = options || {};
    this.textEntries = [];
    this.totalMatchableChars = 0;
    this.totalRawChars = 0;
    this.sourceTextOffsets = new WeakMap();
    this.sourceTextRawOffsets = new WeakMap();
    this.sourceNodeStats = new WeakMap();
    this.sourceOrderIndexes = new WeakMap();
    this.sourcePreorderIndexes = new WeakMap();
    this.rebuild();
  }

  ReaderContentStream.prototype = {
    normalizeText: normalizeText,
    isMatchableChar: isMatchableChar,
    countChars: countChars,
    countRawChars: countRawChars,

    rebuild: function() {
      this.textEntries = [];
      this.sourceTextOffsets = new WeakMap();
      this.sourceTextRawOffsets = new WeakMap();
      this.sourceNodeStats = new WeakMap();
      this.sourceOrderIndexes = new WeakMap();
      this.sourcePreorderIndexes = new WeakMap();
      this.indexSourcePreorder();

      var topLevelNodes = childrenOf(this.root);
      for (var order = 0; order < topLevelNodes.length; order++) {
        this.sourceOrderIndexes.set(topLevelNodes[order], order);
      }

      var count = 0;
      var rawCount = 0;
      this.walkTextNodes(this.root, (function(node) {
        this.sourceTextOffsets.set(node, count);
        this.sourceTextRawOffsets.set(node, rawCount);
        var entry = {
          node: node,
          order: this.sourceOrderForTextNode(node),
          preorder: this.sourcePreorderForNode(node),
          rubyRoot: this.rubyRootForTextNode(node),
          startChar: count,
          startRaw: rawCount,
          text: node.textContent || ''
        };
        count += countChars(entry.text);
        rawCount += countRawChars(entry.text);
        entry.endChar = count;
        entry.endRaw = rawCount;
        this.textEntries.push(entry);
        this.updateSourceNodeStats(node, entry);
      }).bind(this));

      this.totalMatchableChars = count;
      this.totalRawChars = rawCount;
    },

    indexSourcePreorder: function() {
      var index = 0;
      var visit = (function(node) {
        if (!node) return;
        this.sourcePreorderIndexes.set(node, index);
        index += 1;
        childrenOf(node).forEach(visit);
      }).bind(this);
      visit(this.root);
    },

    walkTextNodes: function(root, visit) {
      if (!root || isIgnoredNode(root, this.root)) return;
      if (root.nodeType === TEXT_NODE) {
        visit(root);
        return;
      }
      if (!isContainerNode(root)) return;
      var children = childrenOf(root);
      for (var i = 0; i < children.length; i++) {
        this.walkTextNodes(children[i], visit);
      }
    },

    updateSourceNodeStats: function(node, entry) {
      var current = node;
      while (current) {
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
        if (current === this.root) break;
        current = current.parentNode;
      }
    },

    statsForNode: function(node) {
      return this.sourceNodeStats.get(node) || { hasText: false, startChar: 0, endChar: 0, startRaw: 0, endRaw: 0 };
    },

    sourceOrderForTextNode: function(node) {
      var root = topLevelNodeFor(this.root, node);
      var order = this.sourceOrderIndexes.get(root);
      return order === undefined ? 0 : order;
    },

    idsForNode: function(root, extraIds) {
      var ids = new Set(extraIds || []);
      var visit = function(node) {
        mergeIds(ids, ownIdsForNode(node));
        childrenOf(node).forEach(visit);
      };
      visit(root);
      return ids;
    },

    idsForTextNode: function(node) {
      var ids = new Set();
      var current = node ? node.parentNode : null;
      while (current) {
        mergeIds(ids, ownIdsForNode(current));
        if (current === this.root) break;
        current = current.parentNode;
      }
      return ids;
    },

    idsForMediaUnit: function(renderRoot, mediaNode) {
      if (renderRoot === mediaNode) return this.idsForNode(renderRoot);
      var ids = this.idsForNode(mediaNode);
      var current = mediaNode ? mediaNode.parentNode : null;
      while (current) {
        mergeIds(ids, ownIdsForNode(current));
        if (current === renderRoot || current === this.root) break;
        current = current.parentNode;
      }
      return ids;
    },

    textItems: function() {
      var items = [];
      for (var e = 0; e < this.textEntries.length; e++) {
        var entry = this.textEntries[e];
        var text = entry.text;
        var offset = 0;
        var rawOffset = 0;
        var matchableOffset = 0;
        while (offset < text.length) {
          var char = String.fromCodePoint(text.codePointAt(offset));
          var next = offset + char.length;
          var matchable = isMatchableChar(char);
          items.push({
            node: entry.node,
            order: entry.order,
            preorder: entry.preorder,
            rubyRoot: entry.rubyRoot,
            char: char,
            start: offset,
            end: next,
            chapterRawStart: entry.startRaw + rawOffset,
            chapterRawEnd: entry.startRaw + rawOffset + 1,
            chapterCharStart: entry.startChar + matchableOffset,
            chapterCharEnd: entry.startChar + matchableOffset + (matchable ? 1 : 0)
          });
          if (matchable) matchableOffset += 1;
          rawOffset += 1;
          offset = next;
        }
      }
      return items;
    },

    containsStandaloneMedia: function(root) {
      if (!root || isIgnoredNode(root, this.root)) return false;
      if (root.nodeType === ELEMENT_NODE && isStandaloneMediaNode(root)) return true;
      return childrenOf(root).some((function(child) {
        return this.containsStandaloneMedia(child);
      }).bind(this));
    },

    mediaUnits: function() {
      var result = [];
      var visit = (function(node) {
        if (!node || isIgnoredNode(node, this.root)) return;
        if (node.nodeType === ELEMENT_NODE && isStandaloneMediaNode(node)) {
          var renderRoot = this.renderRootForMediaNode(node);
          var position = this.sourcePositionForNode(renderRoot);
          result.push({
            node: renderRoot,
            mediaNode: node,
            renderRoot: renderRoot,
            tagName: tagName(node),
            mediaTagName: tagName(node),
            renderRootTagName: tagName(renderRoot),
            sourceOrder: this.sourceOrderForNode(renderRoot),
            preorder: this.sourcePreorderForNode(node),
            startChar: position.startChar,
            endChar: position.endChar,
            startRaw: position.startRaw,
            endRaw: position.endRaw,
            ids: this.idsForMediaUnit(renderRoot, node)
          });
          return;
        }
        childrenOf(node).forEach(visit);
      }).bind(this);
      visit(this.root);
      result.sort(function(a, b) {
        return a.preorder - b.preorder;
      });
      return result;
    },

    renderRootForMediaNode: function(node) {
      return nearestMediaRenderRoot(this.root, node);
    },

    sourceOrderForNode: function(node) {
      var root = topLevelNodeFor(this.root, node);
      var order = this.sourceOrderIndexes.get(root);
      return order === undefined ? 0 : order;
    },

    sourcePreorderForNode: function(node) {
      var order = this.sourcePreorderIndexes.get(node);
      return order === undefined ? 0 : order;
    },

    sourcePositionForNode: function(node) {
      var stats = this.statsForNode(node);
      if (stats.hasText) return stats;
      var preorder = this.sourcePreorderForNode(node);
      var previous = null;
      for (var i = 0; i < this.textEntries.length; i++) {
        var entry = this.textEntries[i];
        if (entry.preorder > preorder) {
          return {
            hasText: false,
            startChar: entry.startChar,
            endChar: entry.startChar,
            startRaw: entry.startRaw,
            endRaw: entry.startRaw
          };
        }
        if (entry.preorder < preorder) previous = entry;
      }
      var char = previous ? previous.endChar : 0;
      var raw = previous ? previous.endRaw : 0;
      return { hasText: false, startChar: char, endChar: char, startRaw: raw, endRaw: raw };
    },

    rubyRootForTextNode: function(node) {
      return closestAncestor(node, this.root, 'ruby');
    },

    rubyRoots: function() {
      var roots = [];
      var seen = new WeakSet();
      for (var i = 0; i < this.textEntries.length; i++) {
        var root = this.textEntries[i].rubyRoot;
        if (root && !seen.has(root)) {
          seen.add(root);
          roots.push(root);
        }
      }
      return roots;
    }
  };

  global.hoshiReaderContentStream = {
    create: function(root, options) {
      return new ReaderContentStream(root, options);
    },
    normalizeText: normalizeText,
    isMatchableChar: isMatchableChar,
    countChars: countChars,
    countRawChars: countRawChars
  };
})(window);
