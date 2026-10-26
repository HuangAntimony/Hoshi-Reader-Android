package moe.antimony.hoshi.features.tts

import android.content.ContextWrapper
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class TtsEmptyAudioTest(private val source: Source) {
    enum class Source { Translator, Azure, GoogleCloud, GoogleTranslate, OpenAi, Vits }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun sources() = Source.entries.map { arrayOf(it) }
    }
    @get:Rule val folder = TemporaryFolder()
    private fun mp3(value: Byte = 1) = byteArrayOf(0xff.toByte(), 0xf3.toByte(), 0x64, 0xc0.toByte()) + ByteArray(140) { value }

    private inner class Fixture(format: TtsAudioFormat = TtsAudioFormat.Mp3) : AutoCloseable {
        val requests = mutableListOf<String>()
        var synthesis: (Int) -> Pair<Int, ByteArray> = { 200 to ByteArray(0) }
        var rawResponse: ByteArray? = null
        var emptyMetadata = false
        private val custom = TtsCustomSource("custom:empty", "Fixture",
            protocol = if (source == Source.Vits) TtsCustomProtocol.Vits else TtsCustomProtocol.OpenAi,
            baseUrl = "http://127.0.0.1:18799/", model = "fixture", format = format)
        private val online = when (source) {
            Source.Translator -> TtsOnlineSource.MicrosoftTranslator
            Source.Azure -> TtsOnlineSource.Azure
            Source.GoogleCloud -> TtsOnlineSource.GoogleCloud
            else -> TtsOnlineSource.GoogleTranslate
        }
        val voice = when (source) {
            Source.GoogleCloud -> "ja-JP-Neural2-B"
            Source.GoogleTranslate -> "ja"
            Source.OpenAi -> "n"
            Source.Vits -> "VITS:0"
            else -> "ja-JP-NanamiNeural"
        }
        val dialogue = when (source) {
            Source.GoogleCloud -> "ja-JP-Neural2-C"
            Source.GoogleTranslate -> "ja"
            Source.OpenAi -> "d"
            Source.Vits -> "VITS:1"
            else -> "ja-JP-MayuNeural"
        }
        val settings = (if (source in listOf(Source.OpenAi, Source.Vits))
            TtsSettings(engine = custom.id, customSources = listOf(custom))
        else TtsSettings(engine = online.id, online = mapOf(online.id to TtsOnlineConfig("eastasia", "fixture"))))
            .withVoice(voice).withOptions(TtsSynthesisOptions(format = format))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val metadata = when {
                request.url.host == "dev.microsofttranslator.com" -> {
                    val token = "fixture." + Base64.getUrlEncoder().withoutPadding()
                        .encodeToString("""{"exp":${System.currentTimeMillis() / 1000 + 3600}}""".toByteArray()) + ".signature"
                    """{"r":"eastasia","t":"$token"}"""
                }
                request.method == "GET" -> when (source) {
                    Source.GoogleCloud -> """{"voices":[{"name":"$voice","languageCodes":["ja-JP"]},{"name":"$dialogue","languageCodes":["ja-JP"]}]}"""
                    Source.OpenAi -> """{"voices":["n","d"]}"""
                    Source.Vits -> """{"VITS":[{"id":0,"lang":["ja"]},{"id":1,"lang":["ja"]}]}"""
                    else -> """[{"Locale":"ja-JP","ShortName":"$voice"},{"Locale":"ja-JP","ShortName":"$dialogue"}]"""
                }
                else -> null
            }
            val (status, bytes) = if (metadata != null) 200 to (if (emptyMetadata) "" else metadata).toByteArray()
            else {
                requests += Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                val (code, audio) = synthesis(requests.lastIndex)
                code to (rawResponse ?: if (code == 200) encodedAudio(audio) else audio)
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(bytes.toResponseBody()).build()
        }.build()
        private val transport = OnlineTtsTransport(client, client)
        val router = TtsEngineRouter(AndroidTtsEngine(ContextWrapper(null)),
            OnlineTtsEngine(transport, { _, _ -> "fixture" }, Dispatchers.IO),
            CustomTtsEngine(transport, { _, _ -> "fixture" }, Dispatchers.IO))
        val repository = TtsAudioRepository(router, folder.root, Dispatchers.IO, TtsExportFallback { _, _ -> error("Unexpected fallback") })
        private fun encodedAudio(audio: ByteArray): ByteArray {
            val encoded = Base64.getEncoder().encodeToString(audio)
            return when (source) {
                Source.GoogleCloud -> """{"audioContent":"$encoded"}""".toByteArray()
                Source.GoogleTranslate -> buildJsonArray {
                    add(buildJsonArray {
                        add("wrb.fr"); add("jQ1olc"); add(buildJsonArray { add(encoded) }.toString())
                    })
                }.toString().toByteArray()
                else -> audio
            }
        }
        override fun close() { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }

    @Test fun successfulEmptyAudioSkipsAnyTextWithoutWritingFiles() = runBlocking {
        Fixture().use { fixture ->
            for (text in listOf("──", "🪼", "普通の文章。")) {
                assertTrue(fixture.router.synthesize(text, fixture.settings, folder.root).isEmpty())
            }
            assertEquals(3, fixture.requests.size)
            assertFalse(folder.root.walkTopDown().any { it.isFile })
        }
    }

    @Test fun emptyBeginningMiddleAndEndChunksKeepTheOtherAudioInOrder() = runBlocking {
        Fixture().use { fixture ->
            fixture.synthesis = { 200 to when (it) { 1 -> mp3(1); 3 -> mp3(2); else -> ByteArray(0) } }
            val characters = if (source == Source.GoogleTranslate) 33 else 1000
            val files = fixture.router.synthesize("あ".repeat(characters * 5), fixture.settings, folder.root)
            assertEquals(5, fixture.requests.size)
            assertEquals(2, files.size)
            assertArrayEquals(mp3(1), files[0].readBytes())
            assertArrayEquals(mp3(2), files[1].readBytes())
        }
    }

    @Test fun emptyWavResponseAlsoSkipsSynthesisWithoutAPlaceholderFile() = runBlocking {
        if (source == Source.GoogleTranslate) return@runBlocking // This source always returns MP3.
        Fixture(TtsAudioFormat.Wav).use { fixture ->
            assertTrue(fixture.router.synthesize("正文。", fixture.settings, folder.root).isEmpty())
            assertEquals(1, fixture.requests.size)
            assertFalse(folder.root.walkTopDown().any { it.isFile })
        }
    }

    @Test fun emptyPlaybackIsCachedAndPreviewOrExportReportsEmptyText() = runBlocking {
        Fixture().use { fixture ->
            val sentence = TtsSentence(0, 0, 3, "普通文。")
            val preferences = TtsPreferences(fixture.settings)
            assertTrue(fixture.repository.audio(sentence, "book", preferences).isEmpty())
            assertTrue(fixture.repository.audio(sentence, "book", preferences).isEmpty())
            assertEquals(1, fixture.requests.size)
            val error = runCatching { fixture.repository.export(sentence, "book", preferences) }.exceptionOrNull()
            assertEquals(TtsFailure.EmptyText, (error as TtsException).failure)
            assertEquals(2, fixture.requests.size)
            assertFalse(folder.root.walkTopDown().any { it.isFile })
        }
    }

    @Test fun routedSentencePlaybackAndExportKeepAudibleDialogueAfterEmptyNarration() = runBlocking {
        if (source == Source.GoogleTranslate) return@runBlocking // This source has only one voice, so no split request.
        Fixture().use { fixture ->
            fixture.synthesis = { 200 to if (it % 2 == 0) ByteArray(0) else mp3() }
            val preferences = TtsPreferences(fixture.settings.withRouting(TtsVoiceRouting(true, fixture.voice, fixture.dialogue)))
            val sentence = TtsText.chapter(0, "<p>──『肉の塊』がそこに居た。</p>").first()
            assertEquals("──『肉の塊』", sentence.text)
            val files = fixture.repository.audio(sentence, "book", preferences)
            assertEquals(1, files.size)
            assertArrayEquals(mp3(), files.single().readBytes())
            assertEquals(files, fixture.repository.audio(sentence, "book", preferences))
            val exported = fixture.repository.export(sentence, "book", preferences)
            assertArrayEquals(mp3(), exported.readBytes())
            assertEquals(4, fixture.requests.size)
            assertEquals(fixture.requests.take(2), fixture.requests.drop(2))
            assertTrue(fixture.requests[0].contains("──"))
            assertTrue(fixture.requests[1].contains("『肉の塊』"))
            if (source != Source.Vits) {
                assertTrue(fixture.requests[0].contains(fixture.voice))
                assertTrue(fixture.requests[1].contains(fixture.dialogue))
            }
        }
    }

    @Test fun otherStatusesAndNonemptyInvalidAudioStillFailWithoutFiles() = runBlocking {
        Fixture().use { fixture ->
            for ((status, bytes) in listOf(204 to ByteArray(0), 201 to ByteArray(0),
                200 to "not audio".toByteArray(), 200 to mp3().copyOf(50))) {
                fixture.synthesis = { status to bytes }
                val error = runCatching { fixture.router.synthesize("正文。", fixture.settings, folder.root) }.exceptionOrNull()
                assertEquals("status=$status bytes=${bytes.size}", TtsFailure.InvalidResponse, (error as TtsException).failure)
                assertFalse(folder.root.walkTopDown().any { it.isFile })
            }
        }
    }

    @Test fun laterFailureAfterAnEmptyChunkDeletesEarlierAudio() = runBlocking {
        Fixture().use { fixture ->
            fixture.synthesis = { when (it) { 0 -> 200 to mp3(); 1 -> 200 to ByteArray(0); else -> 503 to "unavailable".toByteArray() } }
            val characters = if (source == Source.GoogleTranslate) 33 else 1000
            val error = runCatching { fixture.router.synthesize("あ".repeat(characters * 3), fixture.settings, folder.root) }.exceptionOrNull()
            assertEquals(TtsFailure.Network, (error as TtsException).failure)
            assertEquals(3, fixture.requests.size)
            assertFalse(folder.root.walkTopDown().any { it.isFile })
        }
    }

    @Test fun googleRequiresAValidEnvelopeEvenWhenItContainsNoAudio() = runBlocking {
        if (source !in listOf(Source.GoogleCloud, Source.GoogleTranslate)) return@runBlocking
        Fixture().use { fixture ->
            for (body in listOf("", "{}", "not json", """{"audioContent":"!!!!"}""",
                """[["wrb.fr","jQ1olc","[\"!!!!\"]"]]""")) {
                fixture.rawResponse = body.toByteArray()
                val error = runCatching { fixture.router.synthesize("正文。", fixture.settings, folder.root) }.exceptionOrNull()
                assertEquals(TtsFailure.InvalidResponse, (error as TtsException).failure)
            }
        }
    }

    @Test fun emptyAuthorizationAndCatalogAreNotSuccessfulEmptySynthesis() = runBlocking {
        if (source == Source.GoogleTranslate) return@runBlocking // Static catalog; no authorization or discovery request.
        Fixture().use { fixture ->
            fixture.emptyMetadata = true
            val error = runCatching { fixture.router.catalog(fixture.settings) }.exceptionOrNull()
            assertEquals(TtsFailure.InvalidResponse, (error as TtsException).failure)
            assertTrue(fixture.requests.isEmpty())
        }
    }
}
