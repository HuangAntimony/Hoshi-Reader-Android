package moe.antimony.hoshi.features.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleCloudOAuthConfigurationTest {
    @Test
    fun configurationExplainsTtuSetupAndRequiredAndroidOAuthClientSetup() {
        val configuration = GoogleCloudOAuthConfiguration
        val steps = configuration.instructions

        assertEquals(
            "https://github.com/ttu-ttu/ebook-reader?tab=readme-ov-file#storage-sources",
            configuration.ttuSetupUrl,
        )
        assertTrue(configuration.introduction.contains("Hoshi and ッツ"))
        assertEquals(5, steps.size)
        assertTrue(steps.any { it.contains("ッツ") && it.contains("Google Cloud setup") })
        assertTrue(steps.any { it.contains("Google Drive API") })
        assertTrue(steps.any { it.contains("OAuth client ID") && it.contains("Android") })
        assertTrue(steps.any { it.contains("package name") && it.contains("SHA-1") })
        assertTrue(steps.any { it.contains("Connect Google Drive") })
    }
}
