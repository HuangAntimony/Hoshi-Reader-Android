package moe.antimony.hoshi.features.tts

import android.content.Context
import androidx.annotation.OptIn
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import org.junit.Assert.*
import org.junit.Test

/** Real completion callbacks, local silence and isolated preferences; no engine or user data. */
@OptIn(UnstableApi::class)
class TtsLookupPauseDeviceTest {
    @Test fun lookupPauseAtDecoderEndResumesTheNextSentence() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("tts-lookup-", ".wav", context.cacheDir)
        val samples = 4000
        file.writeBytes(ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
            repeat(samples) { putShort(0) }
        }.array())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            withContext(Dispatchers.Main) {
                val player = ExoPlayer.Builder(context).build()
                val output = AndroidTtsPlaybackOutput(context)
                output.attach(player)
                // Connection is already owned by this isolated fixture instead of the app service.
                val connected = object : TtsPlaybackOutput by output { override suspend fun connect() = Unit }
                val audio = object : TtsAudioSource {
                    override suspend fun audio(sentence: TtsSentence, bookId: String?, preferences: TtsPreferences) = listOf(file)
                    override suspend fun clearPlaybackCache() = Unit
                }
                val store = object : DataStore<Preferences> {
                    override val data = MutableStateFlow(emptyPreferences())
                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                        transform(data.value).also { data.value = it }
                }
                val controller = TtsPlaybackController(TtsBookRepository(Dispatchers.IO), TtsRepository(store), audio, connected, scope)
                val lookupPaused = CompletableDeferred<Unit>()
                val nextPlaying = CompletableDeferred<Unit>()
                var resume: (() -> Unit)? = null
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED && resume == null) {
                            // Lookup wins the main-thread race before output's batched completion event.
                            resume = controller.pauseForLookup()
                            lookupPaused.complete(Unit)
                        }
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying && controller.state.value.index == 1) nextPlaying.complete(Unit)
                    }
                })
                try {
                    val book = EpubBook("Fixture", chapters = listOf(EpubChapter("one", "one.xhtml", "application/xhtml+xml", "<p>一。二。</p>")))
                    controller.load("lookup-fixture", book, false)
                    withTimeout(5000) { controller.state.first { it.sentences.size == 2 } }
                    controller.play()
                    withTimeout(5000) { lookupPaused.await() }
                    // Let the remaining listener batch deliver completion while paused.
                    yield()
                    assertFalse(controller.state.value.playing)
                    assertEquals(Player.STATE_ENDED, player.playbackState)
                    requireNotNull(resume).invoke()
                    withTimeout(5000) { nextPlaying.await() }
                    assertEquals(1, controller.state.value.index)
                    assertNull(controller.state.value.error)
                } finally { controller.stop(); output.detach(); player.release() }
            }
        } finally { scope.cancel(); file.delete() }
    }
}
