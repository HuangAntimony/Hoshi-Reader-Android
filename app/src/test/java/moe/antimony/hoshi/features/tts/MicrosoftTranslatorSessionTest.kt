package moe.antimony.hoshi.features.tts

import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class MicrosoftTranslatorSessionTest {
    private val epoch = 1700000000L

    private fun token(expiry: Long, signature: String = "signature") = "header." +
        Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":$expiry}""".toByteArray()) + ".$signature"

    private fun response(expiry: Long, signature: String = "signature") =
        """{"r":"eastasia","t":"${token(expiry, signature)}"}"""

    private fun session(now: () -> Long = { epoch }, handler: () -> Pair<Int, String>): MicrosoftTranslatorSession {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val (code, body) = handler()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(body.toResponseBody()).build()
        }.build()
        return MicrosoftTranslatorSession(OnlineTtsTransport(client), now)
    }

    @Test fun signatureMatchesIndependentClientProtocolVector() {
        assertEquals(
            "MSTranslatorAndroidApp::+GCJl+VWS6JHQsSwkSdEoReMRMP3d5EDrgLRg/mNuWE=::tue, 14 nov 2023 22:13:20 gmt::0123456789abcdef0123456789abcdef",
            MicrosoftTranslatorProtocol.signature(epoch, "0123456789abcdef0123456789abcdef"),
        )
    }

    @Test fun tokenParsesActualRegionAndExpiry() {
        val parsed = MicrosoftTranslatorProtocol.token(response(epoch + 900), epoch)
        assertEquals("eastasia", parsed.region)
        assertEquals(token(epoch + 900), parsed.bearer)
        assertEquals(epoch + 900, parsed.expiresAt)
    }

    @Test fun invalidAuthorizationResponsesExposeOnlyTypedFailure() {
        val bodies = listOf("<html>unavailable</html>", "{}", response(epoch),
            """{"r":"eastasia","t":"invalid"}""",
            """{"r":"eastasia.attacker.invalid/","t":"${token(epoch + 900)}"}""",
            """{"r":"eastasia","t":"header.!!!!.signature"}""")
        for (body in bodies) {
            try { MicrosoftTranslatorProtocol.token(body, epoch); fail("Expected invalid response") }
            catch (error: TtsException) { assertEquals(TtsFailure.InvalidResponse, error.failure) }
        }
    }

    @Test fun concurrentRequestsShareAuthorization() = runBlocking {
        val authentications = AtomicInteger()
        val session = session {
            authentications.incrementAndGet()
            200 to response(epoch + 900)
        }
        val tokens = (1..20).map { async { session.authorized { it.bearer } } }.awaitAll()
        assertEquals(1, authentications.get())
        assertEquals(1, tokens.distinct().size)
    }

    @Test fun authorizationRefreshesOnlyInsideExpiryMargin() = runBlocking {
        var now = epoch
        val authentications = AtomicInteger()
        val session = session({ now }) {
            200 to response(now + 900, authentications.incrementAndGet().toString())
        }
        val first = session.authorized { it.bearer }
        now += 839
        assertEquals(first, session.authorized { it.bearer })
        now++
        assertNotEquals(first, session.authorized { it.bearer })
        assertEquals(2, authentications.get())
    }

    @Test fun authenticationRetryIsBoundedAndOtherFailuresDoNotRefresh() = runBlocking {
        for (failure in listOf(TtsFailure.Authentication, TtsFailure.RateLimited, TtsFailure.Network)) {
            val authentications = AtomicInteger()
            val requests = AtomicInteger()
            val session = session { 200 to response(epoch + 900, authentications.incrementAndGet().toString()) }
            try {
                session.authorized<Unit> { requests.incrementAndGet(); throw TtsException(failure) }
                fail("Expected request failure")
            } catch (error: TtsException) { assertEquals(failure, error.failure) }
            val expected = if (failure == TtsFailure.Authentication) 2 else 1
            assertEquals(expected, authentications.get())
            assertEquals(expected, requests.get())
        }
    }

    @Test fun failedAuthorizationIsNotCached() = runBlocking {
        val calls = AtomicInteger()
        val session = session { if (calls.incrementAndGet() == 1) 429 to "limited" else 200 to response(epoch + 900) }
        try { session.authorized { it.region }; fail("Expected rate limiting") }
        catch (error: TtsException) { assertEquals(TtsFailure.RateLimited, error.failure) }
        assertEquals("eastasia", session.authorized { it.region })
        assertEquals(2, calls.get())
    }

    @Test fun canceledAuthorizationReleasesWaitersWithoutCachingLateResponse() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val session = session {
            val call = calls.incrementAndGet()
            if (call == 1) {
                entered.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
            }
            200 to response(epoch + 900, call.toString())
        }
        try {
            val canceled = launch { session.authorized { fail("Canceled request must not synthesize") } }
            withTimeout(3000) { entered.await() }
            canceled.cancelAndJoin()
            release.countDown()
            assertEquals(token(epoch + 900, "2"), withTimeout(3000) { session.authorized { it.bearer } })
            assertEquals(2, calls.get())
        } finally { release.countDown() }
    }

    @Test fun translatorUsesAzureRatePitchAndAudioFormats() {
        val source = TtsOnlineSource.MicrosoftTranslator
        assertFalse(source.needsKey)
        assertEquals(.5f, source.normalizeRate(.1f))
        assertEquals(2f, source.normalizeRate(3f))
        assertEquals(ttsPitchRange(TtsOnlineSource.Azure, "ja-JP-NanamiNeural"), ttsPitchRange(source, "ja-JP-NanamiNeural"))
        assertEquals("mp3", TtsSettings(engine = source.id).audioExtension())
        assertEquals("wav", TtsSettings(engine = source.id).withOptions(TtsSynthesisOptions(format = TtsAudioFormat.Wav)).audioExtension())
    }
}
