package moe.antimony.hoshi.features.tts

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OnlineTtsEngineTest {
    @get:Rule val folder = TemporaryFolder()
    private val wav: ByteArray get() = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(40); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(24000); putInt(48000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(4); putInt(0)
    }.array()

    private fun engine(handler: (Request) -> Pair<Int, ByteArray>): OnlineTtsEngine {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val (code, body) = handler(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body(body.toResponseBody()).build()
        }.build()
        return OnlineTtsEngine(OnlineTtsTransport(client), { _, _ -> "test-key" }, Dispatchers.IO)
    }

    @Test fun azureSendsCredentialsRegionVoiceRateAndPitchAndReturnsWav() = runBlocking {
        val requests = mutableListOf<Request>()
        val engine = engine { request ->
            requests += request
            if (request.url.encodedPath.endsWith("list")) 200 to """[{"Locale":"ja-JP","ShortName":"ja-JP-NanamiNeural"}]""".toByteArray()
            else 200 to wav
        }
        val settings = TtsSettings(engine = TtsOnlineSource.Azure.id, rate = 1.5f, pitch = 2f,
            online = mapOf(TtsOnlineSource.Azure.id to TtsOnlineConfig("eastus", "encrypted")))
        val text = "東京……へ‥‥行く...。<x>"
        val files = engine.synthesize(text, settings, folder.root)
        assertArrayEquals(wav, files.single().readBytes())
        val request = requests.last()
        assertEquals("eastus.tts.speech.microsoft.com", request.url.host)
        assertEquals("test-key", request.header("Ocp-Apim-Subscription-Key"))
        assertEquals("riff-24khz-16bit-mono-pcm", request.header("X-Microsoft-OutputFormat"))
        val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(buffer.readByteArray().inputStream())
        assertEquals(text, document.documentElement.textContent)
        val prosody = document.getElementsByTagName("prosody").item(0)
        assertEquals("+2st", prosody.attributes.getNamedItem("pitch").nodeValue)
        assertEquals("+50%", prosody.attributes.getNamedItem("rate").nodeValue)
    }

    @Test fun cloudDecodesBase64AndUsesConfiguredKeyWithoutPuttingItInUrl() = runBlocking {
        val engine = engine { request ->
            assertFalse(request.url.toString().contains("test-key"))
            assertEquals("test-key", request.header("X-Goog-Api-Key"))
            if (request.url.encodedPath.endsWith("voices")) 200 to """{"voices":[{"name":"ja-JP-Neural2-B","languageCodes":["ja-JP"]}]}""".toByteArray()
            else 200 to buildJsonObject { put("audioContent", Base64.getEncoder().encodeToString(wav)) }.toString().toByteArray()
        }
        val settings = TtsSettings(engine = TtsOnlineSource.GoogleCloud.id,
            online = mapOf(TtsOnlineSource.GoogleCloud.id to TtsOnlineConfig(encryptedKey = "encrypted")))
        assertArrayEquals(wav, engine.synthesize("日本", settings, folder.root).single().readBytes())
    }

    @Test fun failedLaterChunkCleansEarlierFilesAndDoesNotFallBack() = runBlocking {
        var calls = 0
        val engine = engine { request ->
            if (request.url.encodedPath.endsWith("voices")) 200 to """{"voices":[{"name":"ja-JP-Neural2-B","languageCodes":["ja-JP"]}]}""".toByteArray()
            else if (++calls == 1) 200 to buildJsonObject { put("audioContent", Base64.getEncoder().encodeToString(wav)) }.toString().toByteArray()
            else 429 to "quota".toByteArray()
        }
        val settings = TtsSettings(engine = TtsOnlineSource.GoogleCloud.id,
            online = mapOf(TtsOnlineSource.GoogleCloud.id to TtsOnlineConfig(encryptedKey = "encrypted")))
        try { engine.synthesize("日本語。".repeat(400), settings, folder.root); fail("Expected quota error") }
        catch (error: TtsException) { assertEquals(TtsFailure.RateLimited, error.failure) }
        assertEquals(2, calls)
        assertTrue(folder.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun missingConfigurationDoesNotMakeNetworkRequests() = runBlocking {
        val engine = engine { error("No network request allowed") }
        for (source in listOf(TtsOnlineSource.Azure, TtsOnlineSource.GoogleCloud)) {
            try { engine.catalog(TtsSettings(engine = source.id)); fail("Expected configuration error") }
            catch (error: TtsException) { assertEquals(TtsFailure.Configuration, error.failure) }
        }
    }
}
