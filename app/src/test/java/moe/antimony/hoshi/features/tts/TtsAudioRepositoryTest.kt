package moe.antimony.hoshi.features.tts

import android.content.ContextWrapper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TtsAudioRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val preferences = TtsPreferences(TtsSettings(engine = TtsOnlineSource.GoogleCloud.id,
        online = mapOf(TtsOnlineSource.GoogleCloud.id to TtsOnlineConfig(encryptedKey = "fixture"))))
    private val next = TtsSentence(0, 0, 3, "次です。")
    private val later = TtsSentence(0, 4, 4, "三つです。")

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
        return TtsAudioRepository(TtsEngineRouter(AndroidTtsEngine(ContextWrapper(null)), online), folder.root, Dispatchers.IO)
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
                """{"voices":[{"name":"ja-JP-Neural2-B","languageCodes":["ja-JP"]}]}"""
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
