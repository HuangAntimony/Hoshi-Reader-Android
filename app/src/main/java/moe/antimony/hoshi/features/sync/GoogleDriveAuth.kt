package moe.antimony.hoshi.features.sync

import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await

private val Context.hoshiDriveAuthDataStore by preferencesDataStore(name = "hoshi-drive-auth")

@Singleton
class GoogleDriveAuth @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val ttu: DeviceCodeDriveAuthorizer,
    private val settings: Lazy<SyncSettingsRepository>,
) : DriveAuthorizer {
    private val client = Identity.getAuthorizationClient(context)
    private val connectedKey = booleanPreferencesKey("connected")

    suspend fun provider(): SyncProvider = settings.get().settings.first().provider

    suspend fun authorize(selectAccount: Boolean = false): AuthorizationResult = client.authorize(
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DriveFileScope)))
            .apply { if (selectAccount) setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT) }
            .build(),
    ).await()

    fun authorizationResult(intent: Intent?): AuthorizationResult = client.getAuthorizationResultFromIntent(intent)

    suspend fun accept() {
        ttu.revokeAccess()
        context.hoshiDriveAuthDataStore.edit { it[connectedKey] = true }
    }

    suspend fun clearHoshiLogin() {
        context.hoshiDriveAuthDataStore.edit { it[connectedKey] = false }
    }

    override suspend fun status(): DriveAuthStatus = status(provider())

    suspend fun status(provider: SyncProvider): DriveAuthStatus = when (provider) {
        SyncProvider.Ttu -> ttu.status()
        SyncProvider.Gdrive -> if (context.hoshiDriveAuthDataStore.data.first()[connectedKey] == true) DriveAuthStatus.Connected else DriveAuthStatus.NotConnected
    }

    override suspend fun accessToken(): String {
        if (provider() == SyncProvider.Ttu) return ttu.accessToken()
        if (status(SyncProvider.Gdrive) != DriveAuthStatus.Connected) throw DriveAuthorizationRequiredException()
        val result = authorize()
        if (result.hasResolution()) throw DriveAuthorizationRequiredException()
        return result.accessToken!!
    }

    override suspend fun clearAccessToken(token: String) {
        if (provider() == SyncProvider.Ttu) ttu.clearAccessToken(token)
        else client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
    }

    override suspend fun revokeAccess() {
        if (provider() == SyncProvider.Ttu) ttu.revokeAccess() else clearHoshiLogin()
    }

    companion object {
        const val DriveFileScope = "https://www.googleapis.com/auth/drive.file"
    }
}
