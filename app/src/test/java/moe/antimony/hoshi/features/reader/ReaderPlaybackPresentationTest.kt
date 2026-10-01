package moe.antimony.hoshi.features.reader

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import moe.antimony.hoshi.features.sasayaki.SasayakiCueRevealSource
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReaderPlaybackPresentationTest {
    private class Reader : ReaderPlaybackPresentationActions {
        var chapter = 0
        var active = true
        val events = mutableListOf<String>()
        var stops = listOf(ReaderSasayakiMediaStop(screenIndex = 1))
        var restore = ReaderPlaybackTargetRestoreResult(false, true, 0.2,
            listOf(ReaderSasayakiMediaStop(screenIndex = 2)))
        var afterHold: () -> Unit = {}
        override suspend fun stopsBeforeCue() = stops
        override suspend fun stopsToChapterEnd(): List<ReaderSasayakiMediaStop> {
            events += "inspect:$chapter"
            return stops
        }
        override suspend fun showStops(stops: List<ReaderSasayakiMediaStop>, countStatistics: Boolean) {
            for (stop in stops) {
                events += "image:${stop.screenIndex}"
                delay(1000)
                afterHold()
            }
        }
        override suspend fun holdVisibleStop(progress: Double?) { events += "first-image"; delay(1000); afterHold() }
        override suspend fun loadChapter(chapterIndex: Int): Boolean {
            chapter = chapterIndex
            events += "chapter:$chapter"
            return true
        }
        override suspend fun restoreTarget(inspectMedia: Boolean, fromChapter: Int): ReaderPlaybackTargetRestoreResult {
            events += "restore:$inspectMedia"
            return restore
        }
        override suspend fun reveal(reveal: Boolean): Double? { events += "reveal:$reveal"; return 0.5 }
    }

    @Test fun sentenceIsRevealedOnlyAfterImageHoldFinishes() = runTest {
        val reader = Reader()
        val work = async { revealReaderPlaybackWithMediaStops(0, true,
            SasayakiCueRevealSource.NaturalPlayback, 1000, { reader.chapter }, reader) }
        runCurrent()
        assertEquals(listOf("image:1"), reader.events)
        advanceTimeBy(999); runCurrent()
        assertFalse(work.isCompleted)
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("image:1", "reveal:true"), reader.events)
        assertEquals(ReaderPlaybackRevealResult(0.5, true), work.await())
    }

    @Test fun forwardChapterPlaybackHoldsTailIntermediateAndTargetImagesInOrder() = runTest {
        val reader = Reader()
        val work = async { revealReaderPlaybackWithMediaStops(2, true,
            SasayakiCueRevealSource.NaturalPlayback, 1000, { reader.chapter }, reader) }
        advanceUntilIdle()
        assertEquals(listOf("inspect:0", "image:1", "chapter:1", "inspect:1", "image:1",
            "restore:true", "first-image", "image:2", "reveal:true"), reader.events)
        assertEquals(ReaderPlaybackRevealResult(0.5, false), work.await())
    }

    @Test fun directJumpAndDisabledHoldsSkipIntermediateImages() = runTest {
        for ((source, duration) in listOf(SasayakiCueRevealSource.DirectJump to 1000L,
            SasayakiCueRevealSource.NaturalPlayback to 0L)) {
            val reader = Reader().apply { restore = ReaderPlaybackTargetRestoreResult(true) }
            revealReaderPlaybackWithMediaStops(2, true, source, duration, { reader.chapter }, reader)
            assertEquals(listOf("restore:false"), reader.events)
        }
    }

    @Test fun pauseDuringImageHoldCannotRevealOrLoadTheNextChapter() = runTest {
        val reader = Reader().apply { afterHold = { active = false } }
        val result = revealReaderPlaybackWithMediaStops(2, true,
            SasayakiCueRevealSource.NaturalPlayback, 1000, { reader.chapter }, reader, { reader.active })
        assertEquals(listOf("inspect:0", "image:1"), reader.events)
        assertEquals(ReaderPlaybackRevealResult(null, false), result)
    }

    @Test fun passiveRestorationDoesNotInspectOrHoldImages() = runTest {
        val reader = Reader()
        val result = revealReaderPlaybackWithMediaStops(0, false,
            SasayakiCueRevealSource.DirectJump, 1000, { reader.chapter }, reader)
        assertEquals(listOf("reveal:false"), reader.events)
        assertEquals(ReaderPlaybackRevealResult(0.5, true), result)
    }
}
