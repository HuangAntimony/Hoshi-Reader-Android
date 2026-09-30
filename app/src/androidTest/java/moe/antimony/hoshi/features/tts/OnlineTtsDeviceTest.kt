package moe.antimony.hoshi.features.tts

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit network smoke tests. Only public Japanese samples and test-owned cache are used. */
@RunWith(AndroidJUnit4::class)
class OnlineTtsDeviceTest {
    @Test fun edgeCatalogSynthesisAndExport() = checkSource(TtsOnlineSource.Edge)
    @Test fun translateLongSentenceExportsEveryMp3Chunk() = checkSource(TtsOnlineSource.GoogleTranslate)

    @Test fun edgeEllipsisPauseProducesLongerPlayableMp3() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = OnlineTtsEngine(OnlineTtsTransport(), TtsCredentialCipher(Dispatchers.IO), Dispatchers.IO)
        val directory = File(context.cacheDir, "edge-ellipsis-device-test")
        val settings = TtsSettings(engine = TtsOnlineSource.Edge.id, voice = "ja-JP-NanamiNeural")
        try {
            suspend fun synthesize(pause: Boolean) = engine.synthesize("こんにちは……お元気ですか。",
                settings.withOptions(TtsSynthesisOptions(edgeEllipsisPause = pause)), directory).single()
            val natural = synthesize(false)
            val paused = synthesize(true)
            // Edge's output is 48 kbit/s CBR; this public sample has an internal sentence pause.
            assertTrue("The normalized ellipsis should add an audible pause", paused.length() - natural.length() > 2000)
            assertEquals("mp3", paused.extension)
        } finally { directory.deleteRecursively() }
    }

    @Test fun credentialsStayEncryptedAndAreBoundToTheirSource() = runBlocking {
        val cipher = TtsCredentialCipher(Dispatchers.IO)
        val encrypted = cipher.encrypt("device-test-value", TtsOnlineSource.Azure.id)
        assertFalse(encrypted.contains("device-test-value"))
        assertEquals("device-test-value", cipher.decrypt(encrypted, TtsOnlineSource.Azure.id))
        try { cipher.decrypt(encrypted, TtsOnlineSource.GoogleCloud.id); fail("Expected authenticated decryption failure") }
        catch (error: TtsException) { assertEquals(TtsFailure.Configuration, error.failure) }
    }

    private fun checkSource(source: TtsOnlineSource) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "online-tts-device-test-${source.name}")
        val online = OnlineTtsEngine(OnlineTtsTransport(), TtsCredentialCipher(Dispatchers.IO), Dispatchers.IO)
        val router = TtsEngineRouter(AndroidTtsEngine(context), online)
        val repository = TtsAudioRepository(router, directory, Dispatchers.IO)
        val settings = TtsSettings(engine = source.id, pitch = if (source == TtsOnlineSource.Edge) 10f else 0f,
            parameters = mapOf(source.id to TtsSynthesisOptions(volume = -10f)))
        val voices = router.catalog(settings).voices
        assertTrue(voices.isNotEmpty())
        val sentence = TtsSentence(0, 0, 0, "今日は日本語の音声をテストしています。長い文章も最後まで正しく読み上げます。")
        try {
            val parts = repository.audio(sentence, null, TtsPreferences(settings))
            if (source == TtsOnlineSource.GoogleTranslate) assertTrue(parts.size > 1)
            val exported = repository.export(sentence, null, TtsPreferences(settings))
            assertEquals("mp3", exported.extension)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(exported.absolutePath)
                assertTrue(extractor.trackCount > 0)
                assertEquals("audio/mpeg", extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
                extractor.selectTrack(0)
                var count = 0
                while (extractor.sampleTime >= 0) { count++; if (!extractor.advance()) break }
                assertTrue("The complete export must contain multiple audio frames", count > 20)
            } finally { extractor.release(); exported.delete() }
        } finally { repository.clearPlaybackCache(); directory.deleteRecursively() }
    }
}
