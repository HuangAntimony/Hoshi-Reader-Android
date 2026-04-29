package moe.antimony.hoshi.features.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class DriveSyncSettingsTest {
    @Test
    fun defaultsKeepGoogleDriveSyncDisabled() {
        val settings = DriveSyncSettings()

        assertFalse(settings.isEnabled)
        assertFalse(settings.autoSyncOnOpen)
        assertFalse(settings.autoSyncOnBookmark)
        assertEquals(null, settings.accountEmail)
        assertEquals(null, settings.lastStatus)
    }

    @Test
    fun storeRoundTripsSettingsAsJson() {
        val file = tempFile()
        val store = DriveSyncSettingsStore(file)
        val settings = DriveSyncSettings(
            isEnabled = true,
            autoSyncOnOpen = true,
            autoSyncOnBookmark = true,
            accountEmail = "reader@example.com",
            lastStatus = "Synced 2 books",
        )

        store.save(settings)

        assertEquals(settings, store.load())
        assertTrue(file.readText().contains("\"accountEmail\""))
    }

    @Test
    fun corruptSettingsReturnDefaults() {
        val file = tempFile()
        file.writeText("{not-json")

        assertEquals(DriveSyncSettings(), DriveSyncSettingsStore(file).load())
    }

    private fun tempFile(): File {
        val dir = createTempDirectory(prefix = "drive-sync-settings").toFile()
        return dir.resolve("settings.json")
    }
}
