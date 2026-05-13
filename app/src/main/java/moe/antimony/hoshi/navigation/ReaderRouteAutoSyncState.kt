package moe.antimony.hoshi.navigation

import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import moe.antimony.hoshi.features.sync.SyncSettings

internal data class ReaderRouteAutoSyncState(
    val syncSettings: SyncSettings?,
    val sasayakiSettings: SasayakiSettings?,
) {
    val isReadyToLoad: Boolean
        get() = syncSettings != null && sasayakiSettings != null

    val isReaderAutoSyncEnabled: Boolean
        get() = syncSettings?.enabled == true && syncSettings.autoSyncEnabled

    val shouldSyncOnOpen: Boolean
        get() = isReaderAutoSyncEnabled

    val shouldSyncAudioBook: Boolean
        get() = sasayakiSettings?.enabled == true && sasayakiSettings.syncEnabled
}
