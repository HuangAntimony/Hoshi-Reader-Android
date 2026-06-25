package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.features.sasayaki.SasayakiCueRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPaginationScriptsTest {
    @Test
    fun previousChapterNavigationTargetsEndLikeIos() {
        val position = ReaderChapterPosition(index = 3)

        val previous = position.previousOrNull()

        assertEquals(ReaderChapterPosition(index = 2, progress = 1.0), previous)
    }

    @Test
    fun nextChapterNavigationTargetsStartLikeIos() {
        val position = ReaderChapterPosition(index = 3)

        val next = position.nextOrNull(lastIndex = 4)

        assertEquals(ReaderChapterPosition(index = 4, progress = 0.0), next)
    }

    @Test
    fun recognizesWebViewNavigationResults() {
        assertTrue(ReaderPaginationScripts.didScroll("\"scrolled\""))
        assertFalse(ReaderPaginationScripts.didScroll("\"limit\""))
        assertFalse(ReaderPaginationScripts.didScroll(null))

        assertEquals(ReaderNavigationResult.Advanced, ReaderPaginationScripts.navigationResult("\"scrolled\""))
        assertEquals(ReaderNavigationResult.Revealed, ReaderPaginationScripts.navigationResult("\"revealed\""))
        assertEquals(ReaderNavigationResult.Limit, ReaderPaginationScripts.navigationResult("\"limit\""))
        assertEquals(ReaderNavigationResult.Limit, ReaderPaginationScripts.navigationResult(null))
    }

    @Test
    fun parsesNullableDoubleResultsFromWebView() {
        assertEquals(0.625, ReaderPaginationScripts.doubleResult("\"0.625\"") ?: -1.0, 0.0)
        assertEquals(1.0, ReaderPaginationScripts.doubleResult("1.0") ?: -1.0, 0.0)
        assertNull(ReaderPaginationScripts.doubleResult("\"limit\""))
        assertNull(ReaderPaginationScripts.doubleResult(null))
    }

    @Test
    fun exportsImageBoundsFromMeasuredViewportForAndroidWebView() {
        val layout = readerViewportCssLayout(
            settings = ReaderSettings(verticalWriting = true),
            viewportCssWidth = 480,
            viewportCssHeight = 800,
        )

        assertEquals(822, layout.pageHeightPx)
        assertEquals(480, layout.pageWidthPx)
        assertEquals(455, layout.imageMaxWidthPx)
        assertEquals(800, layout.imageMaxHeightPx)
    }

    @Test
    fun verticalImageBoundsSubtractOnePixelGuardLikeIosWebViewWorkaround() {
        assertEquals(
            455,
            readerViewportCssLayout(ReaderSettings(verticalWriting = true), 480, 800).imageMaxWidthPx,
        )
        assertEquals(
            456,
            readerViewportCssLayout(ReaderSettings(verticalWriting = false), 480, 800).imageMaxWidthPx,
        )
    }

    @Test
    fun exportsVerticalPaddingPxVariablesForAndroidWebView() {
        val layout = readerViewportCssLayout(
            settings = ReaderSettings(verticalPadding = 22),
            viewportCssWidth = 480,
            viewportCssHeight = 800,
        )

        assertEquals(88.0, layout.verticalPaddingBlockPx, 0.0)
        assertEquals(176.0, layout.verticalPaddingGapPx, 0.0)
    }

    @Test
    fun verticalImageBoundsFitPaddedPageContentOnAndroidWebView() {
        val settings = ReaderSettings(
            verticalWriting = true,
            fontSize = 28,
            horizontalPadding = 14,
            verticalPadding = 14,
        )

        val layout = readerViewportCssLayout(
            settings = settings,
            viewportCssWidth = 384,
            viewportCssHeight = 801,
        )

        assertEquals(829, layout.pageHeightPx)
        assertEquals(329, layout.imageMaxWidthPx)
        assertEquals(688, layout.imageMaxHeightPx)
    }

    @Test
    fun generatedLayoutUsesContinuousFullWidthImagesOnlyForVerticalScrollMode() {
        val paginatedVertical = ReaderGeneratedLayout.from(
            ReaderSettings(viewMode = ReaderViewMode.Paginated, verticalWriting = true),
        )
        val continuousVertical = ReaderGeneratedLayout.from(
            ReaderSettings(viewMode = ReaderViewMode.Continuous, verticalWriting = true),
        )
        val continuousHorizontal = ReaderGeneratedLayout.from(
            ReaderSettings(viewMode = ReaderViewMode.Continuous, verticalWriting = false),
        )

        assertEquals(0.95, paginatedVertical.imageWidthViewportRatio, 0.0)
        assertEquals(1.0, continuousVertical.imageWidthViewportRatio, 0.0)
        assertEquals(0.95, continuousHorizontal.imageWidthViewportRatio, 0.0)
        assertEquals(1, continuousVertical.imageWidthReductionPx)
        assertEquals(0, continuousHorizontal.imageWidthReductionPx)
    }

    @Test
    fun sasayakiHighlightCommandCarriesCueRangeForLazyWrapping() {
        val command = ReaderPaginationScripts.highlightSasayakiCueInvocation(
            cue = SasayakiCueRange(id = "cue\"1", start = 42, length = 7),
            reveal = true,
        )

        assertEquals(
            """window.hoshiReader.highlightSasayakiCue({id:"cue\"1",start:42,length:7}, true)""",
            command,
        )
    }

    @Test
    fun sasayakiMediaStopCommandsUseReaderPublicApi() {
        val cue = SasayakiCueRange(id = "cue\"1", start = 42, length = 7)

        assertEquals(
            """window.hoshiReader.sasayakiMediaStopsBeforeCue({id:"cue\"1",start:42,length:7})""",
            ReaderPaginationScripts.sasayakiMediaStopsBeforeCueInvocation(cue),
        )
        assertEquals(
            "window.hoshiReader.sasayakiMediaStopsToChapterEnd()",
            ReaderPaginationScripts.sasayakiMediaStopsToChapterEndInvocation(),
        )
        assertEquals(
            """window.hoshiReader.showSasayakiMediaStop({"scroll":800})""",
            ReaderPaginationScripts.showSasayakiMediaStopInvocation("""{"scroll":800}"""),
        )
    }
}
