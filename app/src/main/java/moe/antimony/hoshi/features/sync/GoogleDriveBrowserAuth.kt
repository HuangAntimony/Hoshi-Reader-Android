package moe.antimony.hoshi.features.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import moe.antimony.hoshi.BuildConfig
import moe.antimony.hoshi.di.IoDispatcher

@Singleton
class GoogleDriveBrowserAuth internal constructor(
    private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val clientId: String,
    private val requestToken: suspend (Map<String, String>) -> BrowserTokenResponse,
) : DriveAuthorizer {
    @Inject constructor(
        @ApplicationContext context: Context,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(
        context,
        PreferenceDataStoreFactory.create {
            context.noBackupFilesDir.resolve("hoshi-drive-browser-auth.preferences_pb")
        },
        BuildConfig.HOSHI_GOOGLE_CLIENT_ID,
        { request -> requestBrowserToken(request, ioDispatcher) },
    )

    private val mutex = Mutex()
    private val tokensKey = stringPreferencesKey("tokens")
    private val pendingKey = stringPreferencesKey("pendingAuthorization")
    private val refreshKey = booleanPreferencesKey("needsTokenRefresh")

    internal suspend fun authorizationRequest(): BrowserAuthorizationRequest = mutex.withLock {
        if (clientId.isEmpty()) throw DriveAuthorizationRequiredException()
        val request = BrowserAuthorizationRequest(clientId, randomSecret(), randomSecret())
        dataStore.edit { it[pendingKey] = BrowserAuthJson.encodeToString(request) }
        request
    }

    suspend fun authorizationIntent(): Intent = Intent(context, GoogleDriveBrowserAuthActivity::class.java)
        .putExtra(GoogleDriveBrowserAuthActivity.AuthorizationUri, authorizationRequest().toUri().toString())

    suspend fun accept(intent: Intent?) = mutex.withLock {
        val pending = dataStore.data.first()[pendingKey]
        dataStore.edit { it.remove(pendingKey) }
        val request = pending?.let { BrowserAuthJson.decodeFromString<BrowserAuthorizationRequest>(it) }
        val response = intent?.data
        if (request == null || response == null || request.clientId != clientId ||
            response.buildUpon().clearQuery().fragment(null).build().toString() != request.redirectUri ||
            response.fragment != null || response.getQueryParameters("state") != listOf(request.state) ||
            response.getQueryParameter("error") != null
        ) throw DriveAuthorizationRequiredException()
        val code = response.getQueryParameters("code").singleOrNull()?.takeIf { it.isNotEmpty() }
            ?: throw DriveAuthorizationRequiredException()
        val token = requestToken(
            mapOf(
                "client_id" to clientId,
                "grant_type" to "authorization_code",
                "code" to code,
                "code_verifier" to request.codeVerifier,
                "redirect_uri" to request.redirectUri,
            ),
        )
        val refresh = token.refreshToken?.takeIf { it.isNotEmpty() } ?: throw DriveAuthorizationRequiredException()
        saveTokens(BrowserTokens(clientId, token.accessToken, refresh, token.expiresAt()))
    }

    override suspend fun status(): DriveAuthStatus = mutex.withLock {
        when {
            clientId.isEmpty() -> DriveAuthStatus.MissingConfiguration
            readTokens(dataStore.data.first())?.clientId == clientId -> DriveAuthStatus.Connected
            else -> DriveAuthStatus.NotConnected
        }
    }

    override suspend fun accessToken(): String = mutex.withLock {
        val preferences = dataStore.data.first()
        val tokens = readTokens(preferences)?.takeIf { it.clientId == clientId && clientId.isNotEmpty() }
            ?: throw DriveAuthorizationRequiredException()
        if (preferences[refreshKey] != true && tokens.expiresAt > System.currentTimeMillis() + 60_000) {
            return@withLock tokens.accessToken
        }
        val response = try {
            requestToken(
                mapOf("client_id" to clientId, "grant_type" to "refresh_token", "refresh_token" to tokens.refreshToken),
            )
        } catch (error: BrowserTokenException) {
            if (error.error in setOf("invalid_grant", "invalid_client", "unauthorized_client")) {
                dataStore.edit {
                    it.remove(tokensKey)
                    it.remove(refreshKey)
                }
                throw DriveAuthorizationRequiredException()
            }
            throw error
        }
        saveTokens(
            tokens.copy(
                accessToken = response.accessToken,
                refreshToken = response.refreshToken ?: tokens.refreshToken,
                expiresAt = response.expiresAt(),
            ),
        )
        response.accessToken
    }

    override suspend fun clearAccessToken(token: String) = mutex.withLock {
        if (readTokens(dataStore.data.first())?.accessToken == token) dataStore.edit { it[refreshKey] = true }
        Unit
    }

    override suspend fun revokeAccess() = mutex.withLock {
        dataStore.edit { it.clear() }
        Unit
    }

    private fun readTokens(preferences: Preferences): BrowserTokens? =
        preferences[tokensKey]?.let { BrowserAuthJson.decodeFromString<BrowserTokens>(it) }

    private suspend fun saveTokens(tokens: BrowserTokens) {
        dataStore.edit {
            it[tokensKey] = BrowserAuthJson.encodeToString(tokens)
            it.remove(refreshKey)
        }
    }
}

@Serializable
internal data class BrowserAuthorizationRequest(val clientId: String, val state: String, val codeVerifier: String) {
    val redirectUri: String get() = "${clientId.split('.').reversed().joinToString(".")}:/oauth2callback"

    fun toUri(): Uri = Uri.parse("https://accounts.google.com/o/oauth2/v2/auth").buildUpon().apply {
        appendQueryParameter("client_id", clientId)
        appendQueryParameter("redirect_uri", redirectUri)
        appendQueryParameter("response_type", "code")
        appendQueryParameter("scope", GoogleDriveAuth.DriveFileScope)
        appendQueryParameter("access_type", "offline")
        appendQueryParameter("prompt", "consent select_account")
        appendQueryParameter("state", state)
        appendQueryParameter("code_challenge_method", "S256")
        appendQueryParameter(
            "code_challenge",
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.US_ASCII)),
            ),
        )
    }.build()
}

@Serializable
private data class BrowserTokens(val clientId: String, val accessToken: String, val refreshToken: String, val expiresAt: Long)

private fun randomSecret(): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
