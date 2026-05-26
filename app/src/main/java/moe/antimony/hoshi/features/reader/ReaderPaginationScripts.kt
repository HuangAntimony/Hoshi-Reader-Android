package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.features.sasayaki.SasayakiCueRange

internal enum class ReaderNavigationDirection(val jsValue: String) {
    Forward("forward"),
    Backward("backward"),
}

internal object ReaderPaginationScripts {
    fun paginateInvocation(direction: ReaderNavigationDirection): String =
        "window.hoshiReader.paginate('${direction.jsValue}')"

    fun nativeSelectionActiveInvocation(active: Boolean): String =
        "if (window.hoshiReader && typeof window.hoshiReader.setNativeSelectionActive === 'function') { window.hoshiReader.setNativeSelectionActive($active); }"

    fun progressInvocation(): String =
        "window.hoshiReader.calculateProgress()"

    fun applySasayakiCuesInvocation(cuesJson: String): String =
        "window.hoshiReader.applySasayakiCues($cuesJson)"

    fun highlightSasayakiCueInvocation(cue: SasayakiCueRange, reveal: Boolean): String =
        "window.hoshiReader.highlightSasayakiCue(${cue.toJavaScriptObjectLiteral()}, $reveal)"

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
        highlightsJson: String? = null,
        initialFragment: String? = null,
    ): String {
        if (settings.continuousMode) {
            return continuousShellScript(
                initialProgress = initialProgress,
                settings = settings,
                sasayakiCuesJson = sasayakiCuesJson,
                highlightsJson = highlightsJson,
                initialFragment = initialFragment,
            )
        }
        val initialRestoreScript = initialFragment?.let { fragment ->
            "window.hoshiReader.jumpToFragment(${fragment.javaScriptStringLiteral()});"
        } ?: "window.hoshiReader.restoreProgress($initialProgress);"
        val generatedLayout = ReaderGeneratedLayout.from(settings)
        return """
        <script>
        window.hoshiReader = {
          pageHeight: 0,
          pageWidth: 0,
          nativeSelectionActive: false,
          nativeSelectionScrollPosition: null,
          cueWrappers: new Map(),
          activeCueId: null,
          ttuRegexNegated: /[^0-9A-Za-z○◯々-〇〻ぁ-ゖゝ-ゞァ-ヺー０-９Ａ-Ｚａ-ｚｦ-ﾝ\p{Radical}\p{Unified_Ideograph}]+/gimu,
          ttuRegex: /[0-9A-Za-z○◯々-〇〻ぁ-ゖゝ-ゞァ-ヺー０-９Ａ-Ｚａ-ｚｦ-ﾝ\p{Radical}\p{Unified_Ideograph}]/iu,
          nodeStartOffsets: new WeakMap(),
          nodeStartRawOffsets: new WeakMap(),
          paginationMetrics: null,
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
          notifyRestoreComplete: function() {
            if (window.HoshiReaderRestore && window.HoshiReaderRestore.postMessage) {
              window.HoshiReaderRestore.postMessage('restoreCompleted');
            }
            this.warmPaginationMetrics();
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
            var rawOffsets = new WeakMap();
            var walker = this.createWalker();
            var count = 0;
            var rawCount = 0;
            var node;
            while (node = walker.nextNode()) {
              offsets.set(node, count);
              rawOffsets.set(node, rawCount);
              count += this.countChars(node.textContent);
              rawCount += this.countRawChars(node.textContent);
            }
            this.nodeStartOffsets = offsets;
            this.nodeStartRawOffsets = rawOffsets;
            this.paginationMetrics = null;
          },
          countCharsBeforeViewport: function(node, context) {
            var text = node.textContent || '';
            var totalChars = this.countChars(text);
            if (totalChars <= 0) return 0;
            var range = document.createRange();
            range.selectNodeContents(node);
            var rects = range.getClientRects();
            if (!rects.length) return 0;
            var minStart = Infinity;
            var maxEnd = -Infinity;
            for (var i = 0; i < rects.length; i++) {
              var rect = rects[i];
              if (rect.width <= 0 || rect.height <= 0) continue;
              var start = context.vertical ? rect.top : rect.left;
              var end = context.vertical ? rect.bottom : rect.right;
              minStart = Math.min(minStart, start);
              maxEnd = Math.max(maxEnd, end);
            }
            if (maxEnd <= 0) return totalChars;
            if (minStart >= 0 || minStart === Infinity) return 0;
            var offsets = [];
            var prefixCounts = [0];
            var count = 0;
            var offset = 0;
            while (offset < text.length) {
              offsets.push(offset);
              var char = String.fromCodePoint(text.codePointAt(offset));
              offset += char.length;
              if (this.isMatchableChar(char)) count += 1;
              prefixCounts.push(count);
            }
            var low = 0;
            var high = offsets.length - 1;
            var firstVisible = offsets.length;
            while (low <= high) {
              var mid = Math.floor((low + high) / 2);
              if (this.isTextOffsetBeforeViewport(node, offsets[mid], text, context)) {
                low = mid + 1;
              } else {
                firstVisible = mid;
                high = mid - 1;
              }
            }
            return prefixCounts[firstVisible];
          },
          isTextOffsetBeforeViewport: function(node, offset, text, context) {
            var char = String.fromCodePoint(text.codePointAt(offset));
            if (!char) return false;
            var range = document.createRange();
            range.setStart(node, offset);
            range.setEnd(node, offset + char.length);
            var rect = this.getRect(range);
            if (!rect || rect.width <= 0 || rect.height <= 0) return false;
            return (context.vertical ? rect.bottom : rect.right) <= 0;
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
            this.wrapSasayakiCueRanges(cueRanges);
            this.buildNodeOffsets();
          },
          wrapSasayakiCue: function(cue) {
            var existing = this.cueWrappers.get(cue.id);
            if (existing && existing.length) return existing;
            var cueRanges = this.collectSasayakiCueRanges([cue]);
            var wrapped = this.wrapSasayakiCueRanges(cueRanges);
            this.buildNodeOffsets();
            return wrapped.get(cue.id) || [];
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
          highlightSasayakiCue: function(cue, reveal) {
            this.clearSasayakiCue();
            var cueId = typeof cue === 'string' ? cue : cue.id;
            var wrappers = this.cueWrappers.get(cueId);
            if ((!wrappers || !wrappers.length) && typeof cue !== 'string') {
              wrappers = this.wrapSasayakiCue(cue);
            }
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
          lockRootViewport: function() {
            var root = document.documentElement;
            var didScroll = false;
            if (root.scrollTop !== 0) {
              root.scrollTop = 0;
              didScroll = true;
            }
            if (root.scrollLeft !== 0) {
              root.scrollLeft = 0;
              didScroll = true;
            }
            if (window.scrollX !== 0 || window.scrollY !== 0) {
              window.scrollTo(0, 0);
              didScroll = true;
            }
            return didScroll;
          },
          assignPagePosition: function(context, position) {
            if (context.vertical) {
              context.scrollEl.scrollTop = position;
            } else {
              context.scrollEl.scrollLeft = position;
            }
            this.lockRootViewport();
          },
          setPagePosition: function(context, position) {
            var clamped = Math.min(Math.max(0, position), context.maxScroll);
            window.lastPageScroll = clamped;
            this.assignPagePosition(context, clamped);
            return clamped;
          },
          setNativeSelectionActive: function(active) {
            var context = this.getScrollContext();
            if (active) {
              this.nativeSelectionActive = true;
              this.nativeSelectionScrollPosition = this.getPagePosition(context);
              window.lastPageScroll = this.nativeSelectionScrollPosition;
              return;
            }
            if (this.nativeSelectionActive && this.nativeSelectionScrollPosition !== null && this.nativeSelectionScrollPosition !== undefined) {
              var lockedScroll = Math.min(Math.max(0, this.nativeSelectionScrollPosition), context.maxScroll);
              this.assignPagePosition(context, lockedScroll);
              window.lastPageScroll = lockedScroll;
            }
            this.nativeSelectionActive = false;
            this.nativeSelectionScrollPosition = null;
          },
          handlePagedBodyScroll: function() {
            this.lockRootViewport();
            var context = this.getScrollContext();
            if (context.pageSize <= 0) return;
            var currentScroll = this.getPagePosition(context);
            if (this.nativeSelectionActive) {
              var lockedScroll = this.nativeSelectionScrollPosition;
              if (lockedScroll === null || lockedScroll === undefined) {
                lockedScroll = window.lastPageScroll || 0;
              }
              lockedScroll = Math.min(Math.max(0, lockedScroll), context.maxScroll);
              if (Math.abs(currentScroll - lockedScroll) > 0.5) {
                this.assignPagePosition(context, lockedScroll);
              }
              window.lastPageScroll = lockedScroll;
              return;
            }
            var snappedScroll = Math.round(currentScroll / context.pageSize) * context.pageSize;
            snappedScroll = Math.min(Math.max(0, snappedScroll), context.maxScroll);
            if (Math.abs(currentScroll - snappedScroll) > 1) {
              this.assignPagePosition(context, window.lastPageScroll || 0);
            } else {
              window.lastPageScroll = snappedScroll;
            }
          },
          registerSnapScroll: function(initialScroll) {
            if (window.snapScrollRegistered) return;
            window.snapScrollRegistered = true;
            window.lastPageScroll = initialScroll;
            this.lockRootViewport();
            window.addEventListener('scroll', () => {
              if (this.lockRootViewport()) {
                requestAnimationFrame(() => this.lockRootViewport());
              }
            }, { passive: true });
            document.body.addEventListener('scroll', () => {
              this.handlePagedBodyScroll();
            }, { passive: true });
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
            var metrics = this.paginationMetrics || this.buildPaginationMetrics();
            return metrics.maxScroll;
          },
          contentFirstPageScroll: function(context) {
            var metrics = this.paginationMetrics || this.buildPaginationMetrics();
            return metrics.minScroll;
          },
          warmPaginationMetrics: function() {
            if (this.paginationMetrics) return;
            var run = () => {
              if (this.paginationMetrics) return;
              this.buildPaginationMetrics();
            };
            if (window.requestIdleCallback) {
              window.requestIdleCallback(run, { timeout: 1000 });
            } else {
              setTimeout(run, 200);
            }
          },
          buildPaginationMetrics: function() {
            var context = this.getScrollContext();
            var currentScroll = this.getPagePosition(context);
            var maxAlignedScroll = Math.floor(context.maxScroll / context.pageSize) * context.pageSize;
            if (context.pageSize <= 0) {
              var emptyMetrics = { minScroll: 0, maxScroll: 0, totalChars: 0, progressStops: [] };
              this.paginationMetrics = emptyMetrics;
              return emptyMetrics;
            }
            var lastContentEdge = 0;
            var firstContentEdge = null;
            var progressStops = [];
            var exploredChars = 0;
            var totalChars = 0;
            var walker = this.createWalker();
            var node;
            while (node = walker.nextNode()) {
              var nodeLen = this.countChars(node.textContent);
              totalChars += nodeLen;
              if (nodeLen <= 0) continue;
              var range = document.createRange();
              range.selectNodeContents(node);
              var rects = range.getClientRects();
              var progressRect = this.getRect(range);
              var nodeStartEdge = progressRect && progressRect.width > 0 && progressRect.height > 0
                ? (context.vertical ? progressRect.top : progressRect.left) + currentScroll
                : null;
              for (var i = 0; i < rects.length; i++) {
                var rect = rects[i];
                if (rect.width <= 0 || rect.height <= 0) continue;
                var startEdge = (context.vertical ? rect.top : rect.left) + currentScroll;
                var endEdge = (context.vertical ? rect.bottom : rect.right) + currentScroll;
                firstContentEdge = firstContentEdge === null ? startEdge : Math.min(firstContentEdge, startEdge);
                lastContentEdge = Math.max(lastContentEdge, endEdge);
              }
              if (nodeStartEdge !== null) {
                progressStops.push({ scroll: nodeStartEdge, exploredChars: exploredChars + nodeLen });
              }
              exploredChars += nodeLen;
            }

            var media = document.querySelectorAll('img, svg, image, video, canvas');
            for (var j = 0; j < media.length; j++) {
              var mediaRect = media[j].getBoundingClientRect();
              if (mediaRect.width <= 0 || mediaRect.height <= 0) continue;
              var mediaStart = (context.vertical ? mediaRect.top : mediaRect.left) + currentScroll;
              var mediaEnd = (context.vertical ? mediaRect.bottom : mediaRect.right) + currentScroll;
              firstContentEdge = firstContentEdge === null ? mediaStart : Math.min(firstContentEdge, mediaStart);
              lastContentEdge = Math.max(lastContentEdge, mediaEnd);
            }

            var minScroll = firstContentEdge === null ? 0 : Math.min(maxAlignedScroll, this.alignContentStartToPage(context, firstContentEdge));
            var lastContentScroll = lastContentEdge <= 0 ? 0 : Math.floor(Math.max(0, lastContentEdge - 1) / context.pageSize) * context.pageSize;
            var maxScroll = Math.min(maxAlignedScroll, lastContentScroll);
            progressStops.sort(function(a, b) { return a.scroll - b.scroll; });
            var metrics = {
              minScroll: minScroll,
              maxScroll: maxScroll,
              totalChars: totalChars,
              progressStops: progressStops
            };
            this.paginationMetrics = metrics;
            return metrics;
          },
          calculateProgress: function() {
            var context = this.getScrollContext();
            var walker = this.createWalker();
            var totalChars = 0;
            var exploredChars = 0;
            var node;
            while (node = walker.nextNode()) {
              var nodeLen = this.countChars(node.textContent);
              totalChars += nodeLen;
              if (nodeLen > 0) exploredChars += this.countCharsBeforeViewport(node, context);
            }
            return totalChars > 0 ? exploredChars / totalChars : 0;
          },
          restoreProgress: async function(progress) {
            await document.fonts.ready;
            var context = this.getScrollContext();
            if (context.pageSize <= 0 || progress <= 0) {
              var firstPage = this.contentFirstPageScroll(context);
              this.setPagePosition(context, firstPage);
              this.registerSnapScroll(firstPage);
              this.notifyRestoreComplete();
              return;
            }
            if (progress >= 0.99) {
              var lastPage = this.contentLastPageScroll(context);
              lastPage = Math.max(0, lastPage);
              this.setPagePosition(context, lastPage);
              requestAnimationFrame(() => {
                this.setPagePosition(context, lastPage);
                this.registerSnapScroll(lastPage);
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
            var targetOffset = 0;
            walker = this.createWalker();
            while (node = walker.nextNode()) {
              var nodeLen = this.countChars(node.textContent);
              if ((runningSum + nodeLen) > targetCharCount) {
                targetNode = node;
                targetOffset = this.textOffsetForCharCount(node, Math.max(0, targetCharCount - runningSum));
                break;
              }
              runningSum += nodeLen;
            }
            if (targetNode) {
              var range = document.createRange();
              var targetText = targetNode.textContent || '';
              var targetChar = String.fromCodePoint(targetText.codePointAt(targetOffset));
              range.setStart(targetNode, targetOffset);
              range.setEnd(targetNode, Math.min(targetText.length, targetOffset + Math.max(1, targetChar.length)));
              var rect = this.getRect(range);
              var currentScroll = this.getPagePosition(context);
              var anchor = (context.vertical ? rect.top : rect.left) + currentScroll;
              var targetScroll = this.alignToPage(context, anchor);
              this.setPagePosition(context, targetScroll);
              requestAnimationFrame(() => {
                this.setPagePosition(context, targetScroll);
                this.registerSnapScroll(targetScroll);
              });
            } else {
              var firstPage = this.contentFirstPageScroll(context);
              this.setPagePosition(context, firstPage);
              this.registerSnapScroll(firstPage);
            }
            requestAnimationFrame(() => {
              requestAnimationFrame(() => this.notifyRestoreComplete());
            });
          },
          jumpToFragment: async function(fragment) {
            await document.fonts.ready;
            var context = this.getScrollContext();
            var rawFragment = (fragment || '').trim();
            var target = rawFragment && (document.getElementById(rawFragment) || document.getElementsByName(rawFragment)[0]);
            if (context.pageSize <= 0 || !target) {
              this.registerSnapScroll(this.getPagePosition(context));
              this.notifyRestoreComplete();
              return false;
            }
            var rect = this.getRect(target);
            var currentScroll = this.getPagePosition(context);
            var anchor = (context.vertical ? rect.top : rect.left) + currentScroll;
            var targetScroll = this.alignToPage(context, anchor);
            this.setPagePosition(context, targetScroll);
            requestAnimationFrame(() => {
              this.setPagePosition(context, targetScroll);
              this.registerSnapScroll(targetScroll);
              requestAnimationFrame(() => this.notifyRestoreComplete());
            });
            return true;
          },
          paginate: function(direction) {
            if (this.nativeSelectionActive) return "limit";
            var context = this.getScrollContext();
            if (context.pageSize <= 0) return "limit";
            var currentScroll = this.getPagePosition(context);
            var metrics = this.paginationMetrics || this.buildPaginationMetrics();
            var minAlignedScroll = metrics.minScroll;
            var maxAlignedScroll = metrics.maxScroll;
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
        ${readerHighlightsScript()}
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
          document.documentElement.style.setProperty('--hoshi-vertical-padding-block', (window.innerHeight * ${settings.verticalPadding / 200.0}) + 'px');
          document.documentElement.style.setProperty('--hoshi-vertical-padding-gap', (window.innerHeight * ${settings.verticalPadding / 100.0}) + 'px');
          document.documentElement.style.setProperty('--page-height', pageHeight + 'px');
          document.documentElement.style.setProperty('--page-width', pageWidth + 'px');
          document.documentElement.style.setProperty('--hoshi-image-max-width', Math.max(1, Math.floor(pageWidth * ${generatedLayout.imageWidthViewportRatio})) + 'px');
          document.documentElement.style.setProperty('--hoshi-image-max-height', Math.max(1, pageHeight - ${settings.bottomOverlapPx}) + 'px');
          window.hoshiReader.pageHeight = pageHeight;
          window.hoshiReader.pageWidth = pageWidth;
          ${readerImageTapScript(settings)}
          var svgImages = Array.from(document.querySelectorAll('svg image'));
          svgImages.forEach(function(svgImage) {
            var svg = svgImage.closest('svg');
            if (!svg) return;
            if (svg.getAttribute('preserveAspectRatio') === 'none') {
              svg.setAttribute('preserveAspectRatio', 'xMidYMid meet');
            }
            var svgImageSrc = svgImage.href && svgImage.href.baseVal ? svgImage.href.baseVal : (svgImage.getAttribute('href') || svgImage.getAttribute('xlink:href'));
            setupReaderImage(svgImage, svgImageSrc, false, svg);
          });
          var images = Array.from(document.querySelectorAll('img'));
          var imagePromises = images.map(function(img) {
            return new Promise(function(resolve) {
              var isGaiji = img.classList.contains('gaiji') || img.classList.contains('gaiji-line');
              var mark = function() {
                if (!isGaiji && (img.naturalWidth > 256 || img.naturalHeight > 256)) {
                  img.classList.add('block-img');
                  setupReaderImage(img, img.currentSrc || img.src, true);
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
            if (!images.length) return;
            return new Promise(function(resolve) { setTimeout(resolve, 50); });
          }).then(function() {
            window.hoshiReader.buildNodeOffsets();
            ${sasayakiCuesJson?.let { "window.hoshiReader.applySasayakiCues($it);" }.orEmpty()}
            ${highlightsJson?.let { "window.hoshiHighlights.applyHighlights($it);" }.orEmpty()}
            $initialRestoreScript
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

    private fun continuousShellScript(
        initialProgress: Double,
        settings: ReaderSettings,
        sasayakiCuesJson: String?,
        highlightsJson: String?,
        initialFragment: String?,
    ): String {
        val initialRestoreScript = initialFragment?.let { fragment ->
            "window.hoshiReader.jumpToFragment(${fragment.javaScriptStringLiteral()});"
        } ?: "window.hoshiReader.restoreProgress($initialProgress);"
        val generatedLayout = ReaderGeneratedLayout.from(settings)
        return """
        <script>
        window.hoshiReader = {
          cueWrappers: new Map(),
          activeCueId: null,
          ttuRegexNegated: /[^0-9A-Za-z○◯々-〇〻ぁ-ゖゝ-ゞァ-ヺー０-９Ａ-Ｚａ-ｚｦ-ﾝ\p{Radical}\p{Unified_Ideograph}]+/gimu,
          ttuRegex: /[0-9A-Za-z○◯々-〇〻ぁ-ゖゝ-ゞァ-ヺー０-９Ａ-Ｚａ-ｚｦ-ﾝ\p{Radical}\p{Unified_Ideograph}]/iu,
          nodeStartOffsets: new WeakMap(),
          nodeStartRawOffsets: new WeakMap(),
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
          notifyRestoreComplete: function() {
            if (window.HoshiReaderRestore && window.HoshiReaderRestore.postMessage) {
              window.HoshiReaderRestore.postMessage('restoreCompleted');
            }
          },
          scrollToChapterStart: function() {
            var root = document.scrollingElement || document.documentElement;
            window.scrollTo(0, 0);
            root.scrollTop = 0;
            root.scrollLeft = 0;
            document.documentElement.scrollTop = 0;
            document.documentElement.scrollLeft = 0;
            document.body.scrollTop = 0;
            document.body.scrollLeft = 0;
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
            var rawOffsets = new WeakMap();
            var walker = this.createWalker();
            var count = 0;
            var rawCount = 0;
            var node;
            while (node = walker.nextNode()) {
              offsets.set(node, count);
              rawOffsets.set(node, rawCount);
              count += this.countChars(node.textContent);
              rawCount += this.countRawChars(node.textContent);
            }
            this.nodeStartOffsets = offsets;
            this.nodeStartRawOffsets = rawOffsets;
          },
          countCharsBeforeViewport: function(node, vertical) {
            var text = node.textContent || '';
            var totalChars = this.countChars(text);
            if (totalChars <= 0) return 0;
            var range = document.createRange();
            range.selectNodeContents(node);
            var rects = range.getClientRects();
            if (!rects.length) return 0;
            var minStart = Infinity;
            var maxEnd = -Infinity;
            for (var i = 0; i < rects.length; i++) {
              var rect = rects[i];
              if (rect.width <= 0 || rect.height <= 0) continue;
              var start = vertical ? rect.left : rect.top;
              var end = vertical ? rect.right : rect.bottom;
              minStart = Math.min(minStart, start);
              maxEnd = Math.max(maxEnd, end);
            }
            if (vertical) {
              if (minStart >= window.innerWidth) return totalChars;
              if (maxEnd <= window.innerWidth || minStart === Infinity) return 0;
            } else {
              if (maxEnd <= 0) return totalChars;
              if (minStart >= 0 || minStart === Infinity) return 0;
            }
            var offsets = [];
            var prefixCounts = [0];
            var count = 0;
            var offset = 0;
            while (offset < text.length) {
              offsets.push(offset);
              var char = String.fromCodePoint(text.codePointAt(offset));
              offset += char.length;
              if (this.isMatchableChar(char)) count += 1;
              prefixCounts.push(count);
            }
            var low = 0;
            var high = offsets.length - 1;
            var firstVisible = offsets.length;
            while (low <= high) {
              var mid = Math.floor((low + high) / 2);
              if (this.isTextOffsetBeforeViewport(node, offsets[mid], text, vertical)) {
                low = mid + 1;
              } else {
                firstVisible = mid;
                high = mid - 1;
              }
            }
            return prefixCounts[firstVisible];
          },
          isTextOffsetBeforeViewport: function(node, offset, text, vertical) {
            var char = String.fromCodePoint(text.codePointAt(offset));
            if (!char) return false;
            var range = document.createRange();
            range.setStart(node, offset);
            range.setEnd(node, offset + char.length);
            var rect = this.getRect(range);
            if (!rect || rect.width <= 0 || rect.height <= 0) return false;
            return vertical ? rect.left >= window.innerWidth : rect.bottom <= 0;
          },
          scrollToTarget: function(target) {
            var rect = this.getRect(target);
            if (this.isVertical()) {
              if (rect.left >= 0 && rect.right <= window.innerWidth) return false;
            } else if (rect.top >= 0 && rect.bottom <= window.innerHeight) {
              return false;
            }
            target.scrollIntoView({ block: 'start', inline: 'nearest' });
            return true;
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
            this.wrapSasayakiCueRanges(cueRanges);
            this.buildNodeOffsets();
          },
          wrapSasayakiCue: function(cue) {
            var existing = this.cueWrappers.get(cue.id);
            if (existing && existing.length) return existing;
            var cueRanges = this.collectSasayakiCueRanges([cue]);
            var wrapped = this.wrapSasayakiCueRanges(cueRanges);
            this.buildNodeOffsets();
            return wrapped.get(cue.id) || [];
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
          highlightSasayakiCue: function(cue, reveal) {
            this.clearSasayakiCue();
            var cueId = typeof cue === 'string' ? cue : cue.id;
            var wrappers = this.cueWrappers.get(cueId);
            if ((!wrappers || !wrappers.length) && typeof cue !== 'string') {
              wrappers = this.wrapSasayakiCue(cue);
            }
            if (!wrappers || !wrappers.length) return null;
            this.activeCueId = cueId;
            wrappers.forEach(function(wrapper) { wrapper.classList.add('hoshi-sasayaki-active'); });
            if (reveal && this.scrollToTarget(wrappers[0])) {
              return this.calculateProgress();
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
          calculateProgress: function() {
            var vertical = this.isVertical();
            var walker = this.createWalker();
            var totalChars = 0;
            var exploredChars = 0;
            var node;
            while (node = walker.nextNode()) {
              var nodeLen = this.countChars(node.textContent);
              totalChars += nodeLen;
              if (nodeLen > 0) exploredChars += this.countCharsBeforeViewport(node, vertical);
            }
            return totalChars > 0 ? exploredChars / totalChars : 0;
          },
          restoreProgress: async function(progress) {
            await document.fonts.ready;
            if (progress <= 0) {
              this.scrollToChapterStart();
              requestAnimationFrame(() => {
                this.scrollToChapterStart();
                this.notifyRestoreComplete();
              });
              return;
            }
            var walker = this.createWalker();
            var totalChars = 0;
            var node;
            while (node = walker.nextNode()) {
              totalChars += this.countChars(node.textContent);
            }
            if (totalChars <= 0) {
              this.notifyRestoreComplete();
              return;
            }
            var targetCharCount = Math.ceil(totalChars * progress);
            var runningSum = 0;
            var targetNode = null;
            var targetOffset = 0;
            var lastTargetNode = null;
            walker = this.createWalker();
            while (node = walker.nextNode()) {
              var nodeLen = this.countChars(node.textContent);
              if (nodeLen > 0) lastTargetNode = node;
              if ((runningSum + nodeLen) > targetCharCount) {
                targetNode = node;
                targetOffset = this.textOffsetForCharCount(node, Math.max(0, targetCharCount - runningSum));
                break;
              }
              runningSum += nodeLen;
            }
            if (!targetNode) targetNode = lastTargetNode;
            if (targetNode) {
              if (progress >= 0.999999 && targetNode.parentElement) {
                targetNode.parentElement.scrollIntoView({
                  block: 'end',
                  inline: 'nearest',
                  behavior: 'instant'
                });
              } else {
              var range = document.createRange();
              var targetText = targetNode.textContent || '';
              var targetChar = String.fromCodePoint(targetText.codePointAt(targetOffset));
              range.setStart(targetNode, targetOffset);
              range.setEnd(targetNode, Math.min(targetText.length, targetOffset + Math.max(1, targetChar.length)));
              var marker = document.createElement('span');
              marker.style.display = 'inline-block';
              marker.style.width = '1px';
              marker.style.height = '1px';
              range.insertNode(marker);
              marker.scrollIntoView({
                block: progress >= 0.999999 ? 'end' : 'start',
                inline: 'nearest',
                behavior: 'instant'
              });
              var parent = marker.parentNode;
              marker.remove();
              if (parent) parent.normalize();
              }
            }
            requestAnimationFrame(() => {
              requestAnimationFrame(() => this.notifyRestoreComplete());
            });
          },
          jumpToFragment: async function(fragment) {
            await document.fonts.ready;
            var rawFragment = (fragment || '').trim();
            var target = rawFragment && (document.getElementById(rawFragment) || document.getElementsByName(rawFragment)[0]);
            if (!target) {
              this.notifyRestoreComplete();
              return false;
            }
            target.scrollIntoView();
            requestAnimationFrame(() => {
              requestAnimationFrame(() => this.notifyRestoreComplete());
            });
            return true;
          },
          paginate: function(direction) {
            var vertical = this.isVertical();
            var root = document.scrollingElement || document.documentElement;
            var before = vertical ? window.scrollX : root.scrollTop;
            if (vertical) {
              window.scrollBy({ left: direction === "forward" ? -window.innerWidth : window.innerWidth, behavior: "instant" });
            } else {
              window.scrollBy({ top: direction === "forward" ? window.innerHeight : -window.innerHeight, behavior: "instant" });
            }
            var after = vertical ? window.scrollX : root.scrollTop;
            return Math.abs(after - before) > 1 ? "scrolled" : "limit";
          }
        };
        ${readerHighlightsScript()}
        window.hoshiReader.initialize = function() {
          if (window.hoshiReader.didInitialize) return;
          window.hoshiReader.didInitialize = true;
          var viewport = document.querySelector('meta[name="viewport"]');
          if (viewport) { viewport.remove(); }
          var newViewport = document.createElement('meta');
          newViewport.name = 'viewport';
          newViewport.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
          document.head.appendChild(newViewport);
          document.documentElement.style.setProperty('--hoshi-vertical-padding-block', (window.innerHeight * ${settings.verticalPadding / 200.0}) + 'px');
          document.documentElement.style.setProperty('--hoshi-vertical-padding-gap', (window.innerHeight * ${settings.verticalPadding / 100.0}) + 'px');
          document.documentElement.style.setProperty('--hoshi-continuous-height', window.innerHeight + 'px');
          document.documentElement.style.setProperty('--hoshi-image-max-width', Math.max(1, Math.floor(window.innerWidth * ${generatedLayout.imageWidthViewportRatio})) + 'px');
          document.documentElement.style.setProperty('--hoshi-image-max-height', Math.max(1, window.innerHeight - ${settings.bottomOverlapPx}) + 'px');
          ${readerImageTapScript(settings)}
          var svgImages = Array.from(document.querySelectorAll('svg image'));
          svgImages.forEach(function(svgImage) {
            var svg = svgImage.closest('svg');
            if (!svg) return;
            if (svg.getAttribute('preserveAspectRatio') === 'none') {
              svg.setAttribute('preserveAspectRatio', 'xMidYMid meet');
            }
            var svgImageSrc = svgImage.href && svgImage.href.baseVal ? svgImage.href.baseVal : (svgImage.getAttribute('href') || svgImage.getAttribute('xlink:href'));
            setupReaderImage(svgImage, svgImageSrc, false, svg);
          });
          var images = Array.from(document.querySelectorAll('img'));
          var imagePromises = images.map(function(img) {
            return new Promise(function(resolve) {
              var isGaiji = img.classList.contains('gaiji') || img.classList.contains('gaiji-line');
              var mark = function() {
                if (!isGaiji && (img.naturalWidth > 256 || img.naturalHeight > 256)) {
                  img.classList.add('block-img');
                  setupReaderImage(img, img.currentSrc || img.src, true);
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
          Promise.all(imagePromises).then(function() {
            if (!images.length) return;
            return new Promise(function(resolve) { setTimeout(resolve, 50); });
          }).then(function() {
            window.hoshiReader.buildNodeOffsets();
            ${sasayakiCuesJson?.let { "window.hoshiReader.applySasayakiCues($it);" }.orEmpty()}
            ${highlightsJson?.let { "window.hoshiHighlights.applyHighlights($it);" }.orEmpty()}
            $initialRestoreScript
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
}

private fun readerImageTapScript(settings: ReaderSettings): String = """
    function setupReaderImage(element, src, wrap, blurElement) {
      if (!element || !src) return;
      blurElement = blurElement || element;
      if (${settings.blurImages}) {
        blurElement.classList.add('blurred');
        if (wrap && !blurElement.parentElement?.classList.contains('blur-wrapper')) {
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
          HoshiReaderImage.postMessage(new URL(src, document.baseURI).href);
        }
      });
    }
""".trimIndent()

private fun readerHighlightsScript(): String = """
    window.hoshiHighlights = {
      wrappers: new Map(),
      pendingRange: null,
      prepareHighlightSelection: function() {
        var selection = window.getSelection();
        if (!selection || selection.rangeCount === 0) return false;
        var range = selection.getRangeAt(0);
        if (range.collapsed) return false;
        this.pendingRange = range.cloneRange();
        return true;
      },
      createHighlight: function(color, id) {
        var selection = window.getSelection();
        var range = selection && selection.rangeCount > 0 ? selection.getRangeAt(0) : null;
        if ((!range || range.collapsed) && this.pendingRange) {
          range = this.pendingRange;
        }
        if (!range || range.collapsed) {
          this.pendingRange = null;
          return null;
        }
        var startPrefix = range.startContainer.textContent.substring(0, range.startOffset);
        var endPrefix = range.endContainer.textContent.substring(0, range.endOffset);
        var start = (window.hoshiReader.nodeStartOffsets.get(range.startContainer) || 0) + window.hoshiReader.countChars(startPrefix);
        var rawStart = (window.hoshiReader.nodeStartRawOffsets.get(range.startContainer) || 0) + window.hoshiReader.countRawChars(startPrefix);
        var rawEnd = (window.hoshiReader.nodeStartRawOffsets.get(range.endContainer) || 0) + window.hoshiReader.countRawChars(endPrefix);
        if (rawEnd <= rawStart) {
          this.pendingRange = null;
          return null;
        }
        var fragment = range.cloneContents();
        fragment.querySelectorAll('rt, rp').forEach(function(el) { el.remove(); });
        var text = fragment.textContent || '';
        if (selection) selection.removeAllRanges();
        this.pendingRange = null;
        this.wrapHighlight({ id: id, color: color, offset: rawStart, text: text });
        window.hoshiReader.buildNodeOffsets();
        requestAnimationFrame(function() {
          document.body.style.transform = 'translateZ(0)';
          requestAnimationFrame(function() { document.body.style.transform = ''; });
        });
        return { start: start, offset: rawStart, text: text };
      },
      collectSegments: function(offset, length) {
        var end = offset + length;
        var segments = [];
        var cursor = 0;
        var segment = null;
        var flushSegment = function() {
          if (!segment) return;
          segments.push(segment);
          segment = null;
        };
        var walker = window.hoshiReader.createWalker();
        var node;
        while (cursor < end && (node = walker.nextNode())) {
          var text = node.textContent || '';
          var i = 0;
          while (i < text.length && cursor < end) {
            var char = String.fromCodePoint(text.codePointAt(i));
            var next = i + char.length;
            if (cursor >= offset) {
              if (!segment || segment.node !== node) {
                flushSegment();
                segment = { node: node, start: i, end: next };
              } else {
                segment.end = next;
              }
            }
            cursor += 1;
            i = next;
          }
          flushSegment();
        }
        return segments;
      },
      wrapHighlight: function(highlight) {
        var segments = this.collectSegments(highlight.offset, Array.from(highlight.text || '').length);
        if (!segments.length) return;
        var range = document.createRange();
        var wrappers = [];
        for (var i = segments.length - 1; i >= 0; i--) {
          var segment = segments[i];
          range.setStart(segment.node, segment.start);
          range.setEnd(segment.node, segment.end);
          var wrapper = document.createElement('span');
          wrapper.className = 'hoshi-highlight hoshi-highlight-' + highlight.color;
          wrapper.appendChild(range.extractContents());
          range.insertNode(wrapper);
          wrappers.push(wrapper);
        }
        wrappers.reverse();
        this.wrappers.set(highlight.id, wrappers);
      },
      applyHighlights: function(highlights) {
        for (var i = 0; i < highlights.length; i++) {
          this.wrapHighlight(highlights[i]);
        }
        window.hoshiReader.buildNodeOffsets();
      },
      removeHighlight: function(id) {
        var wrappers = this.wrappers.get(id);
        if (!wrappers) return;
        window.hoshiReader.unwrap(wrappers);
        this.wrappers.delete(id);
        window.hoshiReader.buildNodeOffsets();
        requestAnimationFrame(function() {
          document.body.style.transform = 'translateZ(0)';
          requestAnimationFrame(function() { document.body.style.transform = ''; });
        });
      }
    };
""".trimIndent()

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

private fun SasayakiCueRange.toJavaScriptObjectLiteral(): String =
    "{id:${id.javaScriptStringLiteral()},start:$start,length:$length}"
