package moe.antimony.hoshi.features.sync

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.Scope
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.UiText

private val Context.hoshiDriveAuthDataStore by preferencesDataStore(name = "hoshi-drive-auth")
private val connectedKey = booleanPreferencesKey("connected")
private val recoveryKey = booleanPreferencesKey("authorization-recovery")

internal suspend fun DataStore<Preferences>.nativeDriveStatus(): DriveAuthStatus {
    val preferences = data.first()
    return when {
        preferences[connectedKey] != true -> DriveAuthStatus.NotConnected
        preferences[recoveryKey] == true -> DriveAuthStatus.RecoveryRequired
        else -> DriveAuthStatus.Connected
    }
}

internal suspend fun DataStore<Preferences>.setNativeDriveConnected(connected: Boolean) {
    edit {
        it[connectedKey] = connected
        it.remove(recoveryKey)
    }
}

internal suspend fun DataStore<Preferences>.completeNativeDriveRecovery() {
    edit { it.remove(recoveryKey) }
}

internal suspend fun DataStore<Preferences>.nativeDriveAccessToken(authorize: suspend () -> String): String = try {
    authorize()
} catch (error: NativeDriveAuthorizationRecoveryException) {
    // A resolution is not evidence that the grant was revoked. Keep the link retryable.
    edit { if (it[connectedKey] == true) it[recoveryKey] = true }
    throw error
}

@Singleton
class GoogleDriveAuth @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val ttu: DeviceCodeDriveAuthorizer,
    private val settings: Lazy<SyncSettingsRepository>,
    val browser: GoogleDriveBrowserAuth,
) : DriveAuthorizer {
    private val client by lazy { Identity.getAuthorizationClient(context) }

    suspend fun provider(): SyncProvider = settings.get().settings.first().provider

    fun usesBrowserAuthorization(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS

    suspend fun authorize(selectAccount: Boolean = false): AuthorizationResult =
        authorize(selectAccount, authorizationContext())

    private suspend fun authorize(selectAccount: Boolean, snapshot: NativeDriveAuthorizationContext): AuthorizationResult =
        withNativeDriveAuthorizationDiagnostics(snapshot, selectAccount) {
            client.authorize(
                AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DriveFileScope)))
                    .apply { if (selectAccount) setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT) }
                    .build(),
            ).await()
        }

    private suspend fun authorizationContext(): NativeDriveAuthorizationContext {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity?.activeNetwork
        val capabilities = network?.let { connectivity.getNetworkCapabilities(it) }
        return NativeDriveAuthorizationContext(
            connected = context.hoshiDriveAuthDataStore.nativeDriveStatus().isLinked,
            playServicesStatus = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context),
            hasNetwork = network != null,
            hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        )
    }

    suspend fun authorizationResult(intent: Intent?): AuthorizationResult =
        withNativeDriveAuthorizationDiagnostics(authorizationContext(), selectAccount = null) {
            client.getAuthorizationResultFromIntent(intent)
        }

    internal suspend fun recoveryRequired(): Boolean = context.hoshiDriveAuthDataStore.nativeDriveStatus() == DriveAuthStatus.RecoveryRequired

    internal suspend fun completeRecovery() = context.hoshiDriveAuthDataStore.completeNativeDriveRecovery()

    suspend fun accept() {
        ttu.revokeAccess()
        browser.revokeAccess()
        context.hoshiDriveAuthDataStore.setNativeDriveConnected(true)
    }

    suspend fun acceptBrowserAuthorization(intent: Intent?) {
        browser.accept(intent)
        ttu.revokeAccess()
        context.hoshiDriveAuthDataStore.setNativeDriveConnected(false)
    }

    suspend fun clearHoshiLogin() {
        browser.revokeAccess()
        context.hoshiDriveAuthDataStore.setNativeDriveConnected(false)
    }

    override suspend fun status(): DriveAuthStatus = status(provider())

    suspend fun status(provider: SyncProvider): DriveAuthStatus = when (provider) {
        SyncProvider.Ttu -> ttu.status()
        SyncProvider.Gdrive -> {
            val browserStatus = browser.status()
            if (browserStatus == DriveAuthStatus.Connected || usesBrowserAuthorization()) browserStatus
            else context.hoshiDriveAuthDataStore.nativeDriveStatus()
        }
    }

    override suspend fun accessToken(): String {
        if (provider() == SyncProvider.Ttu) return ttu.accessToken()
        if (browser.status() == DriveAuthStatus.Connected) return browser.accessToken()
        val snapshot = authorizationContext()
        if (!status(SyncProvider.Gdrive).isLinked) throw DriveAuthorizationRequiredException(
            NativeDriveAuthorizationException("Native Google Drive authorization unavailable: ${snapshot.details()}"),
        )
        return context.hoshiDriveAuthDataStore.nativeDriveAccessToken {
            val result = authorize(selectAccount = false, snapshot)
            nativeDriveToken(result.accessToken, result.hasResolution(), result.grantedScopes, snapshot)
        }
    }

    override suspend fun clearAccessToken(token: String) {
        if (provider() == SyncProvider.Gdrive) {
            ErrorDiagnosticEvents.buffer.recordServiceFailure(
                UiText.Resource(R.string.sync_connect_google_drive),
                "Google Drive cached access token invalidated after HTTP 401; " +
                    "browserAuthorization=${browser.status() == DriveAuthStatus.Connected}",
            )
        }
        if (provider() == SyncProvider.Ttu) ttu.clearAccessToken(token)
        else if (browser.status() == DriveAuthStatus.Connected) browser.clearAccessToken(token)
        else client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
    }

    override suspend fun revokeAccess() {
        if (provider() == SyncProvider.Ttu) ttu.revokeAccess() else clearHoshiLogin()
    }

    companion object {
        const val DriveFileScope = "https://www.googleapis.com/auth/drive.file"
    }
}
