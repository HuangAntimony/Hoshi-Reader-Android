package moe.antimony.hoshi.features.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleCloudOAuthConfigurationTest {
    @Test
    fun instructionsDescribeRequiredAndroidOAuthClientSetup() {
        val steps = GoogleCloudOAuthConfiguration.instructions

        assertEquals(4, steps.size)
        assertTrue(steps.any { it.contains("Google Drive API") })
        assertTrue(steps.any { it.contains("OAuth client ID") && it.contains("Android") })
        assertTrue(steps.any { it.contains("package name") && it.contains("SHA-1") })
        assertTrue(steps.any { it.contains("reconnect") })
    }
}
