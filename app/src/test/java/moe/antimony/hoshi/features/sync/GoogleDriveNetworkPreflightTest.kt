package moe.antimony.hoshi.features.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleDriveNetworkPreflightTest {
    @Test
    fun driveRequestsAreAllowedWhenInternetCapabilityExistsEvenIfNetworkIsNotValidated() {
        assertTrue(
            shouldAttemptDriveRequest(
                hasActiveNetwork = true,
                hasInternetCapability = true,
                hasValidatedCapability = false,
            ),
        )
    }

    @Test
    fun driveRequestsAreRejectedOnlyWhenThereIsNoActiveInternetNetwork() {
        assertFalse(
            shouldAttemptDriveRequest(
                hasActiveNetwork = false,
                hasInternetCapability = true,
                hasValidatedCapability = true,
            ),
        )
        assertFalse(
            shouldAttemptDriveRequest(
                hasActiveNetwork = true,
                hasInternetCapability = false,
                hasValidatedCapability = true,
            ),
        )
    }
}
