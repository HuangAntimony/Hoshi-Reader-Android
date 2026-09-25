package moe.antimony.hoshi.features.sync

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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

internal class BrowserTokenException(val error: String?) : IOException()

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
        if (connection.responseCode !in 200..299) {
            val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
            throw BrowserTokenException(error?.let {
                runCatching { BrowserAuthJson.parseToJsonElement(it).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
            })
        }
        val token = BrowserAuthJson.decodeFromString<BrowserTokenResponse>(connection.inputStream.bufferedReader().use { it.readText() })
        if (token.accessToken.isEmpty()) throw BrowserTokenException(null)
        token
    } finally {
        connection.disconnect()
    }
}
