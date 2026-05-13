package moe.antimony.hoshi.features.sync

internal data class SyncConnectionActions(
    val showConnect: Boolean,
    val connectEnabled: Boolean,
    val showSignOut: Boolean,
    val signOutEnabled: Boolean,
)

internal fun syncConnectionActions(
    authStatus: DriveAuthStatus,
    isAuthorizing: Boolean,
): SyncConnectionActions =
    when (authStatus) {
        DriveAuthStatus.Connected -> SyncConnectionActions(
            showConnect = false,
            connectEnabled = false,
            showSignOut = true,
            signOutEnabled = !isAuthorizing,
        )
        DriveAuthStatus.GooglePlayServicesUnavailable -> SyncConnectionActions(
            showConnect = true,
            connectEnabled = false,
            showSignOut = false,
            signOutEnabled = false,
        )
        DriveAuthStatus.NotConnected,
        is DriveAuthStatus.Failed,
        -> SyncConnectionActions(
            showConnect = true,
            connectEnabled = !isAuthorizing,
            showSignOut = false,
            signOutEnabled = false,
        )
    }
