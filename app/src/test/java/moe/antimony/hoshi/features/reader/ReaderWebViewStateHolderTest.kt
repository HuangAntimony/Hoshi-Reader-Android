package moe.antimony.hoshi.features.reader

import androidx.compose.ui.unit.IntSize
import kotlin.io.path.createTempDirectory
import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun continuousScrollProgressIsIgnoredWhileWebViewIsRestoringLikeIos() {
        val holder = stateHolder(initialIndex = 2)

        val next = holder.goToNextChapter(lastIndex = 3)
        val ignored = holder.recordContinuousScrollProgress(0.72, holder.webViewRestoreEpoch)

        assertEquals(ReaderChapterPosition(index = 3, progress = 0.0), next)
        assertNull(ignored)
        assertEquals(ReaderChapterPosition(index = 3, progress = 0.0), holder.readerPosition.displayedPosition)

        holder.markWebViewRestored()
        val saved = holder.recordContinuousScrollProgress(0.12, holder.webViewRestoreEpoch)

        assertEquals(ReaderChapterPosition(index = 3, progress = 0.12), saved)
        assertEquals(saved, holder.readerPosition.displayedPosition)
    }

    @Test
    fun staleContinuousScrollProgressFromPreviousRestoreEpochIsIgnored() {
        val holder = stateHolder(initialIndex = 2)
        holder.markWebViewRestored()
        val oldEpoch = holder.webViewRestoreEpoch

        holder.goToNextChapter(lastIndex = 3)
        holder.markWebViewRestored()
        val staleProgress = holder.recordContinuousScrollProgress(0.72, oldEpoch)

        assertNull(staleProgress)
        assertEquals(ReaderChapterPosition(index = 3, progress = 0.0), holder.readerPosition.displayedPosition)
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
    fun readerWebViewWaitsForMeasuredViewportBeforeInitialLoad() {
        assertFalse(readerWebViewReadyToLoad(IntSize.Zero))
        assertTrue(readerWebViewReadyToLoad(IntSize(800, 1200)))
    }

    @Test
    fun sasayakiTopToggleSpaceIsReservedBeforeSidecarsAreParsed() {
        val root = createTempDirectory("hoshi-sasayaki-sidecar").toFile()
        try {
            assertFalse(readerShouldReserveSasayakiTopToggle(root, SasayakiSettings()))

            root.resolve("sasayaki_match.json").writeText("{}")
            root.resolve("sasayaki_playback.json").writeText("{}")

            assertTrue(readerShouldReserveSasayakiTopToggle(root, SasayakiSettings()))
            assertFalse(readerShouldReserveSasayakiTopToggle(root, SasayakiSettings(enabled = false)))
            assertFalse(readerShouldReserveSasayakiTopToggle(root, SasayakiSettings(showReaderToggle = false)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun syncedLayoutSettingsReloadAtDisplayedPosition() {
        val holder = stateHolder(initialIndex = 1)
        holder.markWebViewRestored()
        holder.recordDisplayedProgress(0.35)
        val previousEpoch = holder.webViewRestoreEpoch

        holder.syncSettings(ReaderSettings(verticalPadding = 22))

        assertEquals(ReaderChapterPosition(index = 1, progress = 0.35), holder.readerPosition.loadPosition)
        assertEquals(22, holder.effectiveSettings.verticalPadding)
        assertTrue(holder.isWebViewRestoring)
        assertEquals(previousEpoch + 1, holder.webViewRestoreEpoch)
    }

    @Test
    fun syncedChromeOnlySettingsDoNotReloadWebView() {
        val holder = stateHolder(initialIndex = 1)
        holder.markWebViewRestored()
        val previousEpoch = holder.webViewRestoreEpoch

        holder.syncSettings(ReaderSettings(showTitle = false, showProgressTop = false))

        assertFalse(holder.isWebViewRestoring)
        assertEquals(previousEpoch, holder.webViewRestoreEpoch)
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

    @Test
    fun readerMenuButtonTogglesMenuVisibility() {
        val holder = stateHolder()

        holder.toggleReaderMenu()
        assertTrue(holder.showReaderMenu)

        holder.toggleReaderMenu()
        assertFalse(holder.showReaderMenu)
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
