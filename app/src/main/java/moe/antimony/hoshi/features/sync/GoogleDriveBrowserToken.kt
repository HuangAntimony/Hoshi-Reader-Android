package moe.antimony.hoshi.features.sync

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal val BrowserAuthJson = Json { ignoreUnknownKeys = true }

@Serializable
internal data class BrowserTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("refresh_token") val refreshToken: String? = null,
) {
    fun expiresAt(): Long = System.currentTimeMillis() + expiresIn * 1000
}

internal class BrowserTokenException(
    val error: String?,
    val statusCode: Int? = null,
    description: String? = null,
) : IOException(listOfNotNull(statusCode?.let { "HTTP $it" }, error, description)
    .filter { it.isNotBlank() }.distinct().joinToString("\n").ifEmpty { null })

internal suspend fun requestBrowserToken(
    parameters: Map<String, String>,
    ioDispatcher: CoroutineDispatcher,
    openConnection: () -> HttpURLConnection = { URL("https://oauth2.googleapis.com/token").openConnection() as HttpURLConnection },
): BrowserTokenResponse = withContext(ioDispatcher) {
    val connection = openConnection().apply {
        requestMethod = "POST"
        connectTimeout = 10_000
        readTimeout = 10_000
        instanceFollowRedirects = false
        setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        doOutput = true
    }
    try {
        val body = parameters.entries.joinToString("&") { (key, value) -> "${key.urlQueryComponent()}=${value.urlQueryComponent()}" }
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        if (status !in 200..299) {
            val response = connection.errorStream?.bufferedReader()?.use { it.readText() }
            val error = response?.let {
                runCatching { BrowserAuthJson.parseToJsonElement(it).jsonObject }.getOrNull()
            }
            // Only OAuth's diagnostic fields are retained; never retain the token response body.
            fun diagnostic(field: String): String? = error?.get(field)?.let {
                runCatching { it.jsonPrimitive.content }.getOrNull()
            }?.let { text ->
                parameters.filterKeys { it in setOf("code", "code_verifier", "refresh_token", "client_secret") }
                    .values.filter { it.isNotEmpty() }.fold(text) { safe, secret -> safe.replace(secret, "[redacted]") }
            }
            throw BrowserTokenException(diagnostic("error"), status, diagnostic("error_description"))
        }
        val response = connection.inputStream.bufferedReader().use { it.readText() }
        val token = try {
            BrowserAuthJson.decodeFromString<BrowserTokenResponse>(response)
        } catch (error: SerializationException) {
            // This decoder includes its input in exceptions, which may contain credentials.
            throw BrowserTokenException(null, status, error.javaClass.name)
        }
        if (token.accessToken.isEmpty()) throw BrowserTokenException(null, status)
        token
    } finally {
        connection.disconnect()
    }
}
