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
    @Test fun microsoftTranslatorCatalogSynthesisAndExport() = checkSource(TtsOnlineSource.MicrosoftTranslator)
    @Test fun translateLongSentenceExportsEveryMp3Chunk() = checkSource(TtsOnlineSource.GoogleTranslate)

    @Test fun microsoftTranslatorAdvertisedStyleAndPcmExport() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "translator-style-device-test")
        val engine = OnlineTtsEngine(OnlineTtsTransport(), TtsCredentialCipher(Dispatchers.IO), Dispatchers.IO)
        val router = TtsEngineRouter(AndroidTtsEngine(context), engine, CustomTtsEngine(OnlineTtsTransport(), { _, _ -> throw TtsException(TtsFailure.Configuration) }, kotlinx.coroutines.Dispatchers.IO))
        val repository = TtsAudioRepository(router, directory, Dispatchers.IO, AndroidTtsExportFallback(context))
        val initial = TtsSettings(engine = TtsOnlineSource.MicrosoftTranslator.id)
        val voice = router.catalog(initial).voices.first { "cheerful" in it.styles }
        try {
            for (format in listOf(TtsAudioFormat.Wav, TtsAudioFormat.Mp3)) {
                val sampleRate = if (format == TtsAudioFormat.Wav) 16000 else 48000
                val settings = initial.copy(pitch = 2f).withVoice(voice.id).withRate(1.25f).withOptions(
                    TtsSynthesisOptions(style = "cheerful", styleDegree = 1.5f, commaMs = 300,
                        format = format, sampleRate = sampleRate))
                val sentence = TtsSentence(0, 0, 0, "こんにちは、日本語の音声です。")
                val exported = repository.export(sentence, null, TtsPreferences(settings))
                val extractor = MediaExtractor()
                try {
                    assertEquals(settings.audioExtension(), exported.extension)
                    extractor.setDataSource(exported.absolutePath)
                    assertTrue(extractor.trackCount > 0)
                    val track = extractor.getTrackFormat(0)
                    assertEquals(if (format == TtsAudioFormat.Wav) "audio/raw" else "audio/mpeg", track.getString(MediaFormat.KEY_MIME))
                    assertEquals(sampleRate, track.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    assertTrue(track.getLong(MediaFormat.KEY_DURATION) > 0)
                } finally { extractor.release(); exported.delete() }
            }
        } finally { repository.clearPlaybackCache(); directory.deleteRecursively() }
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
        val router = TtsEngineRouter(AndroidTtsEngine(context), online, CustomTtsEngine(OnlineTtsTransport(), { _, _ -> throw TtsException(TtsFailure.Configuration) }, kotlinx.coroutines.Dispatchers.IO))
        val repository = TtsAudioRepository(router, directory, Dispatchers.IO, AndroidTtsExportFallback(context))
        val settings = TtsSettings(engine = source.id, pitch = if (source == TtsOnlineSource.Edge) 10f else 0f,
            parameters = mapOf(source.id to TtsSynthesisOptions(volume = -10f, format = TtsAudioFormat.Mp3)))
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
