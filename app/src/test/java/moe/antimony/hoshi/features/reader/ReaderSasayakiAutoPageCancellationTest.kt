package moe.antimony.hoshi.features.reader

import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.features.sasayaki.SasayakiCueRevealSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSasayakiAutoPageCancellationTest {
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
    fun openFullscreenImageCancelsAutoPageBeforeShowingImage() {
        val events = mutableListOf<String>()
        val resource = ReaderWebResource(mediaType = "image/png", encoding = null, data = byteArrayOf(1))

        val opened = openReaderFullscreenImage(
            sourceUrl = "https://reader.example/image.png",
            imageResourceForUrl = {
                events += "resolve:$it"
                resource
            },
            closeLookupPopupsAndSelection = { events += "close-popups" },
            cancelAutoPage = { events += "cancel-auto-page" },
            showFullscreenImage = { image ->
                events += "show:${image.sourceUrl}:${image.resource.mediaType}"
            },
        )

        assertTrue(opened)
        assertEquals(
            listOf(
                "resolve:https://reader.example/image.png",
                "cancel-auto-page",
                "close-popups",
                "show:https://reader.example/image.png:image/png",
            ),
            events,
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
            ),
        )
        assertEquals(
            ReaderSasayakiTargetChapterMediaPolicy.DirectRestore,
            readerSasayakiTargetChapterMediaPolicy(
                currentChapterIndex = 2,
                cueChapterIndex = 3,
                source = SasayakiCueRevealSource.DirectJump,
            ),
        )
        assertEquals(
            ReaderSasayakiTargetChapterMediaPolicy.DirectRestore,
            readerSasayakiTargetChapterMediaPolicy(
                currentChapterIndex = 3,
                cueChapterIndex = 2,
                source = SasayakiCueRevealSource.NaturalPlayback,
            ),
        )
    }
}
