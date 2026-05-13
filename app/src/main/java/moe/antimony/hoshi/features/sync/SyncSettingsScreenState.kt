package moe.antimony.hoshi.features.sync

internal data class SyncSettingsScreenState(
    val settings: SyncSettings?,
    val authStatus: DriveAuthStatus?,
) {
    val isContentReady: Boolean
        get() = settings != null && authStatus != null
}
