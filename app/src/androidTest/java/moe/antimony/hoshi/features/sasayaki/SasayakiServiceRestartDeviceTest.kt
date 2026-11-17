package moe.antimony.hoshi.features.sasayaki

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.antimony.hoshi.MainActivity
import moe.antimony.hoshi.epub.SasayakiMatch
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.epub.SasayakiPlaybackData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the app's MediaSessionService with generated media and scratch storage. */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SasayakiServiceRestartDeviceTest {
    @Test
    fun sameReaderRestartsSavedAudioAndReceivesFollowingCuesAfterServiceStops() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = createTempDirectory(context.cacheDir.toPath(), "sasayaki-service-restart-").toFile()
        val audio = root.resolve("Sasayaki/fixture.wav").apply { parentFile!!.mkdirs() }
        val pcmBytes = 16_000 * 12 * 2
        audio.writeBytes(ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(36 + pcmBytes).put("WAVEfmt ".toByteArray()).putInt(16)
            putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
            put("data".toByteArray()).putInt(pcmBytes)
        }.array())
        val initial = SasayakiPlaybackData(1.0, audioFileName = audio.name)
        val repository = object : SasayakiPlaybackRepository {
            override suspend fun load() = initial
            override suspend fun save(playback: SasayakiPlaybackData) = Unit
            override suspend fun saveVolumeGainDb(value: Int) = Unit
        }
        val cues = mutableListOf<Pair<String, Boolean>>()
        val serviceIntent = Intent(context, SasayakiPlaybackService::class.java)
        var runtime: SasayakiPlaybackServiceRuntime? = null

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var playback: SasayakiPlayer
                scenario.onActivity { activity ->
                    val serviceRuntime = activity.uiDependencies.sasayakiPlaybackServiceRuntime
                    assertNull(serviceRuntime.currentSession())
                    runtime = serviceRuntime
                    playback = SasayakiPlayer(
                        bookId = root.name,
                        bookRoot = root,
                        playbackRepository = repository,
                        bookTitle = "Service restart fixture",
                        bookCoverFile = null,
                        matchData = SasayakiMatchData(listOf(
                            SasayakiMatch("first", 0.0, 3.0, "fixture one", 0, 0, 11),
                            SasayakiMatch("next", 3.0, 12.0, "fixture two", 1, 0, 11),
                        ), 0),
                        initialPlayback = initial,
                        getCurrentChapterIndex = { 0 },
                        onCue = { cue, reveal, _ -> cues += cue.id to reveal },
                        onClearCue = {},
                        playbackServiceRuntime = serviceRuntime,
                    ).apply { autoScroll = true }
                    playback.togglePlayback()
                }
                val playbackRuntime = requireNotNull(runtime)
                await { playbackRuntime.currentSession()?.player?.isPlaying == true }
                withContext(Dispatchers.Main) {
                    playback.pausePlayback()
                    playback.seekTo(2.5)
                }
                await { playback.currentTime == 2.5 }
                withContext(Dispatchers.Main) {
                    // Unbind the test client so Android can destroy the stopped service.
                    playbackRuntime.releasePlaybackServiceConnection()
                    context.stopService(serviceIntent)
                }
                await { playbackRuntime.currentSession() == null }
                withContext(Dispatchers.Main) {
                    assertTrue("The open reader should still offer playback", playback.hasAudio)
                    assertFalse(playback.isPlaying)
                    assertEquals(2.5, playback.currentTime, 0.001)
                    cues.clear()
                    playback.togglePlayback()
                }
                await { playbackRuntime.currentSession()?.player?.isPlaying == true }
                await { ("first" to true) in cues && ("next" to true) in cues }
            }
        } finally {
            withContext(Dispatchers.Main) {
                runtime?.stopPlayback()
                context.stopService(serviceIntent)
            }
            root.deleteRecursively()
        }
    }

    private suspend fun await(check: () -> Boolean) = withTimeout(10_000) {
        while (!withContext(Dispatchers.Main) { check() }) delay(25)
    }
}
