package moe.antimony.hoshi.features.tts

import android.content.Context
import androidx.annotation.OptIn
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Public samples, isolated player/cache and in-memory preferences; preserves the user's reading session. */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class TtsContinuousPlaybackDeviceTest {
    @Test fun fastEdgePlaybackCompletesEverySentenceWithPrefetch() = checkPlayback(2)
    @Test fun fastEdgePlaybackCompletesShortSentencesWithSixPrefetchedSentences() = checkPlayback(6)

    private fun checkPlayback(prefetchSentences: Int) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "tts-continuous-device-test")
        val router = TtsEngineRouter(AndroidTtsEngine(context),
            OnlineTtsEngine(OnlineTtsTransport(), TtsCredentialCipher(Dispatchers.IO), Dispatchers.IO), CustomTtsEngine(OnlineTtsTransport(), { _, _ -> throw TtsException(TtsFailure.Configuration) }, kotlinx.coroutines.Dispatchers.IO))
        val audio = TtsAudioRepository(router, directory, Dispatchers.IO, AndroidTtsExportFallback(context))
        val repository = TtsRepository(MemoryStore())
        repository.settings { TtsSettings(engine = TtsOnlineSource.Edge.id,
            prefetchSentences = prefetchSentences).withVoice("ja-JP-NanamiNeural").withRate(2f) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            withContext(Dispatchers.Main) {
                val player = ExoPlayer.Builder(context).build()
                val output = DirectOutput(player)
                val controller = TtsPlaybackController(TtsBookRepository(Dispatchers.IO), repository, audio, output, scope)
                val text = if (prefetchSentences == 6) "一。二。三。四。五。六。七。八。"
                    else "今日は晴れです。次の文章です。日本語を読んでいます。これが最後の文章です。"
                val book = EpubBook("Public sample", chapters = listOf(EpubChapter("one", "one.xhtml",
                    "application/xhtml+xml", "<p>$text</p>")))
                try {
                    controller.load("continuous-fixture", book, false)
                    controller.prepareAt(0, 0)
                    val loaded = withTimeout(5000) { controller.state.first { it.sentences.isNotEmpty() } }
                    assertEquals(if (prefetchSentences == 6) 8 else 4, loaded.sentences.size)
                    controller.play()
                    val finished = withTimeout(60_000) {
                        controller.state.first { it.error != null || it.started && it.index == loaded.sentences.lastIndex && !it.playing }
                    }
                    assertNull(finished.error)
                    assertEquals(loaded.sentences.lastIndex, finished.index)
                    assertEquals(loaded.sentences.map { it.text }, output.sentences)
                } finally { controller.stop(); player.release() }
            }
        } finally {
            scope.cancel()
            audio.clearPlaybackCache()
            directory.deleteRecursively()
        }
    }

    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
            transform(data.value).also { data.value = it }
    }

    private class DirectOutput(private val player: ExoPlayer) : TtsPlaybackOutput {
        override var onEnded: () -> Unit = {}
        override var onPaused: () -> Unit = {}
        override var onError: () -> Unit = {}
        val sentences = mutableListOf<String>()
        init {
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) onEnded()
                }
                override fun onPlayerError(error: PlaybackException) = onError()
            })
        }
        override suspend fun connect() = Unit
        override fun prepare(files: List<File>, title: String, sentence: String) {
            sentences += sentence
            player.setMediaItems(files.map { MediaItem.fromUri(it.toURI().toString()) })
            player.prepare()
        }
        override fun setPlaybackRate(rate: Float) = player.setPlaybackSpeed(rate)
        override fun play() = player.play()
        override fun pause() = player.pause()
        override fun clear() { player.stop(); player.clearMediaItems() }
        override fun disconnect() = Unit
    }
}
