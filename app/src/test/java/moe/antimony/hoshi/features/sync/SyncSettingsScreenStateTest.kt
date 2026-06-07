package moe.antimony.hoshi.features.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncSettingsScreenStateTest {
    @Test
    fun contentIsHiddenUntilSettingsAndAuthStatusAreBothLoaded() {
        assertFalse(SyncSettingsScreenState(settings = null, authStatus = null).isContentReady)
        assertFalse(SyncSettingsScreenState(settings = SyncSettings(enabled = true), authStatus = null).isContentReady)
        assertFalse(SyncSettingsScreenState(settings = null, authStatus = DriveAuthStatus.Connected).isContentReady)
        assertTrue(
            SyncSettingsScreenState(
                settings = SyncSettings(enabled = true),
                authStatus = DriveAuthStatus.Connected,
            ).isContentReady,
        )
    }

    @Test
    fun clearCacheActionIsShownOnlyWhenConnectedLikeIos() {
        assertTrue(
            SyncSettingsScreenState(
                settings = SyncSettings(enabled = true),
                authStatus = DriveAuthStatus.Connected,
            ).showClearCacheAction,
        )
        assertFalse(
            SyncSettingsScreenState(
                settings = SyncSettings(enabled = false),
                authStatus = DriveAuthStatus.Connected,
            ).showClearCacheAction,
        )
        assertFalse(
            SyncSettingsScreenState(
                settings = SyncSettings(enabled = true),
                authStatus = DriveAuthStatus.NotConnected,
            ).showClearCacheAction,
        )
        assertFalse(
            SyncSettingsScreenState(
                settings = SyncSettings(enabled = true),
                authStatus = DriveAuthStatus.MissingConfiguration,
            ).showClearCacheAction,
        )
        assertFalse(
            SyncSettingsScreenState(
                settings = SyncSettings(enabled = true),
                authStatus = DriveAuthStatus.Failed("failed"),
            ).showClearCacheAction,
        )
        assertFalse(SyncSettingsScreenState(settings = null, authStatus = DriveAuthStatus.Connected).showClearCacheAction)
    }
}
