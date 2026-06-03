package moe.antimony.hoshi.features.reader

internal object ReaderSelectionScripts {
    fun selectInvocation(x: Float, y: Float, maxLength: Int): String =
        ReaderSelectionCommand.SelectText(x, y, maxLength).source

    fun highlightInvocation(count: Int): String =
        ReaderSelectionCommand.HighlightSelection(count).source

    fun clearInvocation(): String =
        ReaderSelectionCommand.ClearSelection.source

    fun didSelectNothing(result: String?): Boolean =
        ReaderSelectionResult.fromWebViewResult(result).selectedNothing

    fun script(): String = """
        <script>
        ${source()}
        </script>
    """.trimIndent()

    fun source(): String = """
        const CJK_UNIFIED_IDEOGRAPHS_RANGE = [0x4e00, 0x9fff];
        const CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A_RANGE = [0x3400, 0x4dbf];
        const CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B_RANGE = [0x20000, 0x2a6df];
        const CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C_RANGE = [0x2a700, 0x2b73f];
        const CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D_RANGE = [0x2b740, 0x2b81f];
        const CJK_UNIFIED_IDEOGRAPHS_EXTENSION_E_RANGE = [0x2b820, 0x2ceaf];
        const CJK_UNIFIED_IDEOGRAPHS_EXTENSION_F_RANGE = [0x2ceb0, 0x2ebef];
        const CJK_UNIFIED_IDEOGRAPHS_EXTENSION_G_RANGE = [0x30000, 0x3134f];
        const CJK_UNIFIED_IDEOGRAPHS_EXTENSION_H_RANGE = [0x31350, 0x323af];
        const CJK_UNIFIED_IDEOGRAPHS_EXTENSION_I_RANGE = [0x2ebf0, 0x2ee5f];
        const CJK_COMPATIBILITY_IDEOGRAPHS_RANGE = [0xf900, 0xfaff];
        const CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT_RANGE = [0x2f800, 0x2fa1f];
        const CJK_IDEOGRAPH_RANGES = [
          CJK_UNIFIED_IDEOGRAPHS_RANGE,
          CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A_RANGE,
          CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B_RANGE,
          CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C_RANGE,
          CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D_RANGE,
          CJK_UNIFIED_IDEOGRAPHS_EXTENSION_E_RANGE,
          CJK_UNIFIED_IDEOGRAPHS_EXTENSION_F_RANGE,
          CJK_UNIFIED_IDEOGRAPHS_EXTENSION_G_RANGE,
          CJK_UNIFIED_IDEOGRAPHS_EXTENSION_H_RANGE,
          CJK_UNIFIED_IDEOGRAPHS_EXTENSION_I_RANGE,
          CJK_COMPATIBILITY_IDEOGRAPHS_RANGE,
          CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT_RANGE,
        ];
        const FULLWIDTH_CHARACTER_RANGES = [
          [0xff10, 0xff19],
          [0xff21, 0xff3a],
          [0xff41, 0xff5a],
          [0xff01, 0xff0f],
          [0xff1a, 0xff1f],
          [0xff3b, 0xff3f],
          [0xff5b, 0xff60],
          [0xffe0, 0xffee],
        ];
        const JAPANESE_RANGES = [
          [0x3040, 0x309f],
          [0x30a0, 0x30ff],
          ...CJK_IDEOGRAPH_RANGES,
          [0xff66, 0xff9f],
          [0x30fb, 0x30fc],
          [0xff61, 0xff65],
          [0x3000, 0x303f],
          ...FULLWIDTH_CHARACTER_RANGES,
        ];
        window.hoshiRubyGeometry = window.hoshiRubyGeometry || {
          isVertical: function() {
            return window.getComputedStyle(document.body).writingMode === "vertical-rl";
          },
          rectObject: function(rect) {
            var x = rect.x !== undefined ? rect.x : rect.left;
            var y = rect.y !== undefined ? rect.y : rect.top;
            var width = rect.width !== undefined ? rect.width : rect.right - rect.left;
            var height = rect.height !== undefined ? rect.height : rect.bottom - rect.top;
            return { x: x, y: y, width: width, height: height };
          },
          rectWithBounds: function(rect) {
            var object = this.rectObject(rect);
            return {
              x: object.x,
              y: object.y,
              width: object.width,
              height: object.height,
              left: rect.left !== undefined ? rect.left : object.x,
              top: rect.top !== undefined ? rect.top : object.y,
              right: rect.right !== undefined ? rect.right : object.x + object.width,
              bottom: rect.bottom !== undefined ? rect.bottom : object.y + object.height
            };
          },
          unionRect: function(a, b) {
            var left = Math.min(a.left, b.left);
            var top = Math.min(a.top, b.top);
            var right = Math.max(a.right, b.right);
            var bottom = Math.max(a.bottom, b.bottom);
            return { x: left, y: top, width: right - left, height: bottom - top, left: left, top: top, right: right, bottom: bottom };
          },
          rangesOverlap: function(aStart, aEnd, bStart, bEnd, tolerance) {
            return bStart <= aEnd + tolerance && bEnd >= aStart - tolerance;
          },
          rubyForNode: function(node) {
            var el = node && node.nodeType === Node.TEXT_NODE ? node.parentElement : node;
            return el && el.closest ? el.closest('ruby') : null;
          },
          rubyTextRects: function(node) {
            var ruby = this.rubyForNode(node);
            if (!ruby) return [];
            var rects = [];
            ruby.querySelectorAll('rt').forEach(function(rt) {
              Array.from(rt.getClientRects()).forEach(function(rect) {
                rects.push(rect);
              });
            });
            return rects;
          },
          rubyRectMatchesBase: function(baseRect, rubyRect) {
            var ruby = this.rectWithBounds(rubyRect);
            var tolerance = 1;
            if (this.isVertical()) {
              return this.rangesOverlap(baseRect.top, baseRect.bottom, ruby.top, ruby.bottom, tolerance);
            }
            return this.rangesOverlap(baseRect.left, baseRect.right, ruby.left, ruby.right, tolerance);
          },
          rubyAwareRect: function(rect, node) {
            var rubyRects = this.rubyTextRects(node);
            if (!rubyRects.length) {
              return this.rectObject(rect);
            }
            var result = this.rectWithBounds(rect);
            rubyRects.forEach(function(rubyRect) {
              if (this.rubyRectMatchesBase(result, rubyRect)) {
                result = this.unionRect(result, this.rectWithBounds(rubyRect));
              }
            }, this);
            return this.rectObject(result);
          },
          rectsForRange: function(range) {
            var rects = Array.from(range.getClientRects());
            if (!rects.length) {
              var fallback = range.getBoundingClientRect();
              if (fallback && fallback.width > 0 && fallback.height > 0) rects = [fallback];
            }
            return rects
              .map(function(rect) { return this.rubyAwareRect(rect, range.startContainer); }, this)
              .filter(function(rect) { return rect.width > 0 && rect.height > 0; });
          },
          inlineRectsTouch: function(a, b) {
            var tolerance = 0.5;
            if (this.isVertical()) {
              return this.rangesOverlap(a.x, a.x + a.width, b.x, b.x + b.width, tolerance) &&
                b.y <= a.y + a.height + tolerance &&
                b.y + b.height >= a.y - tolerance;
            }
            return this.rangesOverlap(a.y, a.y + a.height, b.y, b.y + b.height, tolerance) &&
              b.x <= a.x + a.width + tolerance &&
              b.x + b.width >= a.x - tolerance;
          },
          mergeInlineRects: function(rects) {
            var merged = [];
            rects.forEach(function(rect) {
              var current = { x: rect.x, y: rect.y, width: rect.width, height: rect.height };
              var previous = merged[merged.length - 1];
              if (previous && this.inlineRectsTouch(previous, current)) {
                var left = Math.min(previous.x, current.x);
                var top = Math.min(previous.y, current.y);
                var right = Math.max(previous.x + previous.width, current.x + current.width);
                var bottom = Math.max(previous.y + previous.height, current.y + current.height);
                previous.x = left;
                previous.y = top;
                previous.width = right - left;
                previous.height = bottom - top;
              } else {
                merged.push(current);
              }
            }, this);
            return merged;
          }
        };
        window.hoshiSelection = {
          selection: null,
          scanDelimiters: '。、！？…‥「」『』（）()【】〈〉《》〔〕｛｝{}［］[]・：；:;，,.─\n\r',
          sentenceDelimiters: '。！？.!?\n\r',
          trailingSentenceChars: '。、！？…‥」』）)】〉》〕｝}］]',
          brackets: {'「':'」', '『': '』', '（':'）', '(':')', '【':'】', '〈':'〉', '《':'》', '〔':'〕', '｛':'｝', '{':'}', '［':'］', '[':']'},
          isCodePointJapanese: function(codePoint) {
            return JAPANESE_RANGES.some(function(range) { return codePoint >= range[0] && codePoint <= range[1]; });
          },
          isScanBoundary: function(char) {
            return /^[\s\u3000]$/.test(char) ||
              this.scanDelimiters.includes(char) ||
              (window.scanNonJapaneseText === false && !this.isCodePointJapanese(char.codePointAt(0)));
          },
          isFurigana: function(node) {
            var el = node.nodeType === Node.TEXT_NODE ? node.parentElement : node;
            return !!(el && el.closest('rt, rp'));
          },
          isVertical: function() {
            return window.hoshiRubyGeometry.isVertical();
          },
          rectObject: function(rect) {
            return window.hoshiRubyGeometry.rectObject(rect);
          },
          rectWithBounds: function(rect) {
            return window.hoshiRubyGeometry.rectWithBounds(rect);
          },
          unionRect: function(a, b) {
            return window.hoshiRubyGeometry.unionRect(a, b);
          },
          rubyForNode: function(node) {
            return window.hoshiRubyGeometry.rubyForNode(node);
          },
          rubyTextRects: function(node) {
            return window.hoshiRubyGeometry.rubyTextRects(node);
          },
          rubyAwareRect: function(rect, node) {
            return window.hoshiRubyGeometry.rubyAwareRect(rect, node);
          },
          rubyAwareSelectionRect: function(rect, node) {
            return window.hoshiRubyGeometry.rubyAwareRect(rect, node);
          },
          rubyRectMatchesLine: function(lineRect, rubyRect) {
            return window.hoshiRubyGeometry.rubyRectMatchesBase(lineRect, rubyRect);
          },
          unifyVerticalColumnRects: function(rects) {
            if (!this.isVertical() || !rects.length) return rects;
            var groups = [];
            var groupForIndex = new Array(rects.length);
            rects.forEach(function(rect, index) {
              var left = rect.x;
              var right = rect.x + rect.width;
              var group = null;
              for (var i = 0; i < groups.length; i++) {
                if (left < groups[i].right && right > groups[i].left) {
                  group = groups[i];
                  break;
                }
              }
              if (group) {
                group.left = Math.min(group.left, left);
                group.right = Math.max(group.right, right);
              } else {
                group = { left: left, right: right };
                groups.push(group);
              }
              groupForIndex[index] = group;
            });
            return rects.map(function(rect, index) {
              var group = groupForIndex[index];
              return { x: group.left, y: rect.y, width: group.right - group.left, height: rect.height };
            });
          },
          findParagraph: function(node) {
            var el = node.nodeType === Node.TEXT_NODE ? node.parentElement : node;
            return (el && el.closest('p, .glossary-content')) || null;
          },
          createWalker: function(rootNode) {
            var root = rootNode || document.body;
            return document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
              acceptNode: (n) => this.isFurigana(n) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT
            });
          },
          inCharRange: function(charRange, x, y) {
            var rects = charRange.getClientRects();
            if (rects.length) {
              for (var i = 0; i < rects.length; i++) {
                var rect = rects[i];
                if (x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom) return true;
              }
              return false;
            }
            var fallback = charRange.getBoundingClientRect();
            return x >= fallback.left && x <= fallback.right && y >= fallback.top && y <= fallback.bottom;
          },
          getCaretRange: function(x, y) {
            if (document.caretPositionFromPoint) {
              var pos = document.caretPositionFromPoint(x, y);
              if (!pos) return null;
              var range = document.createRange();
              range.setStart(pos.offsetNode, pos.offset);
              range.collapse(true);
              return range;
            }
            var element = document.elementFromPoint(x, y);
            if (!element) return null;
            var container = element.closest('p, div, span, ruby, a') || document.body;
            var walker = this.createWalker(container);
            var range = document.createRange();
            var node;
            while (node = walker.nextNode()) {
              for (var i = 0; i < node.textContent.length; i++) {
                range.setStart(node, i);
                range.setEnd(node, i + 1);
                if (this.inCharRange(range, x, y)) {
                  range.collapse(true);
                  return range;
                }
              }
            }
            return document.caretRangeFromPoint ? document.caretRangeFromPoint(x, y) : null;
          },
          getCharacterAtPoint: function(x, y) {
            var range = this.getCaretRange(x, y);
            if (!range) return null;
            var node = range.startContainer;
            if (node.nodeType !== Node.TEXT_NODE || this.isFurigana(node)) return null;
            var text = node.textContent;
            var caret = range.startOffset;
            var offsets = [caret, caret - 1, caret + 1];
            for (var i = 0; i < offsets.length; i++) {
              var offset = offsets[i];
              if (offset < 0 || offset >= text.length) continue;
              var charRange = document.createRange();
              charRange.setStart(node, offset);
              charRange.setEnd(node, offset + 1);
              if (this.inCharRange(charRange, x, y)) {
                if (this.isScanBoundary(text[offset])) return null;
                return { node: node, offset: offset };
              }
            }
            return null;
          },
          getSentenceContext: function(startNode, startOffset) {
            var container = this.findParagraph(startNode) || document.body;
            var walker = this.createWalker(container);
            walker.currentNode = startNode;
            var partsBefore = [];
            var node = startNode;
            var limit = startOffset;
            while (node) {
              var text = node.textContent;
              var foundStart = false;
              for (var i = limit - 1; i >= 0; i--) {
                if (this.sentenceDelimiters.includes(text[i])) {
                  partsBefore.push(text.slice(i + 1, limit));
                  foundStart = true;
                  break;
                }
              }
              if (foundStart) break;
              partsBefore.push(text.slice(0, limit));
              node = walker.previousNode();
              if (node) limit = node.textContent.length;
            }
            walker.currentNode = startNode;
            var partsAfter = [];
            node = startNode;
            var start = startOffset;
            while (node) {
              var afterText = node.textContent;
              var foundEnd = false;
              for (var j = start; j < afterText.length; j++) {
                if (this.sentenceDelimiters.includes(afterText[j])) {
                  var end = j + 1;
                  while (end < afterText.length && this.trailingSentenceChars.includes(afterText[end])) end++;
                  partsAfter.push(afterText.slice(start, end));
                  foundEnd = true;
                  break;
                }
              }
              if (foundEnd) break;
              partsAfter.push(afterText.slice(start));
              node = walker.nextNode();
              start = 0;
            }
            var beforeText = partsBefore.reverse().join('');
            var rawSentence = beforeText + partsAfter.join('');
            var leadingTrim = rawSentence.length - rawSentence.trimStart().length;
            return {
              sentence: rawSentence.trim(),
              sentenceOffset: Math.max(0, beforeText.length - leadingTrim)
            };
          },
          getSentence: function(startNode, startOffset) {
            return this.getSentenceContext(startNode, startOffset).sentence;
          },
          selectText: function(x, y, maxLength) {
            var hitElement = document.elementFromPoint(x, y);
            if (hitElement?.closest('a')) {
              return 'link';
            }
            if (hitElement?.closest('img, svg, .blur-wrapper')) {
              return 'image';
            }
            var hit = this.getCharacterAtPoint(x, y);
            if (!hit) {
              this.clearSelection();
              return null;
            }
            if (this.selection && hit.node === this.selection.startNode && hit.offset === this.selection.startOffset) {
              this.clearSelection();
              return null;
            }
            this.clearSelection();
            var container = this.findParagraph(hit.node) || document.body;
            var walker = this.createWalker(container);
            var text = '';
            var node = hit.node;
            var offset = hit.offset;
            var ranges = [];
            walker.currentNode = node;
            while (text.length < maxLength && node) {
              var content = node.textContent;
              var start = offset;
              while (offset < content.length && text.length < maxLength) {
                var char = content[offset];
                if (this.isScanBoundary(char)) break;
                text += char;
                offset++;
              }
              if (offset > start) ranges.push({ node: node, start: start, end: offset });
              if (offset < content.length || text.length >= maxLength) break;
              node = walker.nextNode();
              offset = 0;
            }
            if (!text) return null;
            this.selection = { startNode: hit.node, startOffset: hit.offset, ranges: ranges, text: text };
            var sentenceContext = this.getSentenceContext(hit.node, hit.offset);
            var normalizedOffset = window.hoshiReader ? this.getNormalizedOffset(hit.node, hit.offset) : null;
            HoshiTextSelection.postMessage(JSON.stringify({
              text: text,
              sentence: sentenceContext.sentence,
              rect: this.getSelectionRect(x, y),
              normalizedOffset: normalizedOffset,
              sentenceOffset: sentenceContext.sentenceOffset
            }));
            return text;
          },
          getSelectionRect: function(x, y) {
            if (!this.selection || !this.selection.ranges.length) return null;
            var first = this.selection.ranges[0];
            var range = document.createRange();
            range.setStart(first.node, first.start);
            range.setEnd(first.node, first.start + 1);
            var rects = window.hoshiRubyGeometry.rectsForRange(range);
            var rect = rects.find(function(rect) {
              return x >= rect.x && x <= rect.x + rect.width && y >= rect.y && y <= rect.y + rect.height;
            }) || rects[0];
            return rect || null;
          },
          highlightSelection: function(charCount) {
            if (!this.selection || !this.selection.ranges.length || !CSS.highlights) return;
            var highlights = this.selectionCharacterRanges(charCount);
            CSS.highlights.set('hoshi-selection', new Highlight(...highlights));
          },
          selectionRects: function(charCount) {
            if (!this.selection || !this.selection.ranges.length) return [];
            var rects = [];
            this.selectionCharacterRanges(charCount).forEach(function(range) {
              window.hoshiRubyGeometry.rectsForRange(range).forEach(function(rect) {
                rects.push(rect);
              });
            }, this);
            return this.unifyVerticalColumnRects(this.mergeSelectionRects(rects));
          },
          selectionCharacterRanges: function(charCount) {
            if (!this.selection || !this.selection.ranges.length) return [];
            var ranges = [];
            var remaining = charCount;
            for (var i = 0; i < this.selection.ranges.length; i++) {
              var r = this.selection.ranges[i];
              if (remaining <= 0) break;
              var start = r.start;
              var end = start;
              while (end < r.end && remaining > 0) {
                var char = String.fromCodePoint(r.node.textContent.codePointAt(end));
                end += char.length;
                remaining--;
                var range = document.createRange();
                range.setStart(r.node, start);
                range.setEnd(r.node, end);
                ranges.push(range);
                start = end;
              }
            }
            return ranges;
          },
          mergeSelectionRects: function(rects) {
            var merged = [];
            rects.forEach(function(rect) {
              var current = { x: rect.x, y: rect.y, width: rect.width, height: rect.height };
              var previous = merged[merged.length - 1];
              if (previous && this.selectionRectsTouch(previous, current)) {
                var left = Math.min(previous.x, current.x);
                var top = Math.min(previous.y, current.y);
                var right = Math.max(previous.x + previous.width, current.x + current.width);
                var bottom = Math.max(previous.y + previous.height, current.y + current.height);
                previous.x = left;
                previous.y = top;
                previous.width = right - left;
                previous.height = bottom - top;
              } else {
                merged.push(current);
              }
            }, this);
            return merged;
          },
          selectionRectsTouch: function(a, b) {
            return window.hoshiRubyGeometry.inlineRectsTouch(a, b);
          },
          getNormalizedOffset: function(targetNode, offset) {
            if (!window.hoshiReader || !window.hoshiReader.nodeStartOffsets) return null;
            var count = window.hoshiReader.nodeStartOffsets.get(targetNode) || 0;
            var text = targetNode.textContent;
            for (var i = 0; i < offset;) {
              var char = String.fromCodePoint(text.codePointAt(i));
              if (window.hoshiReader.isMatchableChar(char)) count++;
              i += char.length;
            }
            return count;
          },
          clearSelection: function() {
            window.getSelection().removeAllRanges();
            if (CSS.highlights && CSS.highlights.get('hoshi-selection')) CSS.highlights.get('hoshi-selection').clear();
            this.selection = null;
          }
        };
    """.trimIndent()
}
