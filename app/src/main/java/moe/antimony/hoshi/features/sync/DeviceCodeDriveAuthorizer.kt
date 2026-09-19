package moe.antimony.hoshi.features.sync

import android.content.Context
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import dagger.hilt.android.qualifiers.ApplicationContext
import moe.antimony.hoshi.di.IoDispatcher

@Singleton
class DeviceCodeDriveAuthorizer @Inject constructor(
    @ApplicationContext context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : DriveAuthorizer {
    private val preferences = context.applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)

    fun configuredClient(): DeviceCodeOAuthClient? {
        val clientId = preferences.getString(ClientIdKey, null)?.trim().orEmpty()
        val clientSecret = preferences.getString(ClientSecretKey, null)?.trim().orEmpty()
        return if (clientId.isBlank() || clientSecret.isBlank()) {
            null
        } else {
            DeviceCodeOAuthClient(clientId = clientId, clientSecret = clientSecret)
        }
    }

    fun saveClient(clientId: String, clientSecret: String) {
        preferences.edit()
            .putString(ClientIdKey, clientId.trim())
            .putString(ClientSecretKey, clientSecret.trim())
            .apply()
    }

    suspend fun requestDeviceCode(): DeviceCodePrompt {
        val client = configuredClient() ?: throw DriveAuthException(MissingConfigurationMessage)
        val response = postForm(
            url = DeviceCodeUrl,
            parameters = mapOf(
                "client_id" to client.clientId,
                "scope" to DriveFileScope,
            ),
        )
        if (response.statusCode >= 400) {
            throw DriveAuthException(response.oauthErrorMessage("Google Drive authorization code request failed."))
        }
        val payload = DeviceCodeJson.decodeFromString(DeviceCodeResponse.serializer(), response.body.decodeToString())
        val verificationUrl = payload.verificationUrl ?: payload.verificationUri
        if (payload.deviceCode.isBlank() || payload.userCode.isBlank() || verificationUrl.isNullOrBlank()) {
            throw DriveAuthException("Google Drive authorization did not return a device code.")
        }
        return DeviceCodePrompt(
            deviceCode = payload.deviceCode,
            userCode = payload.userCode,
            verificationUrl = verificationUrl,
            expiresInSeconds = payload.expiresIn,
            intervalSeconds = payload.interval ?: DefaultPollIntervalSeconds,
        )
    }

    suspend fun pollAuthorization(prompt: DeviceCodePrompt): DriveAuthorizationResult {
        val client = configuredClient() ?: return DriveAuthorizationResult.Failed(MissingConfigurationMessage)
        val response = try {
            postForm(
                url = TokenUrl,
                parameters = mapOf(
                    "client_id" to client.clientId,
                    "client_secret" to client.clientSecret,
                    "device_code" to prompt.deviceCode,
                    "grant_type" to DeviceCodeGrantType,
                ),
            )
        } catch (error: IOException) {
            return error.toDeviceCodePollingFailureResult()
        }
        if (response.statusCode < 400) {
            val token = DeviceCodeJson.decodeFromString(TokenResponse.serializer(), response.body.decodeToString())
            saveTokens(token)
            return DriveAuthorizationResult.Authorized(token.accessToken)
        }
        val error = response.oauthError()
        return when (error?.error) {
            "authorization_pending" -> DriveAuthorizationResult.Pending
            "slow_down" -> DriveAuthorizationResult.SlowDown
            "access_denied" -> DriveAuthorizationResult.Failed("Google Drive authorization was denied.")
            "expired_token" -> DriveAuthorizationResult.Failed("Google Drive authorization code expired.")
            "invalid_client" -> DriveAuthorizationResult.Failed(
                "Google OAuth client is invalid. Use a TVs and Limited Input devices client from the same project as ッツ/iOS sync.",
            )
            else -> DriveAuthorizationResult.Failed(response.oauthErrorMessage("Google Drive authorization failed."))
        }
    }

    override suspend fun accessToken(): String {
        val accessToken = preferences.getString(AccessTokenKey, null)
        val expiresAtMillis = preferences.getLong(AccessTokenExpiresAtMillisKey, 0L)
        if (!accessToken.isNullOrBlank() && expiresAtMillis - System.currentTimeMillis() > TokenRefreshSkewMillis) {
            return accessToken
        }
        return refreshAccessToken()
    }

    override suspend fun clearAccessToken(token: String) {
        if (preferences.getString(AccessTokenKey, null) == token) {
            preferences.edit()
                .remove(AccessTokenKey)
                .remove(AccessTokenExpiresAtMillisKey)
                .apply()
        }
    }

    override suspend fun revokeAccess() {
        // Sign out is intentionally local-only. The same Google Cloud project is shared with iOS/ッツ,
        // so revoking Google's grant here can also invalidate those clients for the same user.
        clearTokens()
    }

    override suspend fun status(): DriveAuthStatus {
        if (configuredClient() == null) return DriveAuthStatus.MissingConfiguration
        return if (preferences.getString(RefreshTokenKey, null).isNullOrBlank()) {
            DriveAuthStatus.NotConnected
        } else {
            DriveAuthStatus.Connected
        }
    }

    private suspend fun refreshAccessToken(): String {
        val client = configuredClient() ?: throw DriveAuthorizationRequiredException()
        val refreshToken = preferences.getString(RefreshTokenKey, null)
            ?: throw DriveAuthorizationRequiredException()
        val response = postForm(
            url = TokenUrl,
            parameters = mapOf(
                "client_id" to client.clientId,
                "client_secret" to client.clientSecret,
                "refresh_token" to refreshToken,
                "grant_type" to RefreshTokenGrantType,
            ),
        )
        if (response.statusCode >= 400) {
            if (response.isPermanentRefreshTokenFailure()) {
                clearTokens()
            }
            throw DriveAuthException(response.oauthErrorMessage("Google Drive token refresh failed."))
        }
        val token = DeviceCodeJson.decodeFromString(TokenResponse.serializer(), response.body.decodeToString())
        saveTokens(token)
        return token.accessToken
    }

    private suspend fun postForm(url: String, parameters: Map<String, String>): HttpResponse =
        withContext(ioDispatcher) {
            val body = parameters.entries.joinToString("&") { (name, value) ->
                "${name.formEncoded()}=${value.formEncoded()}"
            }.toByteArray(StandardCharsets.UTF_8)
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = RequestTimeoutMillis
                readTimeout = RequestTimeoutMillis
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                doOutput = true
            }
            try {
                connection.outputStream.use { it.write(body) }
                val statusCode = connection.responseCode
                val responseBytes = if (statusCode >= 400) {
                    connection.errorStream?.use { it.readBytes() } ?: ByteArray(0)
                } else {
                    connection.inputStream.use { it.readBytes() }
                }
                HttpResponse(statusCode = statusCode, body = responseBytes)
            } finally {
                connection.disconnect()
            }
        }

    private fun saveTokens(token: TokenResponse) {
        val expiresAtMillis = System.currentTimeMillis() + token.expiresIn * 1000L
        preferences.edit()
            .putString(AccessTokenKey, token.accessToken)
            .putLong(AccessTokenExpiresAtMillisKey, expiresAtMillis)
            .apply {
                if (!token.refreshToken.isNullOrBlank()) {
                    putString(RefreshTokenKey, token.refreshToken)
                }
            }
            .apply()
    }

    private fun clearTokens() {
        preferences.edit()
            .remove(AccessTokenKey)
            .remove(AccessTokenExpiresAtMillisKey)
            .remove(RefreshTokenKey)
            .apply()
    }

    private fun HttpResponse.oauthError(): OAuthErrorResponse? =
        runCatching {
            DeviceCodeJson.decodeFromString(OAuthErrorResponse.serializer(), body.decodeToString())
        }.getOrNull()

    private fun HttpResponse.oauthErrorMessage(fallback: String): String {
        val error = oauthError()
        return error?.errorDescription
            ?: error?.error
            ?: runCatching {
                DeviceCodeJson.parseToJsonElement(body.decodeToString()).jsonObject["error"]?.jsonPrimitive?.content
            }.getOrNull()
            ?: fallback
    }

    private fun HttpResponse.isPermanentRefreshTokenFailure(): Boolean =
        statusCode == HttpURLConnection.HTTP_BAD_REQUEST && oauthError()?.error == "invalid_grant"

    companion object {
        const val DriveFileScope = "https://www.googleapis.com/auth/drive.file"
        const val MissingConfigurationMessage =
            "Configure a Google OAuth client before connecting Google Drive."
        private const val PreferencesName = "google-drive-device-code-auth"
        private const val ClientIdKey = "clientId"
        private const val ClientSecretKey = "clientSecret"
        private const val AccessTokenKey = "accessToken"
        private const val RefreshTokenKey = "refreshToken"
        private const val AccessTokenExpiresAtMillisKey = "accessTokenExpiresAtMillis"
        private const val DeviceCodeUrl = "https://oauth2.googleapis.com/device/code"
        private const val TokenUrl = "https://oauth2.googleapis.com/token"
        private const val DeviceCodeGrantType = "urn:ietf:params:oauth:grant-type:device_code"
        private const val RefreshTokenGrantType = "refresh_token"
        private const val DefaultPollIntervalSeconds = 5L
        const val SlowDownIncrementSeconds = 5L
        const val TransientNetworkBackoffMultiplier = 2L
        const val MaxTransientNetworkBackoffSeconds = 60L
        private const val TokenRefreshSkewMillis = 60_000L
        private const val RequestTimeoutMillis = 10_000
    }
}

data class DeviceCodeOAuthClient(
    val clientId: String,
    val clientSecret: String,
)

data class DeviceCodePrompt(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
)

@Serializable
private data class DeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_url") val verificationUrl: String? = null,
    @SerialName("verification_uri") val verificationUri: String? = null,
    @SerialName("expires_in") val expiresIn: Long,
    val interval: Long? = null,
)

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("refresh_token") val refreshToken: String? = null,
)

@Serializable
private data class OAuthErrorResponse(
    val error: String,
    @SerialName("error_description") val errorDescription: String? = null,
)

private data class HttpResponse(
    val statusCode: Int,
    val body: ByteArray,
)

private val DeviceCodeJson = Json {
    ignoreUnknownKeys = true
}

private fun String.formEncoded(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.name())
