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
}
