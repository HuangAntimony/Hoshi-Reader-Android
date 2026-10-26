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

    @Test fun edgeCatalogAndSynthesisSendV2TokensAndMatchingVersion() = runBlocking {
        val requests = mutableListOf<Request>()
        val engine = engine { request ->
            requests += request
            if (request.url.encodedPath.endsWith("/voices/list"))
                200 to """[{"Locale":"ja-JP","ShortName":"ja-JP-NanamiNeural"}]""".toByteArray()
            else 403 to ByteArray(0)
        }
        val error = runCatching {
            engine.synthesize("接続テスト。", TtsSettings(engine = TtsOnlineSource.Edge.id), folder.root)
        }.exceptionOrNull() as TtsException
        assertEquals(TtsFailure.Authentication, error.failure)
        assertEquals(listOf("/consumer/speech/synthesize/readaloud/voices/list",
            "/consumer/speech/synthesize/readaloud/edge/v1"), requests.map { it.url.encodedPath })
        for (request in requests) {
            assertEquals("2-143.0.3650.75", request.url.queryParameter("Sec-MS-GEC-Version"))
            assertTrue(request.url.queryParameter("Sec-MS-GEC")!!.matches(Regex("[0-9A-F]{16}-[0-9A-F]{64}")))
        }
    }

    private val wav: ByteArray get() = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(40); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(24000); putInt(48000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(4); putInt(0)
    }.array()

    private fun engine(store: TtsVoiceCatalogStore? = null, handler: (Request) -> Pair<Int, ByteArray>): OnlineTtsEngine {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val (code, body) = handler(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body(body.toResponseBody()).build()
        }.build()
        return OnlineTtsEngine(OnlineTtsTransport(client), { _, _ -> "test-key" }, Dispatchers.IO, catalogs = TtsVoiceCatalogCache(store, 32))
    }

    @Test fun repeatedCatalogLoadsReuseVoicesForEveryOnlineSourceDespitePlaybackChanges() = runBlocking {
        for (source in TtsOnlineSource.entries) {
            var authorizations = 0
            var catalogs = 0
            val engine = engine { request ->
                if (request.url.host == "dev.microsofttranslator.com") {
                    authorizations++
                    200 to """{"r":"eastasia","t":"${translatorToken("cache")}"}""".toByteArray()
                } else {
                    catalogs++
                    200 to (if (source == TtsOnlineSource.GoogleCloud)
                        """{"voices":[{"name":"ja-JP-Neural2-B","languageCodes":["ja-JP"]}]}"""
                    else """[{"Locale":"ja-JP","ShortName":"ja-JP-NanamiNeural"}]""").toByteArray()
                }
            }
            val settings = TtsSettings(engine = source.id, online = mapOf(source.id to TtsOnlineConfig("eastasia", "fixture")))
            val first = engine.catalog(settings)
            assertEquals(first, engine.catalog(settings.copy(playbackRate = 2f).withRate(1.5f).withVoice(first.voices.first().id)))
            assertEquals(first, engine.catalog(settings))
            assertEquals(if (source == TtsOnlineSource.GoogleTranslate) 0 else 1, catalogs)
            assertEquals(if (source == TtsOnlineSource.MicrosoftTranslator) 1 else 0, authorizations)
        }
    }

    @Test fun concurrentCatalogRequestsShareOneSuccessfulLoad() = runBlocking {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val engine = engine { calls.incrementAndGet(); 200 to """[{"Locale":"ja-JP","ShortName":"ja-JP-NanamiNeural"}]""".toByteArray() }
        val settings = TtsSettings(engine = TtsOnlineSource.Edge.id)
        val results = (1..12).map { async { engine.catalog(settings) } }.awaitAll()
        assertTrue(results.all { it == results.first() })
        assertEquals(1, calls.get())
    }

    @Test fun failedCatalogLoadsAreNotCachedAndCanBeRetried() = runBlocking {
        var calls = 0
        val engine = engine { if (++calls == 1) 503 to "temporary".toByteArray()
            else 200 to """[{"Locale":"ja-JP","ShortName":"ja-JP-NanamiNeural"}]""".toByteArray() }
        val settings = TtsSettings(engine = TtsOnlineSource.Edge.id)
        try { engine.catalog(settings); fail("Expected failure") } catch (_: TtsException) { }
        val recovered = engine.catalog(settings)
        assertEquals(recovered, engine.catalog(settings))
        assertEquals(2, calls)
    }

    @Test fun storedCatalogSurvivesEngineRecreationAndOnlyExplicitRefreshFetchesAgain() = runBlocking {
        var calls = 0
        val directory = folder.newFolder("voice-catalogs")
        val settings = TtsSettings(engine = TtsOnlineSource.Edge.id)
        val firstEngine = engine(TtsVoiceCatalogStore(directory)) {
            calls++
            200 to """[{"Locale":"ja-JP","ShortName":"ja-JP-NanamiNeural"}]""".toByteArray()
        }
        val first = firstEngine.catalog(settings)
        assertEquals(1, calls)
        val recreated = engine(TtsVoiceCatalogStore(directory)) {
            calls++
            200 to """[{"Locale":"ja-JP","ShortName":"ja-JP-KeitaNeural"}]""".toByteArray()
        }
        assertEquals(first, recreated.cachedCatalog(settings))
        assertEquals(first, recreated.catalog(settings))
        assertEquals(1, calls)
        val refreshed = recreated.catalog(settings, force = true)
        assertEquals("ja-JP-KeitaNeural", refreshed.voices.single().id)
        assertEquals(refreshed, recreated.catalog(settings))
        assertEquals(2, calls)
    }

    @Test fun changingCloudConfigurationRequiresANewCatalogButOtherSourcesRemainCached() = runBlocking {
        val calls = mutableListOf<String>()
        val directory = folder.newFolder("configuration-catalogs")
        val engine = engine(TtsVoiceCatalogStore(directory)) {
            calls += it.url.host
            200 to """[{"Locale":"ja-JP","ShortName":"ja-JP-NanamiNeural"}]""".toByteArray()
        }
        val edge = TtsSettings(engine = TtsOnlineSource.Edge.id)
        val azure = TtsSettings(engine = TtsOnlineSource.Azure.id,
            online = mapOf(TtsOnlineSource.Azure.id to TtsOnlineConfig("eastasia", "first")))
        engine.catalog(edge); engine.catalog(azure)
        val changed = azure.copy(online = mapOf(TtsOnlineSource.Azure.id to TtsOnlineConfig("eastus", "second")))
        assertNull(engine.cachedCatalog(changed))
        engine.catalog(changed); engine.catalog(edge)
        assertEquals(listOf("speech.platform.bing.com", "eastasia.tts.speech.microsoft.com", "eastus.tts.speech.microsoft.com"), calls)
        val files = directory.listFiles()!!.map { it.readText() }
        assertTrue(files.none { it.contains("test-key") || it.contains("first") || it.contains("second") })
    }

    @Test fun corruptDiskCatalogIsRefetchedWithoutLosingOtherSources() = runBlocking {
        val directory = folder.newFolder("corrupt-catalog")
        File(directory, "Edge.json").writeText("incomplete")
        var calls = 0
        val engine = engine(TtsVoiceCatalogStore(directory)) { calls++; 200 to """[{"Locale":"ja-JP","ShortName":"ja-JP-NanamiNeural"}]""".toByteArray() }
        val settings = TtsSettings(engine = TtsOnlineSource.Edge.id)
        assertNull(engine.cachedCatalog(settings))
        val voices = engine.catalog(settings)
        assertEquals(1, calls)
        assertEquals(voices, engine.catalog(settings))
    }

    @Test fun microsoftTranslatorUsesAutomaticAuthorizationAndAzureSpeechParameters() = runBlocking {
        var authentications = 0
        val token = translatorToken("first")
        val text = "東京……へ行く。"
        val engine = engine { request ->
            if (request.url.host == "dev.microsofttranslator.com") {
                authentications++
                assertEquals("POST", request.method)
                assertTrue(request.header("X-MT-Signature")!!.startsWith("MSTranslatorAndroidApp::"))
                200 to """{"r":"eastasia","t":"$token"}""".toByteArray()
            } else {
                assertEquals("eastasia.tts.speech.microsoft.com", request.url.host)
                assertEquals("Bearer $token", request.header("Authorization"))
                assertNull(request.header("Ocp-Apim-Subscription-Key"))
                if (request.url.encodedPath.endsWith("list")) {
                    200 to """[{"Locale":"ja-JP","ShortName":"ja-JP-NanamiNeural","StyleList":["cheerful"]}]""".toByteArray()
                } else {
                    assertEquals("riff-24khz-16bit-mono-pcm", request.header("X-Microsoft-OutputFormat"))
                    val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
                    val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(buffer.readByteArray().inputStream())
                    assertEquals(text, document.documentElement.textContent)
                    assertEquals("cheerful", document.getElementsByTagName("mstts:express-as").item(0).attributes.getNamedItem("style").nodeValue)
                    assertEquals("+2st", document.getElementsByTagName("prosody").item(0).attributes.getNamedItem("pitch").nodeValue)
                    assertEquals("300ms", document.getElementsByTagName("mstts:silence").item(0).attributes.getNamedItem("value").nodeValue)
                    200 to wav
                }
            }
        }
        val settings = TtsSettings(engine = "online:microsoft-translator", pitch = 2f,
            parameters = mapOf("online:microsoft-translator" to TtsSynthesisOptions(style = "cheerful", commaMs = 300, format = TtsAudioFormat.Wav))).withRate(1.5f)
        assertArrayEquals(wav, engine.synthesize(text, settings, folder.root).single().readBytes())
        assertArrayEquals(wav, engine.synthesize(text, settings, folder.root).single().readBytes())
        assertEquals(1, authentications)
    }

    @Test fun microsoftTranslatorRefreshesRejectedTokenOnceWithoutChangingSource() = runBlocking {
        var authentications = 0
        var syntheses = 0
        val tokens = listOf(translatorToken("first"), translatorToken("second"))
        val engine = engine { request ->
            when {
                request.url.host == "dev.microsofttranslator.com" -> {
                    val token = tokens[authentications++]
                    200 to """{"r":"eastasia","t":"$token"}""".toByteArray()
                }
                request.url.encodedPath.endsWith("list") -> 200 to """[{"Locale":"ja-JP","ShortName":"ja-JP-NanamiNeural"}]""".toByteArray()
                ++syntheses == 1 -> 401 to "expired".toByteArray()
                else -> {
                    assertEquals("Bearer ${tokens[1]}", request.header("Authorization"))
                    200 to wav
                }
            }
        }
        assertArrayEquals(wav, engine.synthesize("日本語。", TtsSettings(engine = "online:microsoft-translator").withOptions(TtsSynthesisOptions(format = TtsAudioFormat.Wav)), folder.root).single().readBytes())
        assertEquals(2, authentications)
        assertEquals(2, syntheses)
    }

    private fun translatorToken(signature: String): String = "fixture." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString("""{"exp":${System.currentTimeMillis() / 1000 + 3600}}""".toByteArray()) + ".$signature"

    @Test fun azureSendsCredentialsRegionVoiceRateAndPitchAndReturnsWav() = runBlocking {
        val requests = mutableListOf<Request>()
        val engine = engine { request ->
            requests += request
            if (request.url.encodedPath.endsWith("list")) 200 to """[{"Locale":"ja-JP","ShortName":"ja-JP-NanamiNeural"}]""".toByteArray()
            else 200 to wav
        }
        val settings = TtsSettings(engine = TtsOnlineSource.Azure.id, pitch = 2f,
            online = mapOf(TtsOnlineSource.Azure.id to TtsOnlineConfig("eastus", "encrypted"))).withRate(1.5f)
            .withOptions(TtsSynthesisOptions(format = TtsAudioFormat.Wav))
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
            .withOptions(TtsSynthesisOptions(format = TtsAudioFormat.Wav))
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
            .withOptions(TtsSynthesisOptions(format = TtsAudioFormat.Wav))
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
