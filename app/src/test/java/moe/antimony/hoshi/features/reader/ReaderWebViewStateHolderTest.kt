package moe.antimony.hoshi.features.reader

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderWebViewStateHolderTest {
    @Test
    fun recordsDisplayedProgressWithoutChangingLoadTarget() {
        val holder = stateHolder(initialIndex = 2)

        val saved = holder.recordDisplayedProgress(0.42)

        assertEquals(ReaderChapterPosition(index = 2, progress = 0.0), holder.readerPosition.loadPosition)
        assertEquals(ReaderChapterPosition(index = 2, progress = 0.42), holder.readerPosition.displayedPosition)
        assertEquals(holder.readerPosition.displayedPosition, saved)
    }

    @Test
    fun chapterLimitNavigationUpdatesLoadAndDisplayedPositions() {
        val holder = stateHolder(initialIndex = 2)

        val next = holder.goToNextChapter(lastIndex = 3)
        val previous = holder.goToPreviousChapter()

        assertEquals(ReaderChapterPosition(index = 3, progress = 0.0), next)
        assertEquals(ReaderChapterPosition(index = 2, progress = 1.0), previous)
        assertEquals(previous, holder.readerPosition.loadPosition)
        assertEquals(previous, holder.readerPosition.displayedPosition)
    }

    @Test
    fun viewportResizeAfterInitialMeasureReloadsAtDisplayedPosition() {
        val holder = stateHolder(initialIndex = 1)

        holder.updateViewportSize(IntSize(800, 1200))
        holder.recordDisplayedProgress(0.35)
        holder.updateViewportSize(IntSize(900, 1200))

        assertEquals(ReaderChapterPosition(index = 1, progress = 0.35), holder.readerPosition.loadPosition)
        assertEquals(IntSize(900, 1200), holder.webViewViewportSize)
    }

    @Test
    fun emptyLookupStackConsumesSasayakiResumeRequest() {
        val holder = stateHolder()
        holder.markSasayakiPausedByLookup()
        var resumed = 0

        holder.setLookupPopups(emptyList()) { resumed += 1 }
        holder.setLookupPopups(emptyList()) { resumed += 1 }

        assertEquals(1, resumed)
        assertFalse(holder.sasayakiWasPausedByLookup)
    }

    @Test
    fun lookupAutoPauseOnlyMarksWhenEnabledAutoPauseAndPlaying() {
        val holder = stateHolder()

        assertFalse(holder.shouldPauseSasayakiForLookup(enabled = true, autoPause = true, isPlaying = false))
        assertFalse(holder.sasayakiWasPausedByLookup)
        assertTrue(holder.shouldPauseSasayakiForLookup(enabled = true, autoPause = true, isPlaying = true))
        assertTrue(holder.sasayakiWasPausedByLookup)
        assertFalse(holder.shouldPauseSasayakiForLookup(enabled = true, autoPause = false, isPlaying = true))
        assertFalse(holder.sasayakiWasPausedByLookup)
    }

    @Test
    fun menuActionsOpenOnlyTheRequestedSheet() {
        val holder = stateHolder()

        holder.showReaderMenu()
        holder.openAppearanceFromMenu()
        assertFalse(holder.showReaderMenu)
        assertTrue(holder.showAppearance)

        holder.dismissAppearance()
        holder.showReaderMenu()
        holder.openChaptersFromMenu()
        assertFalse(holder.showReaderMenu)
        assertTrue(holder.showChapters)

        holder.dismissChapters()
        holder.showReaderMenu()
        holder.openSasayakiFromMenu()
        assertFalse(holder.showReaderMenu)
        assertTrue(holder.showSasayaki)
    }

    private fun stateHolder(
        initialIndex: Int = 0,
        initialProgress: Double = 0.0,
    ): ReaderWebViewStateHolder =
        ReaderWebViewStateHolder(
            initialSettings = ReaderSettings(),
            initialPosition = ReaderChapterPosition(
                index = initialIndex,
                progress = initialProgress,
            ),
        )
}
