package moe.antimony.hoshi.features.tts

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CustomTtsEngineTest {
    @get:Rule val folder = TemporaryFolder()
    private val wav = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(40); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(24000); putInt(48000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(4); putInt(0)
    }.array()
    private fun engine(client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).build(), store: TtsVoiceCatalogStore? = null) =
        CustomTtsEngine(OnlineTtsTransport(client, client), { _, _ -> "test-key" }, Dispatchers.IO, store)
    private fun profile(server: MockWebServer, protocol: TtsCustomProtocol = TtsCustomProtocol.OpenAi) =
        TtsCustomSource(id = "custom:fixture", name = "Fixture", protocol = protocol, baseUrl = server.url("/proxy/v1/").toString(),
            model = "irodori-tts", voice = "sample", encryptedKey = "ciphertext", format = TtsAudioFormat.Wav)
    private fun settings(source: TtsCustomSource) = TtsSettings(engine = source.id, customSources = listOf(source))

    @Test fun openAiSynthesisDoesNotRequireCatalogAndPreservesPrefixAndFullJapaneseText() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(wav)))
            val profile = profile(server).copy(rate = 3.5f)
            val files = engine().synthesize("今日……です。", settings(profile), folder.root)
            assertArrayEquals(wav, files.single().readBytes())
            val request = server.takeRequest()
            assertEquals("/proxy/v1/audio/speech", request.path)
            assertEquals("Bearer test-key", request.getHeader("Authorization"))
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("今日……です。", body.getValue("input").jsonPrimitive.content)
            assertEquals("irodori-tts", body.getValue("model").jsonPrimitive.content)
            assertEquals("sample", body.getValue("voice").jsonPrimitive.content)
            assertEquals(3.5, body.getValue("speed").jsonPrimitive.double, .001)
            assertEquals("wav", body.getValue("response_format").jsonPrimitive.content)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun vitsCatalogKeepsModelAndSpeakerIdentityAndExcludesNonJapaneseAndConversionModels() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"VITS":[{"id":0,"name":"綾地寧々","lang":["ja"]},{"id":1,"name":"Chinese","lang":["zh"]}],"BERT-VITS2":[{"id":0,"name":"Same ID","lang":["ja","zh"]}],"HUBERT-VITS":[{"id":0,"name":"Conversion","lang":["ja"]}]}"""))
            val source = profile(server, TtsCustomProtocol.Vits).copy(baseUrl = server.url("/proxy/").toString(), voice = "VITS:0")
            val catalog = engine().catalog(settings(source))
            assertEquals(listOf("VITS:0", "BERT-VITS2:0"), catalog.voices.map { it.id })
            assertEquals("綾地寧々", catalog.voices.first().label)
            val request = server.takeRequest()
            assertEquals("/proxy/voice/speakers", request.path)
            assertEquals("test-key", request.getHeader("X-API-KEY"))
        }
    }

    @Test fun forcedCatalogRefreshFetchesNewVoicesAndFailedRefreshKeepsPersistentCache() = runBlocking {
        for (protocol in TtsCustomProtocol.entries) MockWebServer().use { server ->
            fun response(id: Int) = when (protocol) {
                TtsCustomProtocol.OpenAi -> """{"voices":[{"id":"voice-$id"}]}"""
                TtsCustomProtocol.Vits -> """{"VITS":[{"id":$id,"name":"Voice $id","lang":["ja"]}]}"""
            }
            server.enqueue(MockResponse().setBody(response(0)))
            server.enqueue(MockResponse().setBody(response(1)))
            server.enqueue(MockResponse().setResponseCode(401))
            val source = profile(server, protocol).copy(voice = "")
            val store = TtsVoiceCatalogStore(folder.newFolder(protocol.name))
            val adapter = engine(store = store)
            val first = adapter.catalog(settings(source))
            assertEquals(first, adapter.catalog(settings(source)))
            assertEquals(1, server.requestCount)
            val refreshed = adapter.catalog(settings(source), force = true)
            assertEquals(if (protocol == TtsCustomProtocol.OpenAi) "voice-1" else "VITS:1", refreshed.voices.single().id)
            assertEquals(2, server.requestCount)
            try { adapter.catalog(settings(source), force = true); fail("Expected authentication failure") }
            catch (error: TtsException) { assertEquals(TtsFailure.Authentication, error.failure) }
            assertEquals(refreshed, engine(store = store).catalog(settings(source)))
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun vitsUsesInverseLengthAndJapaneseWithoutSendingUnsupportedPitch() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(wav)))
            val source = profile(server, TtsCustomProtocol.Vits).copy(baseUrl = server.url("/prefix/").toString(), voice = "BERT-VITS2:3", rate = 2f)
            engine().synthesize("こんにちは。", settings(source), folder.root)
            val request = server.takeRequest()
            assertEquals("/prefix/voice/bert-vits2", request.path)
            assertEquals("application/json", request.getHeader("Content-Type"))
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals(3, body.getValue("id").jsonPrimitive.int)
            assertEquals("ja", body.getValue("lang").jsonPrimitive.content)
            assertEquals(.5, body.getValue("length").jsonPrimitive.double, .001)
            assertFalse(body.containsKey("pitch"))
        }
    }

    @Test fun vitsJsonContentTypeDoesNotAddCharsetThatMakesTheServerParseAnEmptyForm() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.getHeader("Content-Type") == "application/json") MockResponse().setBody(Buffer().write(wav))
                    else MockResponse().setResponseCode(400).setBody("empty form")
            }
            val source = profile(server, TtsCustomProtocol.Vits).copy(voice = "VITS:0")
            assertArrayEquals(wav, engine().synthesize("日本語。", settings(source), folder.root).single().readBytes())
        }
    }

    @Test fun gptSoVitsUsesServerPresetAndDoesNotSendLength() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(wav)))
            val source = profile(server, TtsCustomProtocol.Vits).copy(voice = "GPT-SOVITS:2", preset = "narrator", rate = 2f)
            engine().synthesize("日本語。", settings(source), folder.root)
            val request = server.takeRequest()
            assertEquals("/proxy/v1/voice/gpt-sovits", request.path)
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("narrator", body.getValue("preset").jsonPrimitive.content)
            assertFalse(body.containsKey("length"))
        }
    }

    @Test fun optionalOpenAiCatalogMissingDoesNotPreventManualVoiceOrRequireRefreshOnEveryOpen() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            val source = profile(server)
            val adapter = engine(store = TtsVoiceCatalogStore(folder.newFolder("catalogs")))
            val catalog = adapter.catalog(settings(source))
            assertEquals("sample", catalog.voices.single().id)
            assertEquals(catalog, adapter.catalog(settings(source)))
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setBody(Buffer().write(wav)))
            assertTrue(adapter.synthesize("日本語。", settings(source), folder.root).single().isFile)
        }
    }

    @Test fun configuredVoiceIdsCombineWithDiscoveryAndRefreshFromCachedCatalog() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"voices":[{"id":"alice","name":"Server Alice"}]}"""))
            val source = profile(server).copy(voiceIds = listOf("alice", "bob"))
            val adapter = engine(store = TtsVoiceCatalogStore(folder.newFolder("manual-voices")))
            val catalog = adapter.catalog(settings(source))
            assertEquals(listOf("alice", "bob", "sample"), catalog.voices.map { it.id })
            assertEquals("Server Alice", catalog.voices.first().label)
            val changed = source.copy(voiceIds = listOf("alice", "carol"))
            assertEquals(listOf("alice", "carol", "sample"), adapter.cachedCatalog(settings(changed))!!.voices.map { it.id })
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun catalogsSurviveRecreationAndAreSeparatedByServerConfiguration() = runBlocking {
        MockWebServer().use { server ->
            val store = TtsVoiceCatalogStore(folder.newFolder("voices"))
            val source = profile(server)
            server.enqueue(MockResponse().setBody("""{"voices":[{"id":"alice","name":"Alice"}]}"""))
            val first = engine(store = store).catalog(settings(source))
            val next = engine(store = store)
            assertEquals(first, next.cachedCatalog(settings(source.copy(name = "Renamed", rate = 2f))))
            assertNull(next.cachedCatalog(settings(source.copy(baseUrl = server.url("/other/").toString()))))
            assertNull(next.cachedCatalog(settings(source.copy(encryptedKey = "new-key"))))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun transientDiscoveryFailureIsNotPersistedAsAnUnsupportedVoiceList() = runBlocking {
        MockWebServer().use { server ->
            val source = profile(server)
            val store = TtsVoiceCatalogStore(folder.newFolder("retry-voices"))
            server.enqueue(MockResponse().setResponseCode(503))
            try { engine(store = store).catalog(settings(source)); fail("Expected network failure") }
            catch (error: TtsException) { assertEquals(TtsFailure.Network, error.failure) }
            val next = engine(store = store)
            assertNull(next.cachedCatalog(settings(source)))
            server.enqueue(MockResponse().setBody("""{"voices":["alice"]}"""))
            assertTrue(next.catalog(settings(source)).voices.any { it.id == "alice" })
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun authenticationFailureCannotReturnAudioOrLeakServerError() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("private key rejected"))
            try { engine().synthesize("日本語。", settings(profile(server)), folder.root); fail("Expected failure") }
            catch (error: TtsException) { assertEquals(TtsFailure.Authentication, error.failure); assertFalse(error.toString().contains("private")) }
            assertTrue(folder.root.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun invalidAndMismatchedAudioIsRejectedInsteadOfBeingSaved() = runBlocking {
        MockWebServer().use { server ->
            for (bytes in listOf("not audio".toByteArray(), wav)) {
                server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
                try { engine().synthesize("日本語。", settings(profile(server).copy(format = TtsAudioFormat.Mp3)), folder.root); fail("Expected invalid response") }
                catch (error: TtsException) { assertEquals(TtsFailure.InvalidResponse, error.failure) }
            }
            assertTrue(folder.root.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun mp3IsPreservedAndLaterChunkFailureRemovesEarlierFiles() = runBlocking {
        MockWebServer().use { server ->
            val mp3 = ByteArray(417).apply { this[0] = 0xff.toByte(); this[1] = 0xfb.toByte(); this[2] = 0x90.toByte() }
            val source = profile(server).copy(format = TtsAudioFormat.Mp3)
            server.enqueue(MockResponse().setBody(Buffer().write(mp3)))
            val first = engine().synthesize("日本語。", settings(source), folder.root).single()
            assertEquals("mp3", first.extension); assertArrayEquals(mp3, first.readBytes()); first.delete()
            server.enqueue(MockResponse().setBody(Buffer().write(mp3)))
            server.enqueue(MockResponse().setResponseCode(503))
            try { engine().synthesize("日本語。".repeat(400), settings(source), folder.root); fail("Expected later chunk failure") }
            catch (error: TtsException) { assertEquals(TtsFailure.Network, error.failure) }
            assertTrue(folder.root.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun selfHostedTimeoutIsCancellableAndReportedWithoutAudioFiles() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(wav)).setBodyDelay(500, TimeUnit.MILLISECONDS))
            val client = OkHttpClient.Builder().callTimeout(100, TimeUnit.MILLISECONDS).build()
            try { engine(client).synthesize("日本語。", settings(profile(server)), folder.root); fail("Expected timeout") }
            catch (error: TtsException) { assertEquals(TtsFailure.Timeout, error.failure) }
            assertTrue(folder.root.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun deletedCustomProfileFailsConfigurationWithoutConsultingAndroid() = runBlocking {
        val transport = OnlineTtsTransport(OkHttpClient())
        val router = TtsEngineRouter(AndroidTtsEngine(android.content.ContextWrapper(null)),
            OnlineTtsEngine(transport, { _, _ -> "unused" }, Dispatchers.IO), engine())
        try { router.synthesize("日本語。", TtsSettings(engine = "custom:deleted"), folder.root); fail("Expected configuration error") }
        catch (error: TtsException) { assertEquals(TtsFailure.Configuration, error.failure) }
    }

    @Test fun cacheUsesOnlyActiveSynthesisAndExportUsesFullRubyAwareReplacementText() = runBlocking {
        MockWebServer().use { server ->
            repeat(4) { server.enqueue(MockResponse().setBody(Buffer().write(wav))) }
            val transport = OnlineTtsTransport(OkHttpClient())
            val router = TtsEngineRouter(AndroidTtsEngine(android.content.ContextWrapper(null)),
                OnlineTtsEngine(transport, { _, _ -> "unused" }, Dispatchers.IO), engine())
            val audio = TtsAudioRepository(router, folder.root, Dispatchers.IO)
            val source = profile(server)
            val other = source.copy(id = "custom:other", name = "Other")
            val preferences = TtsPreferences(TtsSettings(engine = source.id, customSources = listOf(source, other)),
                listOf(TtsWord("replace", "晴れ", "はれ")))
            val sentence = TtsText.chapter(0, "<p><ruby>今日<rt>きょう</rt></ruby>は晴れです。</p>").single()
            try {
                val first = audio.audio(sentence, "book", preferences)
                val renamed = preferences.copy(settings = preferences.settings.copy(customSources = listOf(source.copy(name = "Renamed"), other.copy(voice = "bob"))))
                assertEquals(first, audio.audio(sentence, "book", renamed))
                assertEquals(1, server.requestCount)
                val changed = preferences.copy(settings = preferences.settings.copy(customSources = listOf(source.copy(voice = "bob"), other)))
                assertNotEquals(first, audio.audio(sentence, "book", changed))
                assertNotEquals(first, audio.audio(sentence, "book", preferences.copy(settings = preferences.settings.selectSource(other.id))))
                audio.export(sentence, "book", preferences).also { assertArrayEquals(wav, it.readBytes()); it.delete() }
                repeat(4) {
                    val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                    assertEquals("きょうははれです。", body.getValue("input").jsonPrimitive.content)
                }
                assertEquals("今日は晴れです。", sentence.text)
            } finally { audio.clearPlaybackCache() }
        }
    }

    @Test fun cancellationStopsNetworkAndCannotLeavePartialAudio() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
            val job = launch { engine(client).synthesize("日本語。", settings(profile(server)), folder.root) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
            job.cancelAndJoin()
            withTimeout(2000) { while (client.dispatcher.runningCallsCount() > 0) delay(10) }
            assertTrue(folder.root.listFiles().orEmpty().isEmpty())
        }
    }
}
