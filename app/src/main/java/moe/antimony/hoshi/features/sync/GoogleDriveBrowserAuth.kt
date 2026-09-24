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
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.antimony.hoshi.BuildConfig
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse

@Singleton
class GoogleDriveBrowserAuth internal constructor(
    private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val clientId: String,
    private val requestToken: suspend (TokenRequest) -> TokenResponse,
) : DriveAuthorizer {
    @Inject constructor(@ApplicationContext context: Context) : this(
        context,
        PreferenceDataStoreFactory.create {
            context.noBackupFilesDir.resolve("hoshi-drive-browser-auth.preferences_pb")
        },
        BuildConfig.HOSHI_GOOGLE_CLIENT_ID,
        { request -> requestBrowserToken(context, request) },
    )

    private val mutex = Mutex()
    private val stateKey = stringPreferencesKey("state")
    private val refreshKey = booleanPreferencesKey("needsTokenRefresh")

    internal fun authorizationRequest(): AuthorizationRequest {
        if (clientId.isEmpty()) throw DriveAuthorizationRequiredException()
        val scheme = clientId.split('.').reversed().joinToString(".")
        return AuthorizationRequest.Builder(
            AuthorizationServiceConfiguration(
                Uri.parse("https://accounts.google.com/o/oauth2/v2/auth"),
                Uri.parse("https://oauth2.googleapis.com/token"),
            ),
            clientId,
            ResponseTypeValues.CODE,
            Uri.parse("$scheme:/oauth2callback"),
        )
            .setScope(GoogleDriveAuth.DriveFileScope)
            .setPrompt("consent select_account")
            .setAdditionalParameters(mapOf("access_type" to "offline"))
            .build()
    }

    fun authorizationIntent(): Intent {
        val request = authorizationRequest()
        val service = AuthorizationService(context)
        return try {
            service.getAuthorizationRequestIntent(request)
        } finally {
            service.dispose()
        }
    }

    suspend fun accept(intent: Intent?) = mutex.withLock {
        if (intent == null) throw AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW
        AuthorizationException.fromIntent(intent)?.let { throw it }
        val response = AuthorizationResponse.fromIntent(intent)!!
        val state = AuthState(response, null)
        state.update(requestToken(response.createTokenExchangeRequest()), null)
        if (!state.isAuthenticated()) throw DriveAuthorizationRequiredException()
        saveState(state)
    }

    override suspend fun status(): DriveAuthStatus = when {
        clientId.isEmpty() -> DriveAuthStatus.MissingConfiguration
        readState()?.isAuthenticated() == true -> DriveAuthStatus.Connected
        else -> DriveAuthStatus.NotConnected
    }

    override suspend fun accessToken(): String = mutex.withLock {
        val state = readState()?.takeIf { it.isAuthenticated() } ?: throw DriveAuthorizationRequiredException()
        if (state.needsTokenRefresh) {
            try {
                state.update(requestToken(state.createTokenRefreshRequest()), null)
                state.needsTokenRefresh = false
                saveState(state)
            } catch (error: AuthorizationException) {
                if (error.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR) {
                    dataStore.edit { it.clear() }
                    throw DriveAuthorizationRequiredException()
                }
                throw error
            }
        }
        state.accessToken!!
    }

    override suspend fun clearAccessToken(token: String) = mutex.withLock {
        if (readState()?.accessToken == token) dataStore.edit { it[refreshKey] = true }
        Unit
    }

    override suspend fun revokeAccess() = mutex.withLock {
        dataStore.edit { it.clear() }
        Unit
    }

    private fun AuthState.isAuthenticated(): Boolean = isAuthorized && refreshToken != null &&
        lastAuthorizationResponse?.request?.clientId == clientId

    private suspend fun readState(): AuthState? {
        val preferences = dataStore.data.first()
        return preferences[stateKey]?.let { json ->
            AuthState.jsonDeserialize(json).apply {
                if (preferences[refreshKey] == true) needsTokenRefresh = true
            }
        }
    }

    private suspend fun saveState(state: AuthState) {
        dataStore.edit {
            it[stateKey] = state.jsonSerializeString()
            it[refreshKey] = state.needsTokenRefresh
        }
    }
}

private suspend fun requestBrowserToken(context: Context, request: TokenRequest): TokenResponse {
    val service = AuthorizationService(context)
    return try {
        suspendCancellableCoroutine { continuation ->
            service.performTokenRequest(request) { response, error ->
                if (error != null) continuation.resumeWithException(error)
                else continuation.resume(response!!)
            }
        }
    } finally {
        service.dispose()
    }
}
