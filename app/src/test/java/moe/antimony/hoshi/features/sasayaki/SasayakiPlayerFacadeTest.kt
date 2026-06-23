package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiPlaybackData
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.epub.SasayakiMatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SasayakiPlayerFacadeTest {
    private val cue = SasayakiMatch("cue", 1.0, 2.0, "text", 0, 3, 4)

    @Test
    fun exposesControllerStateForComposeCallers() {
        val controller = FakeSasayakiPlaybackController()
        val player = playerFor(controller)

        assertSame(controller.playback, player.playback)
        assertEquals(12.5, player.currentTime, 0.0)
        assertEquals(90.0, player.duration, 0.0)
        assertTrue(player.isPlaying)
        assertEquals(UiText.Literal("restore failed"), player.errorMessage)
        assertFalse(player.autoScroll)
        assertTrue(player.hasAudio)
        assertTrue(player.hasMatch)
        assertEquals(0.35, player.delay, 0.0)
        assertEquals(1.25f, player.rate, 0.0f)
        assertEquals("content URI", player.audioStorageSummary)

        player.autoScroll = true
        player.readerSkipButtonAction = SasayakiReaderSkipButtonAction.Seconds10

        assertTrue(controller.autoScroll)
        assertEquals(SasayakiReaderSkipButtonAction.Seconds10, controller.readerSkipButtonAction)
    }

    @Test
    fun delegatesPlaybackCommandsToControllerFacade() {
        val controller = FakeSasayakiPlaybackController()
        val player = playerFor(controller)

        player.setDelay(0.5)
        player.setRate(1.5f)
        player.clearAudio()
        player.togglePlayback()
        player.pausePlayback()
        player.pausePlayback(restoreTemporaryPosition = false)
        player.nextCue()
        player.previousCue()
        player.skipForward(10)
        player.skipBackward(5)
        player.seekTo(40.0)
        assertSame(cue, player.findCue(chapterIndex = 2, offset = 10))
        player.playCue(cue, stop = true)
        assertEquals(File("cue.m4a"), player.exportCueAudio(cue, "sentence"))

        assertEquals(
            listOf(
                "setDelay:0.5",
                "setRate:1.5",
                "clearAudio",
                "togglePlayback",
                "pausePlayback:true",
                "pausePlayback:false",
                "nextCue",
                "previousCue",
                "skipForward:10",
                "skipBackward:5",
                "seekTo:40.0",
                "findCue:2:10",
                "playCue:cue:true",
                "exportCueAudio:cue:sentence",
            ),
            controller.commands,
        )
    }

    @Test
    fun serviceRuntimePlayerReleaseDetachesReaderWithoutReleasingPlayback() {
        val controller = FakeSasayakiPlaybackController()
        val runtime = FakeSasayakiPlaybackRuntime(controller)
        val player = SasayakiPlayer(
            bookId = "book-id",
            bookRoot = File("book"),
            playbackRepository = NoOpPlaybackRepository,
            bookTitle = "Book",
            bookCoverFile = null,
            matchData = null,
            initialPlayback = null,
            getCurrentChapterIndex = { 0 },
            onCue = { _, _ -> },
            onClearCue = {},
            onLoadChapter = {},
            playbackServiceRuntime = runtime,
        )

        player.release()
        player.stopPlayback()

        assertEquals(listOf("load", "detachReader", "stopPlayback"), runtime.commands)
        assertEquals(emptyList<String>(), controller.commands)
    }

    @Test
    fun runtimeLoadRequestDoesNotCarryReaderCoroutineScope() {
        val fieldNames = SasayakiPlaybackRuntimeLoadRequest::class.java.declaredFields
            .map { it.name }

        assertFalse(
            "Sasayaki playback persistence must be owned by the service runtime, not Reader's Compose scope.",
            "persistenceScope" in fieldNames,
        )
    }

    private fun playerFor(controller: SasayakiPlaybackControllerContract): SasayakiPlayer =
        SasayakiPlayer(
            bookId = "book-id",
            bookRoot = File("book"),
            playbackRepository = NoOpPlaybackRepository,
            bookTitle = "Book",
            bookCoverFile = null,
            matchData = null,
            initialPlayback = null,
            getCurrentChapterIndex = { 0 },
            onCue = { _, _ -> },
            onClearCue = {},
            onLoadChapter = {},
            playbackServiceRuntime = FakeSasayakiPlaybackRuntime(controller),
        )

    private object NoOpPlaybackRepository : SasayakiPlaybackRepository {
        override suspend fun load(): SasayakiPlaybackData? = null

        override suspend fun save(playback: SasayakiPlaybackData) = Unit
    }

    private class FakeSasayakiPlaybackRuntime(
        private val controller: SasayakiPlaybackControllerContract,
    ) : SasayakiPlaybackRuntime {
        val commands = mutableListOf<String>()

        override fun load(
            request: SasayakiPlaybackRuntimeLoadRequest,
            getCurrentChapterIndex: () -> Int,
            onCue: (SasayakiMatch, Boolean) -> Unit,
            onClearCue: () -> Unit,
            onLoadChapter: (Int) -> Unit,
        ): SasayakiPlaybackControllerContract {
            commands += "load"
            return controller
        }

        override fun detachReader() {
            commands += "detachReader"
        }

        override fun stopPlayback() {
            commands += "stopPlayback"
        }
    }

    private inner class FakeSasayakiPlaybackController : SasayakiPlaybackControllerContract {
        override val playback = SasayakiPlaybackData(lastPosition = 12.0, delay = 0.35, rate = 1.25f)
        override val currentTime = 12.5
        override val duration = 90.0
        override val isPlaying = true
        override val errorMessage = UiText.Literal("restore failed")
        override var autoScroll = false
        override var readerSkipButtonAction = SasayakiReaderSkipButtonAction.Cue
        override val hasAudio = true
        override val hasMatch = true
        override val delay = 0.35
        override val rate = 1.25f
        override val audioStorageSummary = "content URI"
        val commands = mutableListOf<String>()
        override fun setDelay(value: Double) {
            commands += "setDelay:$value"
        }

        override fun setRate(value: Float) {
            commands += "setRate:$value"
        }

        override fun importAudio(audioUri: android.net.Uri, copiedAudioFileName: String?) {
            commands += "importAudio:$copiedAudioFileName"
        }

        override fun clearAudio() {
            commands += "clearAudio"
        }

        override fun togglePlayback() {
            commands += "togglePlayback"
        }

        override fun pausePlayback(restoreTemporaryPosition: Boolean) {
            commands += "pausePlayback:$restoreTemporaryPosition"
        }

        override fun nextCue() {
            commands += "nextCue"
        }

        override fun previousCue() {
            commands += "previousCue"
        }

        override fun skipForward(seconds: Int) {
            commands += "skipForward:$seconds"
        }

        override fun skipBackward(seconds: Int) {
            commands += "skipBackward:$seconds"
        }

        override fun seekTo(seconds: Double) {
            commands += "seekTo:$seconds"
        }

        override fun findCue(chapterIndex: Int, offset: Int): SasayakiMatch? {
            commands += "findCue:$chapterIndex:$offset"
            return cue
        }

        override fun playCue(cue: SasayakiMatch, stop: Boolean) {
            commands += "playCue:${cue.id}:$stop"
        }

        override fun exportCueAudio(cue: SasayakiMatch, sentence: String): File? {
            commands += "exportCueAudio:${cue.id}:$sentence"
            return File("cue.m4a")
        }

        override fun release() {
            commands += "release"
        }
    }
}
