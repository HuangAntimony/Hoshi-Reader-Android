package moe.antimony.hoshi.features.tts

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
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
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly supplied server, public sample, isolated files/player; never changes application preferences. */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class CustomTtsDeviceTest {
    @Test fun selfHostedVitsCatalogPlaybackCacheAndAnkiExport() = runBlocking {
        val baseUrl = InstrumentationRegistry.getArguments().getString("vitsUrl").orEmpty()
        assumeTrue("Supply vitsUrl to opt into network testing", baseUrl.isNotBlank())
        verifySource(TtsCustomSource("custom:device-fixture", "Device VITS", TtsCustomProtocol.Vits, baseUrl))
    }

    @Test fun selfHostedOpenAiCatalogRefreshPlaybackCacheAndAnkiExport() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val baseUrl = arguments.getString("openAiUrl").orEmpty()
        val model = arguments.getString("openAiModel").orEmpty()
        assumeTrue("Supply openAiUrl and openAiModel to opt into network testing", baseUrl.isNotBlank() && model.isNotBlank())
        verifySource(TtsCustomSource("custom:openai-device-fixture", "Device OpenAI", TtsCustomProtocol.OpenAi, baseUrl, model = model))
    }

    private suspend fun verifySource(initial: TtsCustomSource) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "custom-tts-device-fixture-${initial.protocol}")
        val cipher = TtsCredentialCipher(Dispatchers.IO)
        val transport = OnlineTtsTransport()
        val custom = CustomTtsEngine(transport, cipher, Dispatchers.IO, TtsVoiceCatalogStore(File(directory, "voices")))
        val router = TtsEngineRouter(AndroidTtsEngine(context), OnlineTtsEngine(transport, cipher, Dispatchers.IO), custom)
        val audio = TtsAudioRepository(router, directory, Dispatchers.IO, AndroidTtsExportFallback(context))
        val settings = TtsSettings(engine = initial.id, customSources = listOf(initial))
        val sentence = TtsText.chapter(0, "<p><ruby>今日<rt>きょう</rt></ruby>は晴れです。</p>").single()
        try {
            assertTrue(router.catalog(settings).voices.isNotEmpty())
            val catalog = router.catalog(settings, force = true)
            assertTrue(catalog.voices.isNotEmpty())
            assertEquals(catalog, router.cachedCatalog(settings))
            for (format in listOf(TtsAudioFormat.Wav, TtsAudioFormat.Mp3)) {
                val source = initial.copy(voice = catalog.voices.first().id, rate = 1.5f, format = format)
                val preferences = TtsPreferences(TtsSettings(engine = source.id, customSources = listOf(source), playbackRate = 2f))
                val parts = audio.audio(sentence, "fixture", preferences)
                assertEquals(parts, audio.audio(sentence, "fixture", preferences))
                val exported = audio.export(sentence, "fixture", preferences)
                val extractor = MediaExtractor()
                try {
                    assertEquals(format.name.lowercase(), exported.extension)
                    extractor.setDataSource(exported.absolutePath)
                    assertTrue(extractor.trackCount > 0)
                    assertEquals(if (format == TtsAudioFormat.Wav) "audio/raw" else "audio/mpeg", extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
                    assertTrue(extractor.getTrackFormat(0).getLong(MediaFormat.KEY_DURATION) > 0)
                    withContext(Dispatchers.Main) {
                        val player = ExoPlayer.Builder(context).build()
                        val completed = CompletableDeferred<Unit>()
                        try {
                            player.addListener(object : Player.Listener {
                                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) completed.complete(Unit) }
                                override fun onPlayerError(error: PlaybackException) { completed.completeExceptionally(error) }
                            })
                            player.setMediaItem(MediaItem.fromUri(exported.toURI().toString()))
                            player.setPlaybackSpeed(2f); player.prepare(); player.play()
                            withTimeout(20_000) { completed.await() }
                        } finally { player.release() }
                    }
                } finally { extractor.release(); exported.delete() }
            }
        } finally { audio.clearPlaybackCache(); directory.deleteRecursively() }
    }
}
