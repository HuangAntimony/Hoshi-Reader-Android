package moe.antimony.hoshi.features.reader

internal enum class ReaderNavigationDirection(val jsValue: String) {
    Forward("forward"),
    Backward("backward"),
}

internal object ReaderPaginationScripts {
    fun paginateInvocation(direction: ReaderNavigationDirection): String =
        "window.hoshiReader.paginate('${direction.jsValue}')"

    fun progressInvocation(): String =
        "window.hoshiReader.calculateProgress()"

    fun applySasayakiCuesInvocation(cuesJson: String): String =
        "window.hoshiReader.applySasayakiCues($cuesJson)"

    fun highlightSasayakiCueInvocation(cueId: String, reveal: Boolean): String =
        "window.hoshiReader.highlightSasayakiCue(${cueId.javaScriptStringLiteral()}, $reveal)"

    fun clearSasayakiCueInvocation(): String =
        "window.hoshiReader.clearSasayakiCue()"

    fun didScroll(result: String?): Boolean =
        result?.trim()?.trim('"') == "scrolled"

    fun doubleResult(result: String?): Double? =
        result?.trim()?.trim('"')?.toDoubleOrNull()

    fun shellScript(
        initialProgress: Double = 0.0,
        settings: ReaderSettings = ReaderSettings(),
        sasayakiCuesJson: String? = null,
    ): String = """
        <script>
        window.hoshiReader = {
          pageHeight: 0,
          pageWidth: 0,
          cueWrappers: new Map(),
          activeCueId: null,
          ttuRegexNegated: /[^0-9A-Za-z○◯々-〇〻ぁ-ゖゝ-ゞァ-ヺー０-９Ａ-Ｚａ-ｚｦ-ﾝ\p{Radical}\p{Unified_Ideograph}]+/gimu,
          ttuRegex: /[0-9A-Za-z○◯々-〇〻ぁ-ゖゝ-ゞァ-ヺー０-９Ａ-Ｚａ-ｚｦ-ﾝ\p{Radical}\p{Unified_Ideograph}]/iu,
          nodeStartOffsets: new WeakMap(),
          isVertical: function() {
            return window.getComputedStyle(document.body).writingMode === "vertical-rl";
          },
          isFurigana: function(node) {
            var el = node.nodeType === Node.TEXT_NODE ? node.parentElement : node;
            return !!(el && el.closest('rt, rp'));
          },
          normalizeText: function(text) {
            return (text || '').replace(this.ttuRegexNegated, '');
          },
          countChars: function(text) {
            return Array.from(this.normalizeText(text)).length;
          },
          isMatchableChar: function(char) {
            return this.ttuRegex.test(char || '');
          },
          notifyRestoreComplete: function() {
            if (window.HoshiReaderRestore && window.HoshiReaderRestore.postMessage) {
              window.HoshiReaderRestore.postMessage('restoreCompleted');
            }
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
            var walker = this.createWalker();
            var count = 0;
            var node;
            while (node = walker.nextNode()) {
              offsets.set(node, count);
              count += this.countChars(node.textContent);
            }
            this.nodeStartOffsets = offsets;
          },
          collectSasayakiCueRanges: function(cues) {
            var cueRanges = new Map();
            if (!cues.length) return [];
            var index = 0;
            var current = cues[0];
            var start = current.start;
            var end = start + current.length;
            var cursor = 0;
            var segment = null;
            var flushSegment = function(node) {
              if (!segment) return;
              var ranges = cueRanges.get(segment.id) || [];
              ranges.push({ node: node, start: segment.start, end: segment.end });
              cueRanges.set(segment.id, ranges);
              segment = null;
            };
            var advanceCue = function() {
              index += 1;
              current = cues[index];
              if (current) {
                start = current.start;
                end = start + current.length;
              }
            };
            var walker = this.createWalker();
            var node;
            while (current && (node = walker.nextNode())) {
              var text = node.textContent;
              var i = 0;
              while (i < text.length && current) {
                var char = String.fromCodePoint(text.codePointAt(i));
                var next = i + char.length;
                if (this.isMatchableChar(char)) {
                  if (cursor >= start && cursor < end) {
                    if (!segment) {
                      segment = { id: current.id, start: i, end: next };
                    } else {
                      segment.end = next;
                    }
                  } else {
                    flushSegment(node);
                  }
                  cursor += 1;
                  if (cursor === end) {
                    flushSegment(node);
                    advanceCue();
                  }
                } else if (segment) {
                  segment.end = next;
                }
                i = next;
              }
              flushSegment(node);
            }
            return cues.map(function(cue) {
              return { id: cue.id, ranges: cueRanges.get(cue.id) || [] };
            });
          },
          applySasayakiCues: function(cues) {
            this.resetSasayakiCues();
            var cueRanges = this.collectSasayakiCueRanges(cues);
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
            }
            this.buildNodeOffsets();
          },
          highlightSasayakiCue: function(cueId, reveal) {
            this.clearSasayakiCue();
            var wrappers = this.cueWrappers.get(cueId);
            if (!wrappers || !wrappers.length) return null;
            this.activeCueId = cueId;
            wrappers.forEach(function(wrapper) { wrapper.classList.add('hoshi-sasayaki-active'); });
            if (reveal) {
              var range = document.createRange();
              range.selectNodeContents(wrappers[0]);
              if (this.scrollToRange(range)) {
                return this.calculateProgress();
              }
            }
            return null;
          },
          clearSasayakiCue: function() {
            if (!this.activeCueId) return;
            var wrappers = this.cueWrappers.get(this.activeCueId) || [];
            wrappers.forEach(function(wrapper) { wrapper.classList.remove('hoshi-sasayaki-active'); });
            this.activeCueId = null;
          },
          resetSasayakiCues: function() {
            var self = this;
            this.cueWrappers.forEach(function(wrappers) { self.unwrap(wrappers); });
            this.cueWrappers.clear();
            this.activeCueId = null;
          },
          unwrap: function(wrappers) {
            wrappers.forEach(function(wrapper) {
              var parent = wrapper.parentNode;
              if (!parent) return;
              while (wrapper.firstChild) {
                parent.insertBefore(wrapper.firstChild, wrapper);
              }
              parent.removeChild(wrapper);
              parent.normalize();
            });
          },
          getScrollContext: function() {
            var vertical = this.isVertical();
            var scrollEl = document.body;
            var pageSize = Math.max(1, vertical ? (this.pageHeight || window.innerHeight) : (this.pageWidth || window.innerWidth));
            var totalSize = vertical ? scrollEl.scrollHeight : scrollEl.scrollWidth;
            var maxScroll = Math.max(0, totalSize - pageSize);
            return { vertical: vertical, scrollEl: scrollEl, pageSize: pageSize, maxScroll: maxScroll };
          },
          getPagePosition: function(context) {
            return context.vertical ? context.scrollEl.scrollTop : context.scrollEl.scrollLeft;
          },
          setPagePosition: function(context, position) {
            var clamped = Math.min(Math.max(0, position), context.maxScroll);
            if (context.vertical) {
              context.scrollEl.scrollTop = clamped;
            } else {
              context.scrollEl.scrollLeft = clamped;
            }
            return clamped;
          },
          alignToPage: function(context, offset) {
            return Math.floor(Math.max(0, offset) / context.pageSize) * context.pageSize;
          },
          alignContentStartToPage: function(context, offset) {
            var safeOffset = Math.max(0, offset);
            var nearestPage = Math.round(safeOffset / context.pageSize) * context.pageSize;
            if (Math.abs(safeOffset - nearestPage) < 1) {
              return nearestPage;
            }
            return this.alignToPage(context, safeOffset);
          },
          scrollToRange: function(range) {
            var context = this.getScrollContext();
            if (context.pageSize <= 0) return false;
            var rect = this.getRect(range);
            var currentScroll = this.getPagePosition(context);
            var anchor = (context.vertical ? (rect.top + rect.bottom) / 2 : (rect.left + rect.right) / 2) + currentScroll;
            var targetScroll = this.alignToPage(context, anchor);
            if (targetScroll === currentScroll) return false;
            this.setPagePosition(context, targetScroll);
            var self = this;
            requestAnimationFrame(function() {
              self.setPagePosition(context, targetScroll);
            });
            return true;
          },
          contentLastPageScroll: function(context) {
            var currentScroll = this.getPagePosition(context);
            var lastContentEdge = 0;
            var walker = this.createWalker();
            var node;
            while (node = walker.nextNode()) {
              if (this.countChars(node.textContent) <= 0) continue;
              var range = document.createRange();
              range.selectNodeContents(node);
              var rects = range.getClientRects();
              for (var i = 0; i < rects.length; i++) {
                var rect = rects[i];
                if (rect.width <= 0 || rect.height <= 0) continue;
                var edge = (context.vertical ? rect.bottom : rect.right) + currentScroll;
                lastContentEdge = Math.max(lastContentEdge, edge);
              }
            }

            var media = document.querySelectorAll('img, svg, image, video, canvas');
            for (var j = 0; j < media.length; j++) {
              var mediaRect = media[j].getBoundingClientRect();
              if (mediaRect.width <= 0 || mediaRect.height <= 0) continue;
              var mediaEdge = (context.vertical ? mediaRect.bottom : mediaRect.right) + currentScroll;
              lastContentEdge = Math.max(lastContentEdge, mediaEdge);
            }

            if (lastContentEdge <= 0) return 0;
            var lastContentScroll = Math.floor(Math.max(0, lastContentEdge - 1) / context.pageSize) * context.pageSize;
            var maxAlignedScroll = Math.floor(context.maxScroll / context.pageSize) * context.pageSize;
            return Math.min(maxAlignedScroll, lastContentScroll);
          },
          contentFirstPageScroll: function(context) {
            var currentScroll = this.getPagePosition(context);
            var firstContentEdge = null;
            var walker = this.createWalker();
            var node;
            while (node = walker.nextNode()) {
              if (this.countChars(node.textContent) <= 0) continue;
              var range = document.createRange();
              range.selectNodeContents(node);
              var rects = range.getClientRects();
              for (var i = 0; i < rects.length; i++) {
                var rect = rects[i];
                if (rect.width <= 0 || rect.height <= 0) continue;
                var edge = (context.vertical ? rect.top : rect.left) + currentScroll;
                firstContentEdge = firstContentEdge === null ? edge : Math.min(firstContentEdge, edge);
              }
            }

            var media = document.querySelectorAll('img, svg, image, video, canvas');
            for (var j = 0; j < media.length; j++) {
              var mediaRect = media[j].getBoundingClientRect();
              if (mediaRect.width <= 0 || mediaRect.height <= 0) continue;
              var mediaEdge = (context.vertical ? mediaRect.top : mediaRect.left) + currentScroll;
              firstContentEdge = firstContentEdge === null ? mediaEdge : Math.min(firstContentEdge, mediaEdge);
            }

            if (firstContentEdge === null) return 0;
            var maxAlignedScroll = Math.floor(context.maxScroll / context.pageSize) * context.pageSize;
            var firstContentScroll = this.alignContentStartToPage(context, firstContentEdge);
            return Math.min(maxAlignedScroll, firstContentScroll);
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
              if (nodeLen > 0) {
                var range = document.createRange();
                range.selectNodeContents(node);
                var rect = this.getRect(range);
                if ((vertical ? rect.top : rect.left) < 0) {
                  exploredChars += nodeLen;
                }
              }
            }
            return totalChars > 0 ? exploredChars / totalChars : 0;
          },
          restoreProgress: async function(progress) {
            await document.fonts.ready;
            var context = this.getScrollContext();
            if (context.pageSize <= 0 || progress <= 0) {
              var firstPage = this.contentFirstPageScroll(context);
              this.setPagePosition(context, firstPage);
              this.notifyRestoreComplete();
              return;
            }
            if (progress >= 0.99) {
              var lastPage = this.contentLastPageScroll(context);
              lastPage = Math.max(0, lastPage);
              this.setPagePosition(context, lastPage);
              requestAnimationFrame(() => {
                this.setPagePosition(context, lastPage);
                requestAnimationFrame(() => this.notifyRestoreComplete());
              });
              return;
            }
            var walker = this.createWalker();
            var totalChars = 0;
            var node;
            while (node = walker.nextNode()) {
              totalChars += this.countChars(node.textContent);
            }
            var targetCharCount = Math.ceil(totalChars * progress);
            var runningSum = 0;
            var targetNode = null;
            walker = this.createWalker();
            while (node = walker.nextNode()) {
              runningSum += this.countChars(node.textContent);
              if (runningSum > targetCharCount) {
                targetNode = node;
                break;
              }
            }
            if (targetNode) {
              var range = document.createRange();
              range.setStart(targetNode, 0);
              range.setEnd(targetNode, 1);
              var rect = this.getRect(range);
              var currentScroll = this.getPagePosition(context);
              var anchor = (context.vertical ? rect.top : rect.left) + currentScroll;
              var targetScroll = this.alignToPage(context, anchor);
              this.setPagePosition(context, targetScroll);
              requestAnimationFrame(() => {
                this.setPagePosition(context, targetScroll);
              });
            } else {
              var firstPage = this.contentFirstPageScroll(context);
              this.setPagePosition(context, firstPage);
            }
            requestAnimationFrame(() => {
              requestAnimationFrame(() => this.notifyRestoreComplete());
            });
          },
          paginate: function(direction) {
            var context = this.getScrollContext();
            if (context.pageSize <= 0) return "limit";
            var currentScroll = this.getPagePosition(context);
            var minAlignedScroll = this.contentFirstPageScroll(context);
            var maxAlignedScroll = this.contentLastPageScroll(context);
            if (direction === "forward") {
              if ((currentScroll + context.pageSize) <= (maxAlignedScroll + 1)) {
                var targetForward = Math.round((currentScroll + context.pageSize) / context.pageSize) * context.pageSize;
                this.setPagePosition(context, targetForward);
                return "scrolled";
              }
              return "limit";
            } else {
              if (currentScroll > (minAlignedScroll + 1)) {
                var targetBack = Math.round((currentScroll - context.pageSize) / context.pageSize) * context.pageSize;
                targetBack = Math.max(minAlignedScroll, targetBack);
                this.setPagePosition(context, targetBack);
                return "scrolled";
              }
              return "limit";
            }
          }
        };
        window.hoshiReader.initialize = function() {
          if (window.hoshiReader.didInitialize) return;
          window.hoshiReader.didInitialize = true;
          var viewport = document.querySelector('meta[name="viewport"]');
          if (viewport) { viewport.remove(); }
          var newViewport = document.createElement('meta');
          newViewport.name = 'viewport';
          newViewport.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
          document.head.appendChild(newViewport);
          var pageHeight = window.innerHeight + ${settings.bottomOverlapPx};
          var pageWidth = window.innerWidth;
          document.documentElement.style.setProperty('--page-height', pageHeight + 'px');
          document.documentElement.style.setProperty('--page-width', pageWidth + 'px');
          document.documentElement.style.setProperty('--hoshi-image-max-width', Math.max(1, Math.floor(pageWidth * ${settings.imageWidthViewportRatio})) + 'px');
          document.documentElement.style.setProperty('--hoshi-image-max-height', Math.max(1, pageHeight - ${settings.bottomOverlapPx}) + 'px');
          window.hoshiReader.pageHeight = pageHeight;
          window.hoshiReader.pageWidth = pageWidth;
          Array.from(document.querySelectorAll('svg')).forEach(function(svg) {
            if (svg.querySelector('image') && svg.getAttribute('preserveAspectRatio') === 'none') {
              svg.setAttribute('preserveAspectRatio', 'xMidYMid meet');
            }
          });
          var imagePromises = Array.from(document.querySelectorAll('img')).map(function(img) {
            return new Promise(function(resolve) {
              var isGaiji = img.classList.contains('gaiji') || img.classList.contains('gaiji-line');
              var mark = function() {
                if (!isGaiji && (img.naturalWidth > 256 || img.naturalHeight > 256)) {
                  img.classList.add('block-img');
                }
                resolve();
              };
              if (img.complete && img.naturalWidth > 0) {
                mark();
              } else {
                img.onload = mark;
                img.onerror = function() { resolve(); };
              }
            });
          });
          var spacer = document.createElement('div');
          spacer.style.height = '${settings.trailingSpacerHeightCss}';
          spacer.style.width = '${settings.trailingSpacerWidthCss}';
          spacer.style.display = 'block';
          spacer.style.breakInside = 'avoid';
          document.body.appendChild(spacer);
          Promise.all(imagePromises).then(function() {
            return new Promise(function(resolve) { setTimeout(resolve, 50); });
          }).then(function() {
            window.hoshiReader.buildNodeOffsets();
            ${sasayakiCuesJson?.let { "window.hoshiReader.applySasayakiCues($it);" }.orEmpty()}
            window.hoshiReader.restoreProgress($initialProgress);
          });
        };
        window.addEventListener('load', function() {
          window.hoshiReader.initialize();
        });
        if (document.readyState === 'complete') {
          window.hoshiReader.initialize();
        }
        </script>
    """.trimIndent()
}

private fun String.javaScriptStringLiteral(): String =
    buildString(length + 2) {
        append('"')
        this@javaScriptStringLiteral.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }
