package moe.antimony.hoshi.navigation

import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import moe.antimony.hoshi.features.sync.SyncSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderRouteAutoSyncStateTest {
    @Test
    fun readerLoadWaitsForSyncAndSasayakiSettingsBeforeOpenImportDecision() {
        assertFalse(
            ReaderRouteAutoSyncState(
                syncSettings = null,
                sasayakiSettings = SasayakiSettings(),
            ).isReadyToLoad,
        )
        assertFalse(
            ReaderRouteAutoSyncState(
                syncSettings = SyncSettings(enabled = true, autoSyncEnabled = true),
                sasayakiSettings = null,
            ).isReadyToLoad,
        )
    }

    @Test
    fun openImportRunsOnlyAfterLoadedGlobalSyncAndAutoSyncAreEnabled() {
        assertFalse(
            ReaderRouteAutoSyncState(
                syncSettings = SyncSettings(enabled = false, autoSyncEnabled = true),
                sasayakiSettings = SasayakiSettings(),
            ).shouldSyncOnOpen,
        )
        assertFalse(
            ReaderRouteAutoSyncState(
                syncSettings = SyncSettings(enabled = true, autoSyncEnabled = false),
                sasayakiSettings = SasayakiSettings(),
            ).shouldSyncOnOpen,
        )
        assertTrue(
            ReaderRouteAutoSyncState(
                syncSettings = SyncSettings(enabled = true, autoSyncEnabled = true),
                sasayakiSettings = SasayakiSettings(),
            ).shouldSyncOnOpen,
        )
    }

    @Test
    fun audioBookSyncRequiresLoadedSasayakiSyncSettings() {
        assertFalse(
            ReaderRouteAutoSyncState(
                syncSettings = SyncSettings(enabled = true, autoSyncEnabled = true),
                sasayakiSettings = SasayakiSettings(enabled = false, syncEnabled = true),
            ).shouldSyncAudioBook,
        )
        assertTrue(
            ReaderRouteAutoSyncState(
                syncSettings = SyncSettings(enabled = true, autoSyncEnabled = true),
                sasayakiSettings = SasayakiSettings(enabled = true, syncEnabled = true),
            ).shouldSyncAudioBook,
        )
    }
}
