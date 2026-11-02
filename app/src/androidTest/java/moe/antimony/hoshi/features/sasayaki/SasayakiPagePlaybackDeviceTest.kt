package moe.antimony.hoshi.features.sasayaki

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import moe.antimony.hoshi.epub.SasayakiMatch
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.epub.SasayakiPlaybackData
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The service controller/Media3 path with silent generated audio; no user book, account or service session. */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SasayakiPagePlaybackDeviceTest {
    @Test fun detachedReaderStillStopsBeforeOverlappingNextPageAndExplicitTurnsRespectDelay() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "paragraph-audio-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val audio = File(root, "Sasayaki/fixture.wav").apply { parentFile!!.mkdirs() }
        val bytes = 16_000 * 10 * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVEfmt ".toByteArray()).putInt(16)
        header.putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(bytes)
        audio.outputStream().use { it.write(header.array()); it.write(ByteArray(bytes)) }
        var engine: ExoPlayer? = null
        var controller: SasayakiPlaybackController? = null
        val attachment = SasayakiReaderAttachment()
        val cues = mutableListOf<String>()
        val repository = object : SasayakiPlaybackRepository {
            override suspend fun load(): SasayakiPlaybackData? = null
            override suspend fun save(playback: SasayakiPlaybackData) = Unit
        }
        try {
            val playback = withContext(Dispatchers.Main) {
                val decoder = ExoPlayer.Builder(context).build().also { engine = it }
                attachment.attach({ 0 }, { cue, _, _ -> cues += cue.id }, {})
                SasayakiPlaybackController(context, root, repository, "Paragraph fixture", null,
                    SasayakiMatchData(listOf(
                        SasayakiMatch("page", 0.0, 3.0, "page", 0, 0, 4),
                        SasayakiMatch("next", 1.8, 3.0, "next", 0, 4, 4),
                        SasayakiMatch("chapter", 4.0, 5.0, "chapter", 1, 0, 7),
                    ), 0), SasayakiPlaybackData(0.0, audioFileName = audio.name), scope,
                    attachment::currentChapterIndex, attachment::cue, attachment::clearCue,
                    ServiceOwnedSasayakiPlaybackPreparer { ExoPlayerSasayakiPlayerHandle(decoder) }, Dispatchers.IO,
                ).also { controller = it; it.handlePageChanged(0, listOf("page"), true) }
            }
            await { playback.isPlaying && engine!!.isPlaying }
            await { !playback.isPlaying && playback.currentTime >= 1.75 }
            delay(400) // A paused handler must not schedule another tick and reveal the next page.
            withContext(Dispatchers.Main) {
                assertTrue("Page stop overran: ${playback.currentTime}", playback.currentTime < 2.3)
                assertFalse(cues.contains("next"))
                attachment.detach()
                playback.setDelay(-0.5)
                playback.handlePageChanged(1, listOf("chapter"), false)
            }
            await { kotlin.math.abs(playback.currentTime - 3.5) < 0.05 }
            withContext(Dispatchers.Main) {
                assertFalse(playback.isPlaying)
                playback.handlePageChanged(1, emptyList(), true)
                assertFalse(playback.isPlaying)
                assertEquals(3.5, playback.currentTime, 0.05)
                playback.setDelay(0.5)
                playback.handlePageChanged(0, listOf("page"), true)
            }
            await { playback.isPlaying }
            await { !playback.isPlaying && playback.currentTime >= 2.25 }
            withContext(Dispatchers.Main) {
                playback.handlePageChanged(0, listOf("page"), true)
            }
            await { playback.isPlaying && playback.currentTime < 1.5 }
            withContext(Dispatchers.Main) { playback.clearPageRange() }
            await { playback.isPlaying && playback.currentTime > 2.6 }
        } finally {
            withContext(Dispatchers.Main) { controller?.release(); engine?.release() }
            scope.coroutineContext[Job]!!.cancelAndJoin()
            root.deleteRecursively()
        }
    }

    private suspend fun await(check: () -> Boolean) = withTimeout(10_000) {
        while (!withContext(Dispatchers.Main) { check() }) delay(25)
    }
}
