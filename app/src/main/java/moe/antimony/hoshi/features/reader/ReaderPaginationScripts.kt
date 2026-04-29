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

    fun didScroll(result: String?): Boolean =
        result?.trim()?.trim('"') == "scrolled"

    fun doubleResult(result: String?): Double? =
        result?.trim()?.trim('"')?.toDoubleOrNull()

    fun shellScript(
        initialProgress: Double = 0.0,
        settings: ReaderSettings = ReaderSettings(),
    ): String = """
        <script>
        window.hoshiReader = {
          pageHeight: 0,
          pageWidth: 0,
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
          restoreProgress: function(progress) {
            var context = this.getScrollContext();
            if (context.pageSize <= 0 || progress <= 0) {
              this.setPagePosition(context, 0);
              this.notifyRestoreComplete();
              return;
            }
            if (progress >= 0.99) {
              var lastPage = Math.floor(context.maxScroll / context.pageSize) * context.pageSize;
              this.setPagePosition(context, Math.max(0, lastPage));
              this.notifyRestoreComplete();
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
              range.setEnd(targetNode, Math.min(1, targetNode.textContent.length));
              var rect = this.getRect(range);
              var currentScroll = this.getPagePosition(context);
              var anchor = (context.vertical ? rect.top : rect.left) + currentScroll;
              var targetScroll = this.alignToPage(context, anchor);
              this.setPagePosition(context, targetScroll);
            } else {
              this.setPagePosition(context, 0);
            }
            this.notifyRestoreComplete();
          },
          paginate: function(direction) {
            var context = this.getScrollContext();
            if (context.pageSize <= 0) return "limit";
            var currentScroll = this.getPagePosition(context);
            var maxAlignedScroll = Math.floor(context.maxScroll / context.pageSize) * context.pageSize;
            if (direction === "forward") {
              if ((currentScroll + context.pageSize) <= (maxAlignedScroll + 1)) {
                var targetForward = Math.round((currentScroll + context.pageSize) / context.pageSize) * context.pageSize;
                this.setPagePosition(context, targetForward);
                return "scrolled";
              }
              return "limit";
            } else {
              if (currentScroll > 0) {
                var targetBack = Math.round((currentScroll - context.pageSize) / context.pageSize) * context.pageSize;
                this.setPagePosition(context, targetBack);
                return "scrolled";
              }
              return "limit";
            }
          }
        };
        window.addEventListener('load', function() {
          var viewport = document.querySelector('meta[name="viewport"]');
          if (viewport) { viewport.remove(); }
          var newViewport = document.createElement('meta');
          newViewport.name = 'viewport';
          newViewport.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
          document.head.appendChild(newViewport);
          var pageHeight = window.innerHeight + ${settings.bottomOverlapPx};
          var pageWidth = window.innerWidth;
          var contentHeight = Math.max(1, window.innerHeight - ${settings.horizontalGlyphGuardPx});
          document.documentElement.style.setProperty('--page-height', pageHeight + 'px');
          document.documentElement.style.setProperty('--page-width', pageWidth + 'px');
          document.documentElement.style.setProperty('--content-height', contentHeight + 'px');
          document.documentElement.style.setProperty('--hoshi-image-max-width', Math.max(1, Math.floor(pageWidth * ${settings.imageWidthViewportRatio})) + 'px');
          document.documentElement.style.setProperty('--hoshi-image-max-height', Math.max(1, pageHeight - ${settings.bottomOverlapPx}) + 'px');
          window.hoshiReader.pageHeight = pageHeight;
          window.hoshiReader.pageWidth = pageWidth;
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
            window.hoshiReader.restoreProgress($initialProgress);
          });
        });
        </script>
    """.trimIndent()
}
