package moe.antimony.hoshi.features.tts

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only engine discovery and synthesis into test-owned cache; never changes system voices or preferences. */
@RunWith(AndroidJUnit4::class)
class TtsEngineDeviceTest {
    @Test fun imageGlyphRubySynthesizesThroughPlaybackRepositoryFromMain() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "tts-image-ruby-device-test")
        val audio = TtsAudioRepository(AndroidTtsEngine(context), directory, Dispatchers.IO)
        val sentences = TtsText.chapter(0, "<p><ruby><img src='glyph.png'/><rt>たず</rt></ruby>ねた。次を<ruby><img src='glyph.png'/><rt>き</rt></ruby>く。</p>")
        try {
            withContext(Dispatchers.Main) {
                sentences.forEach { sentence ->
                    val files = audio.audio(sentence, null, TtsPreferences())
                    assertTrue(files.isNotEmpty())
                    assertTrue(files.all { it.length() > 44 })
                    assertEquals(files, audio.audio(sentence, null, TtsPreferences()))
                }
            }
        } finally {
            audio.clearPlaybackCache()
            withContext(Dispatchers.IO) { directory.deleteRecursively() }
        }
    }

    @Test fun installedJapaneseEngineProducesPlayableSentenceFile() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = AndroidTtsEngine(context)
        val installed = engine.installedEngines()
        Log.i("HoshiTtsTest", "Installed engines: ${installed.map { it.id }}")
        val catalog = engine.catalog(TtsSettings())
        assertTrue(catalog.voices.isNotEmpty())
        Log.i("HoshiTtsTest", "Selected ${catalog.selectedEngine}: ${catalog.voices}")
        val directory = File(context.cacheDir, "tts-engine-device-test")
        try {
            val files = engine.synthesize("東京へ行きます。日本語の読み上げです。", TtsSettings(engine = catalog.selectedEngine), directory)
            assertTrue(files.isNotEmpty())
            files.forEach { file ->
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath)
                    assertTrue(extractor.trackCount > 0)
                    val format = extractor.getTrackFormat(0)
                    assertTrue(format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true)
                    assertTrue(format.getLong(MediaFormat.KEY_DURATION) > 0)
                    Log.i("HoshiTtsTest", "Synthesized ${file.length()} bytes: $format")
                } finally { extractor.release() }
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun removedEngineIsExplicitlyRejected() = runBlocking {
        val engine = AndroidTtsEngine(ApplicationProvider.getApplicationContext())
        try {
            engine.catalog(TtsSettings(engine = "moe.antimony.hoshi.nonexistent.tts"))
            fail("A missing selected engine must not silently use the default")
        } catch (error: TtsException) {
            assertEquals(TtsFailure.EngineUnavailable, error.failure)
        }
    }
}
