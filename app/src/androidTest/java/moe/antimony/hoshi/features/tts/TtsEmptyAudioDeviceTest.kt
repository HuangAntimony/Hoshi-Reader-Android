package moe.antimony.hoshi.features.tts

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real Translator requests with isolated files/settings/player; never adds an Anki card. */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class TtsEmptyAudioDeviceTest {
    @Test fun translatorEmptyNarrationPreservesDialoguePlaybackReplayAndExport() = runBlocking<Unit> {
        assumeTrue("Supply ttsEmptyAudioLive=true to use Microsoft Translator",
            InstrumentationRegistry.getArguments().getString("ttsEmptyAudioLive") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "tts-empty-audio-${UUID.randomUUID()}")
        val transport = OnlineTtsTransport()
        val router = TtsEngineRouter(AndroidTtsEngine(context),
            OnlineTtsEngine(transport, { _, _ -> error("No subscription key expected") }, Dispatchers.IO),
            CustomTtsEngine(transport, { _, _ -> error("No custom source expected") }, Dispatchers.IO))
        val audio = TtsAudioRepository(router, directory, Dispatchers.IO, AndroidTtsExportFallback(context))
        val preferences = TtsPreferences(TtsSettings(engine = TtsOnlineSource.MicrosoftTranslator.id)
            .withRouting(TtsVoiceRouting(true, "ja-JP-NanamiNeural", "ja-JP-MayuNeural")))
        val sentences = TtsText.chapter(0, "<p>──『肉の塊』がそこに居た。</p>")
        try {
            assertEquals(listOf("──『肉の塊』", "がそこに居た。"), sentences.map { it.text })
            val silent = TtsSentence(0, 0, 0, "──")
            assertTrue(audio.audio(silent, "fixture", preferences).isEmpty())
            val error = runCatching { audio.export(silent, "fixture", preferences) }.exceptionOrNull()
            assertEquals(TtsFailure.EmptyText, (error as TtsException).failure)
            val first = audio.audio(sentences.first(), "fixture", preferences)
            assertEquals(1, first.size)
            assertEquals(first, audio.audio(sentences.first(), "fixture", preferences))
            val next = audio.audio(sentences.last(), "fixture", preferences)
            assertEquals(1, next.size)
            // Preview and Anki use this same export entrypoint. Verify the resulting file is playable.
            val exported = audio.export(sentences.first(), "fixture", preferences)
            assertTrue(exported.length() > 0)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(exported.absolutePath)
                assertTrue(extractor.trackCount > 0)
                assertEquals("audio/mpeg", extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
                assertTrue(extractor.getTrackFormat(0).getLong(MediaFormat.KEY_DURATION) > 0)
            } finally { extractor.release() }
            withContext(Dispatchers.Main) {
                val player = ExoPlayer.Builder(context).build()
                val ended = CompletableDeferred<Unit>()
                var played = 0
                try {
                    player.addListener(object : Player.Listener {
                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { if (mediaItem != null) played++ }
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_ENDED) ended.complete(Unit)
                        }
                        override fun onPlayerError(error: PlaybackException) { ended.completeExceptionally(error) }
                    })
                    // Sentence, its replay, the following sentence, and the preview/export file.
                    player.setMediaItems((first + first + next + exported).map { MediaItem.fromUri(it.toURI().toString()) })
                    player.setPlaybackSpeed(2f)
                    player.prepare(); player.play()
                    withTimeout(30_000) { ended.await() }
                    assertEquals(4, played)
                } finally { player.release() }
            }
            Log.i("HoshiTtsEmptyAudio", "PASS: empty narration skipped; dialogue, replay, next sentence and preview/export played; exportedBytes=${exported.length()}")
        } finally { audio.clearPlaybackCache(); directory.deleteRecursively() }
    }
}
