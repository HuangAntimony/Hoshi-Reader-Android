package moe.antimony.hoshi.features.sasayaki

import android.content.Context
import android.media.session.MediaController as PlatformMediaController
import android.media.session.PlaybackState
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.antimony.hoshi.epub.SasayakiMatch
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.epub.SasayakiPlaybackData
import moe.antimony.hoshi.features.bookshelf.AndroidBookCoverThumbnailEncoder
import moe.antimony.hoshi.features.bookshelf.BookCoverThumbnailStore
import moe.antimony.hoshi.features.playback.ReadingMediaArtworkRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SasayakiMediaSessionSkipDeviceTest {
    @Test
    fun connectedControllerCanSkipAfterTheAudiobookLoads() = runBlocking {
        withPlaybackSession { context, session, playback ->
            val connection = MediaController.Builder(context, session.token).buildAsync()
            try {
                await("Media3 controller connected") { connection.isDone }
                val controller = connection.get()
                assertEquals(Player.STATE_IDLE, controller.playbackState)
                playback.readerSkipButtonAction = SasayakiReaderSkipButtonAction.Seconds5
                assertTrue(playback.restoreAudio())
                await("Media3 controller sees the audiobook ready") { controller.playbackState == Player.STATE_READY }

                assertTrue("Next must remain available after the timeline changes", controller.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
                assertTrue(controller.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
                controller.seekToNext()
                await("next skips ahead five seconds") { session.player.currentPosition == 17_000L }
                controller.seekToPrevious()
                await("previous skips back five seconds") { session.player.currentPosition == 12_000L }

                controller.seekForward()
                await("fast-forward uses the same jump") { session.player.currentPosition == 17_000L }
                controller.seekBack()
                await("rewind uses the same jump") { session.player.currentPosition == 12_000L }
                controller.seekTo(9_000L)
                await("scrubbing seeks to an absolute position") { session.player.currentPosition == 9_000L }
                assertFalse(session.player.playWhenReady)
            } finally {
                MediaController.releaseFuture(connection)
            }
        }
    }

    @Test
    fun platformSessionAdvertisesSkipActionsAndHandlesMediaKeys() = runBlocking {
        withPlaybackSession { context, session, playback ->
            val controller = PlatformMediaController(context, session.platformToken)
            assertTrue(playback.restoreAudio())
            await("platform controller sees paused audiobook") {
                controller.playbackState?.state == PlaybackState.STATE_PAUSED &&
                    session.player.playbackState == Player.STATE_READY
            }

            val expectedActions = PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SKIP_TO_NEXT
            assertEquals(expectedActions, requireNotNull(controller.playbackState).actions and expectedActions)

            controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT))
            controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT))
            await("next media key jumps to the next sentence") { session.player.currentPosition == 20_000L }
            controller.transportControls.skipToPrevious()
            await("previous transport action jumps to the previous sentence") { session.player.currentPosition == 12_000L }
            assertFalse(session.player.playWhenReady)
        }
    }

    private suspend fun withPlaybackSession(
        test: suspend (Context, MediaSession, SasayakiPlaybackController) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = createTempDirectory(context.cacheDir.toPath(), "sasayaki-media-skip-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val audio = root.resolve("Sasayaki/fixture.wav").apply { parentFile!!.mkdirs() }
            writeSilentWave(audio)
            val initial = SasayakiPlaybackData(12.0, audioFileName = audio.name)
            val repository = object : SasayakiPlaybackRepository {
                override suspend fun load() = initial
                override suspend fun save(playback: SasayakiPlaybackData) = Unit
                override suspend fun saveVolumeGainDb(value: Int) = Unit
            }
            val matches = SasayakiMatchData(
                listOf(
                    SasayakiMatch("first", 12.0, 15.0, "First sentence", 0, 0, 14),
                    SasayakiMatch("next", 20.0, 23.0, "Next sentence", 0, 14, 13),
                ),
                0,
            )
            val artwork = ReadingMediaArtworkRepository(
                BookCoverThumbnailStore(root, AndroidBookCoverThumbnailEncoder(), Dispatchers.IO),
                Dispatchers.IO,
            )
            withContext(Dispatchers.Main) {
                val runtime = SasayakiPlaybackServiceRuntime(context, scope, Dispatchers.IO, artwork)
                try {
                    val session = runtime.createServiceSession(context)
                    val playback = runtime.load(
                        SasayakiPlaybackRuntimeLoadRequest(root.name, root, repository, "Media skip fixture", null, matches, initial),
                        getCurrentChapterIndex = { 0 },
                        onCue = { _, _, _ -> },
                        onClearCue = {},
                    ) as SasayakiPlaybackController
                    test(context, session, playback)
                } finally {
                    runtime.release()
                }
            }
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    private suspend fun await(description: String, check: () -> Boolean) {
        val reached = withTimeoutOrNull(10_000) {
            while (!check()) delay(25)
            true
        }
        assertTrue("Timed out waiting for $description", reached == true)
    }

    private fun writeSilentWave(file: File) {
        val pcmBytes = 16_000 * 30 * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(36 + pcmBytes).put("WAVEfmt ".toByteArray()).putInt(16)
            putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
            put("data".toByteArray()).putInt(pcmBytes)
        }
        file.outputStream().use { it.write(header.array()); it.write(ByteArray(pcmBytes)) }
    }
}
