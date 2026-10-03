package moe.antimony.hoshi.features.tts

import android.content.ContextWrapper
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TtsCustomDialogueTest {
    @get:Rule val folder = TemporaryFolder()
    private val wav = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()).putInt(40).put("WAVEfmt ".toByteArray()).putInt(16)
        putShort(1).putShort(1).putInt(24000).putInt(48000).putShort(2).putShort(16)
        put("data".toByteArray()).putInt(4).putInt(0)
    }.array()

    @Test fun openAiUsesRequestLocalVoicesWithoutCatalogOrChangingTheSavedProfile() = runBlocking {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse().setBody(Buffer().write(wav))) }
            val profile = TtsCustomSource("custom:dialogue", "Local", baseUrl = server.url("/v1/").toString(), model = "tts", voice = "n", format = TtsAudioFormat.Wav)
            val preferences = TtsPreferences(TtsSettings(engine = profile.id, customSources = listOf(profile)).withRouting(TtsVoiceRouting(true, "n", "d")))
            val client = OkHttpClient()
            val transport = OnlineTtsTransport(client, client)
            val router = TtsEngineRouter(AndroidTtsEngine(ContextWrapper(null)), OnlineTtsEngine(transport, { _, _ -> "" }, Dispatchers.IO),
                CustomTtsEngine(transport, { _, _ -> "" }, Dispatchers.IO))
            val audio = TtsAudioRepository(router, folder.root, Dispatchers.IO, TtsExportFallback { _, _ -> error("Unexpected mismatch") })
            try {
                val sentence = TtsText.chapter(0, "<p>彼は「こんにちは」と言った。</p>").single()
                val files = audio.audio(sentence, "book", preferences)
                assertEquals(3, files.size)
                assertEquals(listOf("n", "d", "n"), List(3) {
                    val request = server.takeRequest()
                    assertEquals("/v1/audio/speech", request.path)
                    Json.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("voice").jsonPrimitive.content
                })
                assertEquals("n", preferences.settings.customSource()?.voice)
                assertEquals(files, audio.audio(sentence, "book", preferences))
                assertEquals(3, server.requestCount)
            } finally { audio.clearPlaybackCache(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun failedLaterSegmentDeletesEarlierFilesAndRetryRebuildsTheCompleteSentence() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(wav)))
            server.enqueue(MockResponse().setResponseCode(401))
            val profile = TtsCustomSource("custom:failure", "Local", baseUrl = server.url("/v1/").toString(), model = "tts", format = TtsAudioFormat.Wav)
            val preferences = TtsPreferences(TtsSettings(engine = profile.id, customSources = listOf(profile)).withRouting(TtsVoiceRouting(true, "n", "d")))
            val client = OkHttpClient()
            val transport = OnlineTtsTransport(client, client)
            val audio = TtsAudioRepository(TtsEngineRouter(AndroidTtsEngine(ContextWrapper(null)), OnlineTtsEngine(transport, { _, _ -> "" }, Dispatchers.IO),
                CustomTtsEngine(transport, { _, _ -> "" }, Dispatchers.IO)), folder.root, Dispatchers.IO, TtsExportFallback { _, _ -> error("Unexpected mismatch") })
            val sentence = TtsText.chapter(0, "<p>彼は「こんにちは」と言った。</p>").single()
            try {
                try { audio.audio(sentence, "book", preferences); fail("Must fail on the second segment") }
                catch (error: TtsException) { assertEquals(TtsFailure.Authentication, error.failure) }
                assertTrue(File(folder.root, "tts/playback").listFiles().orEmpty().isEmpty())
                repeat(3) { server.enqueue(MockResponse().setBody(Buffer().write(wav))) }
                assertEquals(3, audio.audio(sentence, "book", preferences).size)
                assertEquals(5, server.requestCount)
            } finally { audio.clearPlaybackCache(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }
}
