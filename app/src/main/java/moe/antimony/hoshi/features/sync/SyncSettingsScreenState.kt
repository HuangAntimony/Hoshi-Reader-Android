package moe.antimony.hoshi.features.sync

import moe.antimony.hoshi.features.settings.settingsContentReady

internal data class SyncSettingsScreenState(
    val settings: SyncSettings?,
    val authStatus: DriveAuthStatus?,
) {
    val isContentReady: Boolean
        get() = settingsContentReady(settings, authStatus)

    val showClearCacheAction: Boolean
        get() = isContentReady && settings?.enabled == true && authStatus == DriveAuthStatus.Connected
}
