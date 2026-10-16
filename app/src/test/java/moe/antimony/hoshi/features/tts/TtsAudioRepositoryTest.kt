package moe.antimony.hoshi.features.tts

import android.content.ContextWrapper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TtsAudioRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val preferences = TtsPreferences(TtsSettings(engine = TtsOnlineSource.GoogleCloud.id,
        online = mapOf(TtsOnlineSource.GoogleCloud.id to TtsOnlineConfig(encryptedKey = "fixture")))
        .withOptions(TtsSynthesisOptions(format = TtsAudioFormat.Wav)))
    private val next = TtsSentence(0, 0, 3, "次です。")
    private val later = TtsSentence(0, 4, 4, "三つです。")

    @Test fun dialogueRequestsUseOrderedVoicesAndExportTheWholeSentence() = runBlocking {
        val requests = mutableListOf<Pair<String, String>>()
        val client = client { body ->
            val json = Json.parseToJsonElement(body).jsonObject
            requests += json.getValue("voice").jsonObject.getValue("name").jsonPrimitive.content to
                json.getValue("input").jsonObject.getValue("text").jsonPrimitive.content
        }
        val repository = repository(client)
        val sentence = TtsText.chapter(0, "<p>彼は「こんにちは」と言った。</p>").single()
        val configured = preferences.copy(settings = preferences.settings.withRouting(TtsVoiceRouting(true, "ja-JP-Neural2-B", "ja-JP-Neural2-C")))
        try {
            val files = repository.audio(sentence, "book", configured)
            assertEquals(3, files.size)
            assertEquals(listOf("ja-JP-Neural2-B" to "彼は", "ja-JP-Neural2-C" to "「こんにちは」", "ja-JP-Neural2-B" to "と言った。"), requests)
            assertEquals(files, repository.audio(sentence, "book", configured))
            repository.export(sentence, "book", configured).also { output ->
                assertEquals("wav", output.extension)
                assertEquals(56, output.length().toInt())
                output.delete()
            }
            assertEquals(requests.take(3), requests.drop(3))
        } finally { repository.clearPlaybackCache(); close(client) }
    }

    @Test fun identicalEffectiveVoicesKeepWholeSentenceAndInvalidDialogueNeverFallsBack() = runBlocking {
        val requests = mutableListOf<String>()
        val client = client { requests += it }
        val repository = repository(client)
        val sentence = TtsText.chapter(0, "<p>「こんにちは」と言った。</p>").single()
        try {
            val same = preferences.copy(settings = preferences.settings.withRouting(TtsVoiceRouting(true, "", "ja-JP-Neural2-B")))
            assertEquals(1, repository.audio(sentence, "book", same).size)
            assertEquals(1, requests.size)
            assertTrue(requests.single().contains(sentence.text))
            val invalid = same.copy(settings = same.settings.withRouting(TtsVoiceRouting(true, "ja-JP-Neural2-B", "missing")))
            try { repository.audio(sentence, "book", invalid); fail("Invalid voice must not fall back") }
            catch (error: TtsException) { assertEquals(TtsFailure.MissingJapanese, error.failure) }
            assertEquals(1, requests.size)
        } finally { repository.clearPlaybackCache(); close(client) }
    }

    @Test fun rubyPreferenceAppliesToPlaybackAndExportAndSeparatesCachedAudio() = runBlocking {
        val requests = mutableListOf<String>()
        val client = client { body ->
            requests += Json.parseToJsonElement(body).jsonObject.getValue("input").jsonObject.getValue("text").jsonPrimitive.content
        }
        val repository = repository(client)
        val sentence = TtsText.chapter(0, "<p><ruby>今日<rt>キヨウ</rt></ruby>です。</p>").single()
        val disabled = preferences.copy(settings = preferences.settings.copy(useRubyReadings = false))
        try {
            val ruby = repository.audio(sentence, "book", preferences)
            val original = repository.audio(sentence, "book", disabled)
            assertNotEquals(ruby, original)
            assertEquals(original, repository.audio(sentence, "book", disabled))
            repository.export(sentence, "book", disabled).delete()
            val custom = disabled.copy(words = listOf(TtsWord("reading", "今日", "きょう")))
            repository.audio(sentence, "other", custom)
            repository.export(sentence, "other", custom).delete()
            assertEquals(listOf("キヨウです。", "今日です。", "今日です。", "きょうです。", "きょうです。"), requests)
            assertEquals("今日です。", sentence.text)
        } finally { repository.clearPlaybackCache(); close(client) }
    }

    @Test fun playbackRateAndPrefetchCountReuseAudioButPitchChangesRequireNewSynthesis() = runBlocking {
        val syntheses = AtomicInteger()
        val client = client { syntheses.incrementAndGet() }
        val repository = repository(client)
        try {
            val first = repository.audio(next, "book", preferences)
            val deeper = preferences.copy(settings = preferences.settings.copy(prefetchSentences = 6))
            assertEquals(first, repository.audio(next, "book", deeper))
            assertEquals(first, repository.audio(next, "book", deeper.copy(settings = deeper.settings.copy(playbackRate = 3f))))
            assertEquals(1, syntheses.get())
            val newPitch = repository.audio(next, "book", deeper.copy(settings = deeper.settings.copy(pitch = 2f)))
            assertNotEquals(first, newPitch)
            assertEquals(2, syntheses.get())
        } finally { repository.clearPlaybackCache(); close(client) }
    }

    @Test fun appPlaybackRateDoesNotChangeExportSynthesisParametersOrAudio() = runBlocking {
        val requests = mutableListOf<String>()
        val client = client { requests += it }
        val repository = repository(client)
        try {
            val normal = repository.export(next, "book", preferences)
            val fast = repository.export(next, "book", preferences.copy(settings = preferences.settings.copy(playbackRate = 3f)))
            try {
                assertEquals(2, requests.size)
                assertEquals(requests.first(), requests.last())
                assertArrayEquals(normal.readBytes(), fast.readBytes())
            } finally { normal.delete(); fast.delete() }
        } finally { repository.clearPlaybackCache(); close(client) }
    }

    @Test fun sentencePauseReusesSynthesizedAudio() = runBlocking {
        val syntheses = AtomicInteger()
        val client = client { syntheses.incrementAndGet() }
        val repository = repository(client)
        try {
            val first = repository.audio(next, "book", preferences)
            val changed = preferences.copy(settings = preferences.settings.copy(sentencePauseMs = 700))
            assertEquals(first, repository.audio(next, "book", changed))
            assertEquals(1, syntheses.get())
        } finally { repository.clearPlaybackCache(); close(client) }
    }

    @Test fun deletingEntireSentenceSkipsSynthesisAndRejectsEmptyExport() = runBlocking {
        var requests = 0
        val client = client { requests++ }
        val repository = repository(client)
        val sentence = TtsSentence(0, 0, 2, "削除。")
        val configured = preferences.copy(words = listOf(TtsWord("delete", "削除。", "")))
        try {
            assertTrue(repository.audio(sentence, "book", configured).isEmpty())
            val routed = configured.copy(settings = configured.settings.withRouting(TtsVoiceRouting(true)))
            assertTrue(repository.audio(sentence, "book", routed).isEmpty())
            try { repository.export(sentence, "book", configured); fail("Empty audio must not be exported") }
            catch (error: TtsException) { assertEquals(TtsFailure.EmptyText, error.failure) }
            assertEquals(0, requests)
            assertFalse(folder.root.walkTopDown().any { it.isFile })
            assertTrue(repository.audio(sentence, "book", preferences).isNotEmpty())
            assertEquals(1, requests)
        } finally { repository.clearPlaybackCache(); close(client) }
    }

    @Test fun playbackAndExportUseTheSameLiteralReplacementsAndEditsInvalidateCachedAudio() = runBlocking {
        val requests = mutableListOf<String>()
        val client = client { body ->
            requests += Json.parseToJsonElement(body).jsonObject.getValue("input").jsonObject.getValue("text").jsonPrimitive.content
        }
        val repository = repository(client)
        val sentence = TtsText.chapter(0, "<p>待って――まだ……続く。</p>").single()
        val rules = listOf(TtsWord("dash", "――", "、"), TtsWord("ellipsis", "……", "。"))
        val configured = preferences.copy(words = rules)
        try {
            val first = repository.audio(sentence, "book", configured)
            assertEquals(first, repository.audio(sentence, "book", configured))
            repository.export(sentence, "book", configured).also { assertTrue(it.isFile); it.delete() }
            val changed = repository.audio(sentence, "book", configured.copy(words = rules.map {
                if (it.id == "dash") it.copy(replacement = "。") else it
            }))
            assertNotEquals(first, changed)
            assertEquals(listOf("待って、まだ。続く。", "待って、まだ。続く。", "待って。まだ。続く。"), requests)
            assertEquals("待って――まだ……続く。", sentence.text)
        } finally { repository.clearPlaybackCache(); close(client) }
    }


    @Test fun regexPreviewPlaybackAndExportShareTextAndToggleInvalidatesAudio() = runBlocking {
        val requests = mutableListOf<String>()
        val client = client { body ->
            requests += Json.parseToJsonElement(body).jsonObject.getValue("input").jsonObject.getValue("text").jsonPrimitive.content
        }
        val repository = repository(client)
        val sentence = TtsSentence(0, 0, 5, "時々東京。", listOf(TtsRuby(2, 4, "とうきょう")))
        val rule = TtsWord("regex", "(時)々", "\$1\$1", regex = true)
        val preview = prepareTtsWordPreview(rule, sentence, "book", preferences)
        try {
            val first = repository.audio(sentence, "book", preview.preferences)
            assertEquals(first, repository.audio(sentence, "book", preview.preferences))
            repository.export(sentence, "book", preview.preferences).also { assertTrue(it.isFile); it.delete() }
            val changed = repository.audio(sentence, "book", preview.preferences.copy(words = listOf(rule.copy(regex = false))))
            assertNotEquals(first, changed)
            assertEquals("時時とうきょう。", preview.text)
            assertEquals(listOf("時時とうきょう。", "時時とうきょう。", "時々とうきょう。"), requests)
        } finally { repository.clearPlaybackCache(); close(client) }
    }

    @Test fun cachedNextSentenceReturnsWhileLaterSynthesisIsStillPending() = runBlocking {
        val pending = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val client = client {
            if (it.contains("三つです")) {
                pending.complete(Unit)
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        val repository = repository(client)
        var prefetch: Deferred<*>? = null
        try {
            val cached = repository.audio(next, "book", preferences)
            prefetch = async { repository.audio(later, "book", preferences) }
            withTimeout(5000) { pending.await() }
            val available = withTimeout(1000) { repository.audio(next, "book", preferences) }
            assertEquals(cached, available)
            assertTrue(available.single().isFile)
            assertFalse(prefetch.isCompleted)
        } finally {
            release.countDown()
            prefetch?.cancelAndJoin()
            repository.clearPlaybackCache()
            close(client)
        }
    }

    @Test fun concurrentRequestsShareCompletedAudioAndClearingForcesNewSynthesis() = runBlocking {
        val syntheses = AtomicInteger()
        val client = client { syntheses.incrementAndGet() }
        val repository = repository(client)
        try {
            val files = coroutineScope {
                List(3) { async { repository.audio(next, "book", preferences) } }.awaitAll()
            }
            assertEquals(1, syntheses.get())
            assertTrue(files.all { it == files.first() })
            repository.clearPlaybackCache()
            assertFalse(files.first().single().exists())
            val regenerated = repository.audio(next, "book", preferences)
            assertTrue(regenerated.single().isFile)
            assertNotEquals(files.first(), regenerated)
            assertEquals(2, syntheses.get())
        } finally { repository.clearPlaybackCache(); close(client) }
    }

    private fun repository(client: OkHttpClient): TtsAudioRepository {
        val online = OnlineTtsEngine(OnlineTtsTransport(client), { _, _ -> "fixture-key" }, Dispatchers.IO)
        return TtsAudioRepository(TtsEngineRouter(AndroidTtsEngine(ContextWrapper(null)), online, CustomTtsEngine(OnlineTtsTransport(), { _, _ -> throw TtsException(TtsFailure.Configuration) }, kotlinx.coroutines.Dispatchers.IO)), folder.root, Dispatchers.IO, TtsExportFallback { _, _ -> throw AssertionError("Unexpected format normalization") })
    }

    private fun client(onSynthesis: (String) -> Unit): OkHttpClient {
        val wav = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(40); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(24000); putInt(48000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(4); putInt(0)
        }.array()
        return OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.url.encodedPath.endsWith("voices")) {
                """{"voices":[{"name":"ja-JP-Neural2-B","languageCodes":["ja-JP"]},{"name":"ja-JP-Neural2-C","languageCodes":["ja-JP"]}]}"""
            } else {
                val buffer = okio.Buffer()
                request.body!!.writeTo(buffer)
                onSynthesis(buffer.readUtf8())
                """{"audioContent":"${Base64.getEncoder().encodeToString(wav)}"}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody()).build()
        }.build()
    }

    private fun close(client: OkHttpClient) {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
