package moe.antimony.hoshi.features.sync

import android.content.Intent

class DriveConnectionAuthorizer(
    private val delegate: DriveAuthorizer,
    private val connectionStore: DriveConnectionStore,
) : DriveAuthorizer {
    override suspend fun accessToken(): String {
        if (!connectionStore.isConnected()) {
            throw DriveAuthorizationRequiredException()
        }
        return delegate.accessToken()
    }

    override suspend fun clearAccessToken(token: String) {
        delegate.clearAccessToken(token)
    }

    override suspend fun authorize(): DriveAuthorizationResult =
        delegate.authorize().also(::updateConnectionAfterAuthorization)

    override fun authorizationResultFromIntent(data: Intent?): DriveAuthorizationResult =
        delegate.authorizationResultFromIntent(data).also(::updateConnectionAfterAuthorization)

    override suspend fun revokeAccess() {
        connectionStore.setConnected(false)
        runCatching { delegate.revokeAccess() }
    }

    override suspend fun status(): DriveAuthStatus {
        if (!connectionStore.isConnected()) {
            return DriveAuthStatus.NotConnected
        }
        return delegate.status()
    }

    private fun updateConnectionAfterAuthorization(result: DriveAuthorizationResult) {
        when (result) {
            is DriveAuthorizationResult.Authorized -> connectionStore.setConnected(true)
            DriveAuthorizationResult.GooglePlayServicesUnavailable,
            is DriveAuthorizationResult.Failed,
            is DriveAuthorizationResult.RequiresResolution,
            -> Unit
        }
    }
}
