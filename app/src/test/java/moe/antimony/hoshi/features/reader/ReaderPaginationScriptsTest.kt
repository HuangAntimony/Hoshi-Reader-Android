package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPaginationScriptsTest {
    @Test
    fun previousChapterNavigationTargetsEndLikeIos() {
        val position = ReaderChapterPosition(index = 3)

        val previous = position.previousOrNull()

        assertTrue(previous == ReaderChapterPosition(index = 2, progress = 1.0))
    }

    @Test
    fun nextChapterNavigationTargetsStartLikeIos() {
        val position = ReaderChapterPosition(index = 3)

        val next = position.nextOrNull(lastIndex = 4)

        assertTrue(next == ReaderChapterPosition(index = 4, progress = 0.0))
    }

    @Test
    fun paginateScriptLeavesChapterChangesToNativeCodeLikeIos() {
        val script = ReaderPaginationScripts.shellScript()

        assertFalse(script.contains("HoshiChapterBridge"))
        assertFalse(script.contains("nextChapter()"))
        assertFalse(script.contains("previousChapter()"))
    }

    @Test
    fun verticalPaginationUsesBodyScrollContextLikeIos() {
        val script = ReaderPaginationScripts.shellScript()

        assertTrue(script.contains("var scrollEl = document.body"))
        assertTrue(script.contains("scrollEl.scrollHeight"))
        assertTrue(script.contains("scrollEl.scrollTop"))
        assertTrue(script.contains("setPagePosition"))
    }

    @Test
    fun recognizesWebViewStringResultForScrolledPage() {
        assertTrue(ReaderPaginationScripts.didScroll("\"scrolled\""))
        assertFalse(ReaderPaginationScripts.didScroll("\"limit\""))
        assertFalse(ReaderPaginationScripts.didScroll(null))
    }

    @Test
    fun exportsImageBoundsFromMeasuredViewportForAndroidWebView() {
        val script = ReaderPaginationScripts.shellScript()

        assertTrue(script.contains("--hoshi-image-max-width"))
        assertTrue(script.contains("--hoshi-image-max-height"))
        assertTrue(script.contains("Math.floor(pageWidth * 0.95)"))
        assertTrue(script.contains("pageHeight - 22"))
    }

    @Test
    fun doesNotPatchViewportResizeInsideReaderJavaScript() {
        val script = ReaderPaginationScripts.shellScript()

        assertFalse(script.contains("relayoutForViewport"))
        assertFalse(script.contains("window.addEventListener('resize'"))
    }

    @Test
    fun normalizesCalibreCoverSvgAspectRatio() {
        val script = ReaderPaginationScripts.shellScript()

        assertTrue(script.contains("svg.querySelector('image')"))
        assertTrue(script.contains("svg.setAttribute('preserveAspectRatio', 'xMidYMid meet')"))
    }

    @Test
    fun verticalPageHeightIncludesIosBottomOverlap() {
        val script = ReaderPaginationScripts.shellScript()

        assertTrue(script.contains("var pageHeight = window.innerHeight + 22;"))
    }

    @Test
    fun loadScriptRestoresInitialProgressAfterLayout() {
        val script = ReaderPaginationScripts.shellScript(initialProgress = 1.0)

        assertTrue(script.contains("restoreProgress:"))
        assertTrue(script.contains("function(progress)"))
        assertTrue(script.contains("if (progress >= 0.99)"))
        assertTrue(script.contains("window.hoshiReader.restoreProgress(1.0)"))
    }

    @Test
    fun exposesCharacterBasedProgressCalculationLikeIos() {
        val script = ReaderPaginationScripts.shellScript()

        assertTrue(script.contains("calculateProgress: function()"))
        assertTrue(script.contains("var exploredChars = 0"))
        assertTrue(script.contains("range.selectNodeContents(node)"))
        assertTrue(script.contains("return totalChars > 0 ? exploredChars / totalChars : 0"))
    }

    @Test
    fun restoresProgressByCharacterPositionLikeIos() {
        val script = ReaderPaginationScripts.shellScript()

        assertTrue(script.contains("notifyRestoreComplete: function()"))
        assertTrue(script.contains("window.HoshiReaderRestore.postMessage('restoreCompleted')"))
        assertTrue(script.contains("var targetCharCount = Math.ceil(totalChars * progress)"))
        assertTrue(script.contains("if (runningSum > targetCharCount)"))
        assertTrue(script.contains("range.setStart(targetNode, 0)"))
        assertTrue(script.contains("var anchor = (context.vertical ? rect.top : rect.left)"))
        assertTrue(script.contains("var targetScroll = this.alignToPage(context, anchor)"))
    }

    @Test
    fun exposesSasayakiCueWrappingAndHighlightingLikeIos() {
        val script = ReaderPaginationScripts.shellScript()

        assertTrue(script.contains("cueWrappers: new Map()"))
        assertTrue(script.contains("collectSasayakiCueRanges: function(cues)"))
        assertTrue(script.contains("applySasayakiCues: function(cues)"))
        assertTrue(script.contains("highlightSasayakiCue: function(cueId, reveal)"))
        assertTrue(script.contains("clearSasayakiCue: function()"))
        assertTrue(script.contains("resetSasayakiCues: function()"))
        assertTrue(script.contains("className = 'hoshi-sasayaki-cue'"))
        assertTrue(script.contains("hoshi-sasayaki-active"))
    }

    @Test
    fun sasayakiRevealScrollsToRangeCenterLikeIos() {
        val script = ReaderPaginationScripts.shellScript()
        val scrollToRange = script.substringAfter("scrollToRange: function(range)")
            .substringBefore("calculateProgress: function()")

        assertTrue(scrollToRange.contains("if (context.pageSize <= 0)"))
        assertTrue(scrollToRange.contains("var anchor = (context.vertical ? (rect.top + rect.bottom) / 2 : (rect.left + rect.right) / 2) + currentScroll"))
        assertFalse(scrollToRange.contains("var anchor = (context.vertical ? rect.top : rect.left) + currentScroll"))
    }

    @Test
    fun pageBoundariesUseLastActualContentPageInsteadOfReportedScrollHeight() {
        val script = ReaderPaginationScripts.shellScript()

        assertTrue(script.contains("contentLastPageScroll: function(context)"))
        assertTrue(script.contains("contentFirstPageScroll: function(context)"))
        assertTrue(script.contains("alignContentStartToPage: function(context, offset)"))
        assertTrue(script.contains("Math.abs(safeOffset - nearestPage) < 1"))
        assertTrue(script.contains("var edge = (context.vertical ? rect.bottom : rect.right) + currentScroll"))
        assertTrue(script.contains("var edge = (context.vertical ? rect.top : rect.left) + currentScroll"))
        assertTrue(script.contains("var mediaEdge = (context.vertical ? mediaRect.bottom : mediaRect.right) + currentScroll"))
        assertTrue(script.contains("var mediaEdge = (context.vertical ? mediaRect.top : mediaRect.left) + currentScroll"))
        assertTrue(script.contains("var minAlignedScroll = this.contentFirstPageScroll(context)"))
        assertTrue(script.contains("var maxAlignedScroll = this.contentLastPageScroll(context)"))
    }

    @Test
    fun appendsTrailingSpacerUsingIosDefaultVerticalLayout() {
        val script = ReaderPaginationScripts.shellScript()

        assertTrue(script.contains("spacer.style.height = 'calc(0.0vh + 22px)'"))
        assertTrue(script.contains("spacer.style.width = '0'"))
    }

    @Test
    fun readerCssUsesIosDefaultPagedLayoutValues() {
        val css = ReaderContentStyles.styleTag()

        assertTrue(css.contains("font-size: 22px !important"))
        assertTrue(css.contains("column-gap: calc(0vh + 22px);"))
        assertTrue(css.contains("padding: 0.0vh 2.5vw !important;"))
        assertTrue(css.contains("padding-bottom: calc(0.0vh + 22px) !important;"))
        assertFalse(css.contains("line-height: 1.65 !important;"))
        assertTrue(css.contains("text-align: start !important;"))
        assertTrue(css.contains("max-width: var(--hoshi-image-max-width, 95vw) !important"))
        assertTrue(css.contains("width: auto !important"))
        assertTrue(css.contains("height: auto !important"))
        assertTrue(css.contains("::highlight(hoshi-selection)"))
        assertTrue(css.contains("background-color: rgba(160, 160, 160, 0.4) !important"))
    }
}
