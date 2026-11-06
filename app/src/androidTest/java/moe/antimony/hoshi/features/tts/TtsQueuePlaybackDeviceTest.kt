package moe.antimony.hoshi.features.tts

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
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

/** Generated PCM, isolated players and in-memory settings; no book, account or preference mutations. */
@OptIn(UnstableApi::class)
class TtsQueuePlaybackDeviceTest {
    @Test fun queuedSentencesCrossQuotesAndChaptersWithoutDecoderRestartsOrPresentationWaits() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("tts-queue-", ".wav", context.cacheDir)
        val frames = 9600 // 400 ms; no source silence to confuse application gaps with engine pauses.
        file.writeBytes(ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(36 + frames * 2).put("WAVEfmt ".toByteArray()).putInt(16)
            putShort(1).putShort(1).putInt(24000).putInt(48000).putShort(2).putShort(16)
            put("data".toByteArray()).putInt(frames * 2)
            repeat(frames) { putShort((kotlin.math.sin(it * 2 * Math.PI * 440 / 24000) * 2000).toInt().toShort()) }
        }.array())
        try {
            withContext(Dispatchers.Main) {
                val baseline = legacyPlayback(context, file)
                val streamed = queuedPlayback(context, file)
                val clauses = queuedPlayback(context, file, splitClauses = true)
                assertEquals(0, clauses.rebuffers)
                assertEquals(0, clauses.idle)
                Log.i("HoshiTtsQueue", "legacy=$baseline queued=$streamed contentMs=3600")
                assertEquals("No intermediate buffering after audio starts: $streamed", 0, streamed.rebuffers)
                assertEquals("No intermediate stop/reset: $streamed", 0, streamed.idle)
                assertTrue("Baseline must exercise the old reset path: $baseline", baseline.idle >= 7)
                // Event timing is secondary evidence; buffering/reset counts prove the mechanism.
                assertTrue("Queued playback unexpectedly slower: legacy=$baseline queued=$streamed",
                    streamed.elapsedMs <= baseline.elapsedMs + 150)
            }
        } finally { file.delete() }
    }

    private suspend fun queuedPlayback(context: Context, file: File, splitClauses: Boolean = false): Metrics {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val player = ExoPlayer.Builder(context).build()
        val output = AndroidTtsPlaybackOutput(context).apply { attach(player) }
        var prepares = 0
        var clears = 0
        val connected = object : TtsPlaybackOutput by output {
            override suspend fun connect() = Unit
            override fun prepare(files: List<File>, title: String, sentence: String) {
                prepares++; output.prepare(files, title, sentence)
            }
            override fun clear() { clears++; output.clear() }
        }
        val store = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                transform(data.value).also { data.value = it }
        }
        val repository = TtsRepository(store)
        repository.settings { TtsSettings(enabled = true, prefetchSentences = 8, splitAtClausePunctuation = splitClauses) }
        val audio = object : TtsAudioSource {
            override suspend fun audio(sentence: TtsSentence, bookId: String?, preferences: TtsPreferences) =
                if (sentence.text == "一。") listOf(file, file) else listOf(file)
            override suspend fun clearPlaybackCache() = Unit
        }
        val controller = TtsPlaybackController(TtsBookRepository(Dispatchers.IO), repository, audio, connected, scope)
        val metrics = Metrics().also { player.addAnalyticsListener(it); player.addListener(it) }
        val expected = if (splitClauses) listOf("一文、", "二文。", "三文、", "四文。", "五文；", "六文。", "七文；", "八文。")
            else listOf("一。", "「二」", "と三。", "四。", "五。", "六。", "七。", "八。")
        val positions = mutableListOf<String>()
        try {
            controller.load("queue-fixture", EpubBook("Queue fixture", chapters = listOf(
                EpubChapter("one", "one.xhtml", "application/xhtml+xml", if (splitClauses) "<p>一文、二文。三文、四文。</p>" else "<p>一。「二」と三。四。</p>"),
                EpubChapter("two", "two.xhtml", "application/xhtml+xml", if (splitClauses) "<p>五文；六文。七文；八文。</p>" else "<p>五。六。七。八。</p>"),
            )), false)
            controller.prepareAt(0, 0)
            withTimeout(5000) { controller.state.first { it.sentences.isNotEmpty() } }
            val initialClears = clears
            scope.launch { controller.state.map { it.sentence?.text }.distinctUntilChanged().collect { it?.let(positions::add) } }
            // A slow Reader must not delay the subsequent audio or block queue replenishment.
            controller.presentSentence = { sentence, _ -> if (sentence.text != "一。") delay(1000) }
            controller.play()
            val finished = withTimeout(15_000) {
                controller.state.first { it.error != null || it.sentence?.text == expected.last() && !it.playing }
            }
            assertNull(finished.error)
            assertEquals(expected, positions)
            assertEquals(1, prepares)
            assertEquals(initialClears, clears)
            assertEquals(if (splitClauses) 8 else 9, metrics.items) // First unit contains two voice/provider fragments.
            metrics.finish()
            return metrics
        } finally {
            controller.stop()
            scope.coroutineContext[Job]?.cancelAndJoin()
            output.detach(); player.release()
        }
    }

    /** The former per-sentence stop/clear/prepare sequence, using exactly the same PCM. */
    private suspend fun legacyPlayback(context: Context, file: File): Metrics {
        val player = ExoPlayer.Builder(context).build()
        val metrics = Metrics().also { player.addAnalyticsListener(it); player.addListener(it) }
        val finished = CompletableDeferred<Unit>()
        var unit = 0
        fun prepareUnit() {
            player.stop(); player.clearMediaItems()
            player.setMediaItems(List(if (unit == 0) 2 else 1) { MediaItem.fromUri(file.toURI().toString()) })
            player.prepare(); player.play()
        }
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    unit++
                    if (unit < 8) prepareUnit() else finished.complete(Unit)
                }
            }
            override fun onPlayerError(error: PlaybackException) { finished.completeExceptionally(error) }
        })
        try {
            prepareUnit()
            withTimeout(15_000) { finished.await() }
            metrics.finish()
            return metrics
        } finally { player.release() }
    }

    private class Metrics : AnalyticsListener, Player.Listener {
        var startedAt: Long? = null
        var rebuffers = 0
        var idle = 0
        var items = 0
        var elapsedMs = 0L
        private var finished = false
        override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
            // Media3 supplies wall-clock time; translate once before measuring monotonic elapsed time.
            if (startedAt == null) startedAt = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - playoutStartSystemTimeMs)
        }
        override fun onPlaybackStateChanged(state: Int) {
            if (startedAt != null && !finished) {
                if (state == Player.STATE_BUFFERING) rebuffers++
                if (state == Player.STATE_IDLE) idle++
            }
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { if (mediaItem != null && !finished) items++ }
        fun finish() {
            elapsedMs = SystemClock.elapsedRealtime() - requireNotNull(startedAt)
            finished = true // Fixture teardown is outside the measured playback interval.
        }
        override fun toString() = "elapsedMs=$elapsedMs rebuffers=$rebuffers idle=$idle items=$items"
    }
}
