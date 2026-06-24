package moe.antimony.hoshi.features.reader

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import moe.antimony.hoshi.epub.SasayakiMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReaderSasayakiAutoPageCoordinatorTest {
    @Test
    fun pausesPlaybackWhileHoldingEveryImageStopAcrossIntermediateChapters() = runBlocking {
        val cue = SasayakiMatch("target", 30.0, 32.0, "target", chapterIndex = 3, start = 0, length = 6)
        val events = mutableListOf<String>()
        val driver = FakeAutoPageDriver(
            initialChapterIndex = 0,
            mediaStopsByChapter = mapOf(
                0 to listOf(ReaderSasayakiMediaStop(scroll = 800.0)),
                1 to listOf(
                    ReaderSasayakiMediaStop(scroll = 0.0),
                    ReaderSasayakiMediaStop(scroll = 800.0),
                ),
                2 to listOf(ReaderSasayakiMediaStop(scroll = 0.0)),
                3 to listOf(ReaderSasayakiMediaStop(scroll = 0.0)),
            ),
            events = events,
        )
        val coordinator = ReaderSasayakiAutoPageCoordinator(
            holdMillis = 1_000L,
            delay = { millis -> events += "delay:$millis" },
        )

        coordinator.revealCueWithMediaStops(cue = cue, reveal = true, driver = driver)

        assertEquals(
            listOf(
                "pause-hold",
                "show:0:800.0",
                "delay:1000",
                "load:1",
                "show:1:0.0",
                "delay:1000",
                "show:1:800.0",
                "delay:1000",
                "load:2",
                "show:2:0.0",
                "delay:1000",
                "load:3",
                "show:3:0.0",
                "delay:1000",
                "reveal:target:true",
                "resume-hold",
            ),
            events,
        )
    }

    @Test
    fun revealsImmediatelyWithoutPausingWhenNoImageStopsAreSkipped() = runBlocking {
        val cue = SasayakiMatch("target", 10.0, 11.0, "target", chapterIndex = 0, start = 0, length = 6)
        val events = mutableListOf<String>()
        val coordinator = ReaderSasayakiAutoPageCoordinator(
            holdMillis = 1_000L,
            delay = { millis -> events += "delay:$millis" },
        )

        coordinator.revealCueWithMediaStops(
            cue = cue,
            reveal = true,
            driver = FakeAutoPageDriver(
                initialChapterIndex = 0,
                mediaStopsByChapter = emptyMap(),
                events = events,
            ),
        )

        assertEquals(listOf("reveal:target:true"), events)
    }

    @Test
    fun loadsPreviousChapterBeforeHoldingItsImageStops() = runBlocking {
        val cue = SasayakiMatch("target", 10.0, 11.0, "target", chapterIndex = 2, start = 0, length = 6)
        val events = mutableListOf<String>()
        val coordinator = ReaderSasayakiAutoPageCoordinator(
            holdMillis = 1_000L,
            delay = { millis -> events += "delay:$millis" },
        )
        val driver = FakeAutoPageDriver(
            initialChapterIndex = 3,
            mediaStopsByChapter = mapOf(2 to listOf(ReaderSasayakiMediaStop(scroll = 800.0))),
            events = events,
        )

        coordinator.revealCueWithMediaStops(cue = cue, reveal = true, driver = driver)

        assertEquals(
            listOf(
                "pause-hold",
                "load:2",
                "show:2:800.0",
                "delay:1000",
                "reveal:target:true",
                "resume-hold",
            ),
            events,
        )
    }

    @Test
    fun cancellationDoesNotResumeAutoPageHold() = runBlocking {
        val cue = SasayakiMatch("target", 10.0, 11.0, "target", chapterIndex = 0, start = 0, length = 6)
        val events = mutableListOf<String>()
        val coordinator = ReaderSasayakiAutoPageCoordinator(
            holdMillis = 1_000L,
            delay = { awaitCancellation() },
        )
        val driver = FakeAutoPageDriver(
            initialChapterIndex = 0,
            mediaStopsByChapter = mapOf(0 to listOf(ReaderSasayakiMediaStop(scroll = 800.0))),
            events = events,
        )

        val job = launch {
            coordinator.revealCueWithMediaStops(cue = cue, reveal = true, driver = driver)
        }
        while ("show:0:800.0" !in events) {
            yield()
        }

        job.cancelAndJoin()

        assertFalse("resume-hold" in events)
    }

    private class FakeAutoPageDriver(
        initialChapterIndex: Int,
        private val mediaStopsByChapter: Map<Int, List<ReaderSasayakiMediaStop>>,
        private val events: MutableList<String>,
    ) : ReaderSasayakiAutoPageDriver {
        override var currentChapterIndex: Int = initialChapterIndex
            private set

        override suspend fun mediaStopsToChapterEnd(): List<ReaderSasayakiMediaStop> =
            mediaStopsByChapter[currentChapterIndex].orEmpty()

        override suspend fun mediaStopsBeforeCue(cue: SasayakiMatch): List<ReaderSasayakiMediaStop> =
            mediaStopsByChapter[currentChapterIndex].orEmpty()

        override suspend fun showMediaStop(stop: ReaderSasayakiMediaStop): Double? {
            events += "show:$currentChapterIndex:${stop.scroll}"
            return 0.0
        }

        override suspend fun revealCue(cue: SasayakiMatch, reveal: Boolean): Double? {
            events += "reveal:${cue.id}:$reveal"
            return 0.0
        }

        override suspend fun loadChapter(chapterIndex: Int) {
            currentChapterIndex = chapterIndex
            events += "load:$chapterIndex"
        }

        override fun pauseForAutoPageHold(): Boolean {
            events += "pause-hold"
            return true
        }

        override fun resumeAfterAutoPageHold() {
            events += "resume-hold"
        }
    }
}
