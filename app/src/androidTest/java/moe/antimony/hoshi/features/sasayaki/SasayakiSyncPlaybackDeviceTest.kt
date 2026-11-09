package moe.antimony.hoshi.features.sasayaki

import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.SasayakiMatch
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.epub.SasayakiPlaybackData
import moe.antimony.hoshi.features.sync.SyncPlayback
import moe.antimony.hoshi.features.sync.SyncStorage
import moe.antimony.hoshi.features.sync.Timestamped
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Generated audio and scratch book storage only; no application settings, service or account. */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SasayakiSyncPlaybackDeviceTest {
    @Test fun overlappingExportsOfOneCueKeepBothClipsIntact() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = kotlin.io.path.createTempDirectory(context.cacheDir.toPath(), "sasayaki-clips-").toFile()
        try {
            val source = SasayakiPlaybackSource.PrivateFile(File(root, "fixture.wav").also(::writeSilentWave))
            val cue = SasayakiMatch("cue", 0.0, 2.0, "fixture", 0, 0, 7)
            val exporter = SasayakiCueAudioExporter(context, outputRoot = root.resolve("clips"))
            val (short, long) = withContext(Dispatchers.IO) {
                awaitAll(
                    async { exporter.export(source, cue, SasayakiCueAudioRange(0.0, 1.0)) },
                    async { exporter.export(source, cue, SasayakiCueAudioRange(0.0, 2.0)) },
                ).map { requireNotNull(it) }
            }
            assertNotEquals(short, long)
            assertTrue(short.length() > 0)
            assertTrue(long.length() > short.length())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun localSyncEchoKeepsPlayingForwardAndChangedRemotePositionStillSeeks() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "sasayaki-sync-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val books = BookRepository(root, Dispatchers.IO)
        val store = SyncStorage(root, books, Dispatchers.IO)
        var controller: SasayakiPlaybackController? = null
        var player: ExoPlayer? = null
        try {
            val bookRoot = books.createBookDirectory("fixture")
            books.saveMetadata(bookRoot, BookMetadata("fixture", "Fixture", folder = "fixture", lastAccess = 0.0))
            val audio = File(bookRoot, "Sasayaki/fixture.wav").apply { parentFile!!.mkdirs() }
            writeSilentWave(audio)
            val initial = SasayakiPlaybackData(2.0, audioFileName = audio.name)
            books.saveSasayakiPlayback(bookRoot, initial)
            store.prepareLibrary()
            val playback = withContext(Dispatchers.Main) {
                val decoder = ExoPlayer.Builder(context).build().also { player = it }
                SasayakiPlaybackController(
                    context, bookRoot, BookSasayakiPlaybackRepository(bookRoot, books), "Fixture", null,
                    SasayakiMatchData(listOf(SasayakiMatch("cue", 0.0, 20.0, "fixture", 0, 0, 7)), 0),
                    initial, scope, { 0 }, { _, _, _ -> }, {},
                    ServiceOwnedSasayakiPlaybackPreparer { ExoPlayerSasayakiPlayerHandle(decoder) },
                    Dispatchers.IO,
                ).also { controller = it; it.togglePlayback() }
            }
            withTimeout(10_000) {
                while (!withContext(Dispatchers.Main) { player!!.isPlaying && player!!.currentPosition >= 3_000 }) delay(25)
            }
            playback.flushSaves()
            // Hold the same storage lock used during reconciliation. Audio keeps playing while
            // later saves wait, so the merged local snapshot legitimately trails the live cue.
            store.transaction {
                val snapshot = store.loadBook("fixture")!!
                val savedPosition = (snapshot.audiobook!!.value.lastPosition * 1000).toLong()
                withTimeout(5_000) {
                    while (!withContext(Dispatchers.Main) { player!!.currentPosition > savedPosition + 1_200 }) delay(25)
                }
                store.applyReaderState = { _, _, _, audiobookChanged, _ ->
                    if (audiobookChanged) withContext(Dispatchers.Main.immediate) {
                        playback.applySyncedPlayback(books.loadSasayakiPlayback(bookRoot)!!)
                    }
                }
                val before = withContext(Dispatchers.Main) { player!!.currentPosition }
                store.applyBook("fixture", snapshot)
                val after = withContext(Dispatchers.Main) { player!!.currentPosition }
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("syncPlayback", "localSnapshotMs=$savedPosition beforeMs=$before afterMs=$after")
                })
                assertTrue("Local echo rewound the cue: $before -> $after (saved $savedPosition)", after >= before)
                assertTrue(withContext(Dispatchers.Main) { playback.isPlaying })

                store.applyBook("fixture", snapshot.copy(audiobook = Timestamped(
                    snapshot.audiobook.modified + 1, SyncPlayback(1.0, 0.0, 1.0),
                )))
                assertTrue("A changed remote position must still be applied", withContext(Dispatchers.Main) { player!!.currentPosition < 2_000 })
            }
        } finally {
            store.applyReaderState = null
            withContext(Dispatchers.Main) { controller?.release(); player?.release() }
            scope.coroutineContext[Job]!!.cancelAndJoin()
            root.deleteRecursively()
        }
    }

    private fun writeSilentWave(file: File) {
        val pcmBytes = 16_000 * 20 * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcmBytes).put("WAVEfmt ".toByteArray()).putInt(16)
        header.putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(pcmBytes)
        file.outputStream().use { it.write(header.array()); it.write(ByteArray(pcmBytes)) }
    }
}
