package moe.antimony.hoshi.features.sync

import android.content.Context
import android.content.Intent
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

private val Context.hoshiDriveAuthDataStore by preferencesDataStore(name = "hoshi-drive-auth")
private val connectedKey = booleanPreferencesKey("connected")

internal suspend fun DataStore<Preferences>.nativeDriveStatus(): DriveAuthStatus =
    if (data.first()[connectedKey] == true) DriveAuthStatus.Connected else DriveAuthStatus.NotConnected

internal suspend fun DataStore<Preferences>.nativeDriveAccessToken(authorize: suspend () -> String): String = try {
    authorize()
} catch (error: DriveAuthorizationRequiredException) {
    // User consent is required: expose Connect before reporting the sync failure.
    edit { it[connectedKey] = false }
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

    suspend fun authorize(selectAccount: Boolean = false): AuthorizationResult = client.authorize(
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DriveFileScope)))
            .apply { if (selectAccount) setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT) }
            .build(),
    ).await()

    fun authorizationResult(intent: Intent?): AuthorizationResult = client.getAuthorizationResultFromIntent(intent)

    suspend fun accept() {
        ttu.revokeAccess()
        browser.revokeAccess()
        context.hoshiDriveAuthDataStore.edit { it[connectedKey] = true }
    }

    suspend fun acceptBrowserAuthorization(intent: Intent?) {
        browser.accept(intent)
        ttu.revokeAccess()
        context.hoshiDriveAuthDataStore.edit { it[connectedKey] = false }
    }

    suspend fun clearHoshiLogin() {
        browser.revokeAccess()
        context.hoshiDriveAuthDataStore.edit { it[connectedKey] = false }
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
        if (status(SyncProvider.Gdrive) != DriveAuthStatus.Connected) throw DriveAuthorizationRequiredException()
        return context.hoshiDriveAuthDataStore.nativeDriveAccessToken {
            val result = authorize()
            if (result.hasResolution()) throw DriveAuthorizationRequiredException()
            result.accessToken!!
        }
    }

    override suspend fun clearAccessToken(token: String) {
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
