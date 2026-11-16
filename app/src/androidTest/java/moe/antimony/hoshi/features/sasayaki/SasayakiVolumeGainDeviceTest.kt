package moe.antimony.hoshi.features.sasayaki

import android.content.Context
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.ui.resolve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SasayakiVolumeGainDeviceTest {
    @Test
    fun restoresBookGainAndAdjustsTheActiveTrackWithoutSeeking() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "audiobook-gain-${System.nanoTime()}")
        val books = BookRepository(root)
        val firstBook = createBook(root, "first")
        val secondBook = createBook(root, "second")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val audioManager = context.getSystemService(AudioManager::class.java)
        val systemVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        var player: ExoPlayerSasayakiPlayerHandle? = null
        var controller: SasayakiPlaybackController? = null
        try {
            assertEquals(0, books.loadSasayakiPlayback(firstBook)!!.volumeGainDb)
            books.saveSasayakiVolumeGain(firstBook, -6)
            withContext(Dispatchers.Main) {
                player = ExoPlayerSasayakiPlayerHandle.createServicePlayer(context)
            }
            val handle = requireNotNull(player)
            val playback = handle.player
            suspend fun loadBook(book: File): SasayakiPlaybackController {
                val repository = BookSasayakiPlaybackRepository(book, books)
                val initial = repository.load()
                return withContext(Dispatchers.Main) {
                    SasayakiPlaybackController(
                        context = context,
                        bookRoot = book,
                        playbackRepository = repository,
                        bookTitle = book.name,
                        bookCoverFile = null,
                        matchData = null,
                        initialPlayback = initial,
                        persistenceScope = scope,
                        getCurrentChapterIndex = { 0 },
                        onCue = { _, _, _ -> },
                        onClearCue = {},
                        playbackPreparer = ServiceOwnedSasayakiPlaybackPreparer { handle },
                        persistenceDispatcher = Dispatchers.IO,
                        restoreAudioOnCreate = false,
                    ).also { controller = it }
                }
            }
            val first = loadBook(firstBook)
            withContext(Dispatchers.Main) { first.togglePlayback() }
            await { playback.isPlaying }
            val mediaItem = withContext(Dispatchers.Main) {
                assertEquals(-6, first.playback.volumeGainDb)
                assertEquals(0.501187f, playback.volume, 0.000001f)
                playback.currentMediaItem
            }
            for ((position, gain) in listOf(3_000L to 0, 6_000L to 6)) {
                await { playback.currentPosition >= position }
                withContext(Dispatchers.Main) {
                    first.setVolumeGainDb(gain)
                    assertEquals(1f, playback.volume, 0f)
                    assertEquals(mediaItem, playback.currentMediaItem)
                    assertTrue(playback.isPlaying && playback.currentPosition >= position)
                    val error = first.volumeGainError
                    assertNull("Volume gain failure: ${error?.resolve(context)}", error)
                }
            }
            await { playback.currentPosition >= 9_000L }
            assertEquals(-6, books.loadSasayakiPlayback(firstBook)!!.volumeGainDb)
            withContext(Dispatchers.Main) {
                first.saveVolumeGain()
                first.flushSaves()
                first.release()
            }
            assertEquals(6, books.loadSasayakiPlayback(firstBook)!!.volumeGainDb)
            val second = loadBook(secondBook)
            withContext(Dispatchers.Main) {
                second.togglePlayback()
                assertEquals(0, second.playback.volumeGainDb)
                assertEquals(1f, playback.volume, 0f)
            }
            await { playback.isPlaying && playback.currentPosition >= 1_000L }
            withContext(Dispatchers.Main) {
                second.release()
                second.flushSaves()
            }
            val reopened = loadBook(firstBook)
            withContext(Dispatchers.Main) {
                assertEquals(6, reopened.playback.volumeGainDb)
                reopened.togglePlayback()
                assertTrue(playback.playWhenReady)
            }
            await { playback.isPlaying }
            assertEquals(systemVolume, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        } finally {
            withContext(Dispatchers.Main) {
                controller?.release()
                controller?.flushSaves()
                player?.release()
            }
            scope.cancel()
            root.deleteRecursively()
        }
    }

    private suspend fun await(check: () -> Boolean) = withTimeout(12_000) {
        while (!withContext(Dispatchers.Main) { check() }) delay(25)
    }

    private fun createBook(root: File, name: String): File {
        val book = root.resolve(name)
        val audio = book.resolve("Sasayaki/fixture.wav")
        check(audio.parentFile!!.mkdirs())
        book.resolve("sasayaki_playback.json").writeText("""{"lastPosition":0.0,"audioFileName":"fixture.wav"}""")
        val rate = 16_000
        val samples = rate * 12
        val wave = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        wave.put("RIFF".toByteArray()).putInt(wave.capacity() - 8)
        wave.put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        wave.putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        wave.put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { index ->
            wave.putShort((700 * sin(2 * PI * 440 * index / rate)).toInt().toShort())
        }
        audio.writeBytes(wave.array())
        return book
    }
}
