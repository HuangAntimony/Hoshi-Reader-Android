package moe.antimony.hoshi.features.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncConnectionActionsTest {
    @Test
    fun connectedStateShowsOnlySignOut() {
        val actions = syncConnectionActions(
            authStatus = DriveAuthStatus.Connected,
            isAuthorizing = false,
        )

        assertFalse(actions.showConnect)
        assertTrue(actions.showSignOut)
        assertTrue(actions.signOutEnabled)
    }

    @Test
    fun disconnectedStateShowsOnlyConnect() {
        val actions = syncConnectionActions(
            authStatus = DriveAuthStatus.NotConnected,
            isAuthorizing = false,
        )

        assertTrue(actions.showConnect)
        assertFalse(actions.showSignOut)
        assertTrue(actions.connectEnabled)
    }
}
