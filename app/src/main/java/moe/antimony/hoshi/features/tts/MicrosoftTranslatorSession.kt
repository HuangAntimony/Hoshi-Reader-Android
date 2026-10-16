package moe.antimony.hoshi.features.tts

import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Ephemeral authorization for the unofficial Translator endpoint; never persisted. */
@Singleton
class MicrosoftTranslatorSession internal constructor(
    private val transport: OnlineTtsTransport,
    private val now: () -> Long,
) {
    @Inject constructor(transport: OnlineTtsTransport) : this(transport, { System.currentTimeMillis() / 1000 })
    private val mutex = Mutex()
    private val userId = UUID.randomUUID().toString().replace("-", "").take(16)
    private var cached: MicrosoftTranslatorToken? = null

    internal suspend fun <T> authorized(request: suspend (MicrosoftTranslatorToken) -> T): T {
        val token = token()
        return try {
            request(token)
        } catch (error: TtsException) {
            if (error.failure != TtsFailure.Authentication) throw error
            mutex.withLock { if (cached === token) cached = null }
            request(token())
        }
    }

    private suspend fun token(): MicrosoftTranslatorToken = mutex.withLock {
        cached?.takeIf { it.expiresAt > now() + 60 }?.let { return@withLock it }
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val response = transport.request(Request.Builder().url(MicrosoftTranslatorProtocol.endpoint)
            .header("X-ClientVersion", "4.0.530a 5fe1dc6c")
            .header("X-UserId", userId)
            .header("X-ClientTraceId", UUID.randomUUID().toString())
            .header("X-MT-Signature", MicrosoftTranslatorProtocol.signature(now(), nonce))
            .header("Accept-Language", "ja-JP")
            .post(ByteArray(0).toRequestBody()).build())
        MicrosoftTranslatorProtocol.token(response.toString(Charsets.UTF_8), now()).also { cached = it }
    }
}

internal class MicrosoftTranslatorToken(val region: String, val bearer: String, val expiresAt: Long)

internal object MicrosoftTranslatorProtocol {
    const val endpoint = "https://dev.microsofttranslator.com/apps/endpoint?api-version=1.0"
    private const val application = "MSTranslatorAndroidApp"
    // Public Translator client protocol constant, not a user's Azure subscription key.
    private const val signingKey = "oik6PdDdMnOXemTbwvMn9de/h9lFnfBaCWbGMMZqqoSaQaqUOqjVGm5NqsmjcBI1x+sS9ugjB55HEJWRiFXYFw=="
    private val dateFormat = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC)

    fun signature(epochSeconds: Long, nonce: String): String {
        val date = dateFormat.format(Instant.ofEpochSecond(epochSeconds)).lowercase(Locale.ROOT)
        val url = URLEncoder.encode(endpoint.substringAfter("://"), "UTF-8")
        val input = "$application$url$date$nonce".lowercase(Locale.ROOT)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(Base64.getDecoder().decode(signingKey), "HmacSHA256"))
        val signature = Base64.getEncoder().encodeToString(mac.doFinal(input.toByteArray(Charsets.UTF_8)))
        return "$application::$signature::$date::$nonce"
    }

    fun token(body: String, now: Long): MicrosoftTranslatorToken = try {
        val response = Json.parseToJsonElement(body).jsonObject
        val region = response.getValue("r").jsonPrimitive.content
        OnlineTtsProtocol.azureBase(region)
        val bearer = response.getValue("t").jsonPrimitive.content
        require(bearer.length <= 16384 && bearer.none { it <= ' ' || it >= '\u007f' })
        val parts = bearer.split('.')
        require(parts.size == 3 && parts.all { it.isNotBlank() })
        val claims = Json.parseToJsonElement(Base64.getUrlDecoder().decode(parts[1]).toString(Charsets.UTF_8)).jsonObject
        val expiry = claims.getValue("exp").jsonPrimitive.long
        require(expiry > now)
        MicrosoftTranslatorToken(region, bearer, expiry)
    } catch (error: Exception) {
        throw TtsException(TtsFailure.InvalidResponse, cause = error)
    }
}
