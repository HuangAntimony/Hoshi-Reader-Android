package moe.antimony.hoshi.features.reader

import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.antimony.hoshi.features.sasayaki.SasayakiCueRevealSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSasayakiAutoPageCancellationTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun liveMatchRefreshWaitsForLatestAutoPageJobWithoutCancellingImageHold() = runTest {
        val firstHold = Job()
        val nextHold = Job()
        var activeHold = firstHold
        var refreshApplied = false
        val refresh = launch {
            awaitReaderSasayakiPresentationIdle { activeHold }
            refreshApplied = true
        }
        runCurrent()
        assertFalse(refreshApplied)
        assertTrue(firstHold.isActive)

        activeHold = nextHold
        firstHold.complete()
        runCurrent()
        assertFalse(refreshApplied)
        assertTrue(nextHold.isActive)

        nextHold.complete()
        refresh.join()
        assertTrue(refreshApplied)
        assertFalse(firstHold.isCancelled)
        assertFalse(nextHold.isCancelled)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun liveMatchRefreshWaitsForNativeSelectionToEndAndThenApplies() = runTest {
        val selectionActive = MutableStateFlow(true)
        var refreshApplied = false
        launch {
            awaitReaderSasayakiPresentationIdle(selectionActive) { null }
            refreshApplied = true
        }
        runCurrent()
        assertFalse(refreshApplied)

        selectionActive.value = false
        runCurrent()
        assertTrue(refreshApplied)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun selectionStartedDuringImageHoldKeepsPendingMatchRefreshDeferred() = runTest {
        val hold = Job()
        val selectionActive = MutableStateFlow(false)
        var refreshApplied = false
        launch {
            awaitReaderSasayakiPresentationIdle(selectionActive) { hold }
            refreshApplied = true
        }
        runCurrent()
        selectionActive.value = true
        hold.complete()
        runCurrent()
        assertFalse(refreshApplied)

        selectionActive.value = false
        runCurrent()
        assertTrue(refreshApplied)
    }

    @Test
    fun cancelReleasesAutoPageHoldSynchronously() {
        val job = Job()
        val events = mutableListOf<String>()

        cancelReaderSasayakiAutoPage(
            job = job,
            clearAutoPageJob = { events += "clear-job" },
            clearPendingRestoreCue = { events += "clear-restore-cue" },
            resumeAfterAutoPageHold = { events += "release-hold" },
        )

        assertTrue(job.isCancelled)
        assertEquals(
            listOf("clear-job", "clear-restore-cue", "release-hold"),
            events,
        )
    }

    @Test
    fun openFullscreenImageKeepsAutoPageHoldBeforeShowingImage() {
        val events = mutableListOf<String>()
        val resource = ReaderWebResource(mediaType = "image/png", encoding = null, data = byteArrayOf(1))

        val opened = openReaderFullscreenImage(
            sourceUrl = "https://reader.example/image.png",
            imageResourceForUrl = {
                events += "resolve:$it"
                resource
            },
            closeLookupPopupsAndSelection = { events += "close-popups" },
            showFullscreenImage = { image ->
                events += "show:${image.sourceUrl}:${image.resource.mediaType}"
            },
        )

        assertTrue(opened)
        assertEquals(
            listOf(
                "resolve:https://reader.example/image.png",
                "close-popups",
                "show:https://reader.example/image.png:image/png",
            ),
            events,
        )
    }

    @Test
    fun imageHoldWaitsForFullscreenImageToCloseAfterHoldDeadline() = runBlocking {
        var fullscreenImageVisible = true
        val delays = mutableListOf<Long>()

        awaitReaderSasayakiImageHold(
            imageHoldMillis = 5_000L,
            isFullscreenImageVisible = { fullscreenImageVisible },
            delayMillis = { millis: Long ->
                delays += millis
                if (millis == ReaderSasayakiFullscreenImageDismissPollMillis) {
                    fullscreenImageVisible = false
                }
            },
        )

        assertEquals(
            listOf(5_000L, ReaderSasayakiFullscreenImageDismissPollMillis),
            delays,
        )
    }

    @Test
    fun awaitReaderChapterReadyReportsTimeout() = runBlocking {
        var delayCount = 0

        val ready = awaitReaderSasayakiChapterReady(
            isChapterReady = { false },
            delayFrame = { delayCount += 1 },
            attempts = 3,
        )

        assertEquals(false, ready)
        assertEquals(3, delayCount)
    }

    @Test
    fun naturalPlaybackHoldsSameChapterMediaStops() {
        assertTrue(
            readerSasayakiShouldHoldMediaStopsBeforeCue(
                currentChapterIndex = 2,
                cueChapterIndex = 2,
                source = SasayakiCueRevealSource.NaturalPlayback,
                imageHoldMillis = 1_000L,
            ),
        )
    }

    @Test
    fun directJumpDoesNotHoldSameChapterMediaStops() {
        assertFalse(
            readerSasayakiShouldHoldMediaStopsBeforeCue(
                currentChapterIndex = 2,
                cueChapterIndex = 2,
                source = SasayakiCueRevealSource.DirectJump,
                imageHoldMillis = 1_000L,
            ),
        )
    }

    @Test
    fun disabledImageHoldSkipsMediaStops() {
        assertFalse(
            readerSasayakiShouldHoldMediaStopsBeforeCue(
                currentChapterIndex = 2,
                cueChapterIndex = 2,
                source = SasayakiCueRevealSource.NaturalPlayback,
                imageHoldMillis = 0L,
            ),
        )
        assertEquals(
            ReaderSasayakiTargetChapterMediaPolicy.DirectRestore,
            readerSasayakiTargetChapterMediaPolicy(
                currentChapterIndex = 2,
                cueChapterIndex = 3,
                source = SasayakiCueRevealSource.NaturalPlayback,
                imageHoldMillis = 0L,
            ),
        )
    }

    @Test
    fun naturalPlaybackForwardJumpsInspectTargetChapterMediaStopsWithoutPreHold() {
        assertEquals(
            ReaderSasayakiTargetChapterMediaPolicy.InspectStopsWithoutPreHold,
            readerSasayakiTargetChapterMediaPolicy(
                currentChapterIndex = 2,
                cueChapterIndex = 3,
                source = SasayakiCueRevealSource.NaturalPlayback,
                imageHoldMillis = 1_000L,
            ),
        )
        assertEquals(
            ReaderSasayakiTargetChapterMediaPolicy.DirectRestore,
            readerSasayakiTargetChapterMediaPolicy(
                currentChapterIndex = 2,
                cueChapterIndex = 3,
                source = SasayakiCueRevealSource.DirectJump,
                imageHoldMillis = 1_000L,
            ),
        )
        assertEquals(
            ReaderSasayakiTargetChapterMediaPolicy.DirectRestore,
            readerSasayakiTargetChapterMediaPolicy(
                currentChapterIndex = 3,
                cueChapterIndex = 2,
                source = SasayakiCueRevealSource.NaturalPlayback,
                imageHoldMillis = 1_000L,
            ),
        )
    }
}
