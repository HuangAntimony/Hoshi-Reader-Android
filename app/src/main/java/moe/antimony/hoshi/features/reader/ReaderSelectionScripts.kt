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
        window.hoshiSelection = {
          selection: null,
          scanDelimiters: '。、！？…‥「」『』（）()【】〈〉《》〔〕｛｝{}［］[]・：；:;，,.─\n\r',
          sentenceDelimiters: '。！？.!?\n\r',
          trailingSentenceChars: '。、！？…‥」』）)】〉》〕｝}］]',
          brackets: {'「':'」', '『': '』', '（':'）', '(':')', '【':'】', '〈':'〉', '《':'》', '〔':'〕', '｛':'｝', '{':'}', '［':'］', '[':']'},
          isScanBoundary: function(char) {
            return /^[\s\u3000]$/.test(char) || this.scanDelimiters.includes(char);
          },
          isFurigana: function(node) {
            var el = node.nodeType === Node.TEXT_NODE ? node.parentElement : node;
            return !!(el && el.closest('rt, rp'));
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
            if (document.elementFromPoint(x, y)?.closest('a')) {
              return null;
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
            var rects = Array.from(range.getClientRects());
            var rect = rects.find(function(rect) { return x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom; }) || range.getBoundingClientRect();
            return { x: rect.x, y: rect.y, width: rect.width, height: rect.height };
          },
          highlightSelection: function(charCount) {
            if (!this.selection || !this.selection.ranges.length || !CSS.highlights) return;
            var highlights = [];
            var remaining = charCount;
            for (var i = 0; i < this.selection.ranges.length; i++) {
              var r = this.selection.ranges[i];
              if (remaining <= 0) break;
              var end = r.start;
              while (end < r.end && remaining > 0) {
                var char = String.fromCodePoint(r.node.textContent.codePointAt(end));
                end += char.length;
                remaining--;
              }
              var range = document.createRange();
              range.setStart(r.node, r.start);
              range.setEnd(r.node, end);
              highlights.push(range);
            }
            CSS.highlights.set('hoshi-selection', new Highlight(...highlights));
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
