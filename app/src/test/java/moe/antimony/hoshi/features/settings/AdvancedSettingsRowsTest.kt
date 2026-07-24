package moe.antimony.hoshi.features.settings

import moe.antimony.hoshi.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedSettingsRowsTest {
    @Test
    fun advancedSettingsRowsKeepIntegrationsAndBackupInExpectedSections() {
        val sections = advancedSettingsSections()

        assertEquals(
            listOf(
                listOf(R.string.advanced_audio, R.string.advanced_statistics, R.string.advanced_sasayaki_audiobooks),
                listOf(R.string.sync_ttu_sync, R.string.anki_connect_use),
                listOf(R.string.settings_book_cover_wallpaper),
                listOf(R.string.settings_backup),
            ),
            sections.map { section -> section.rows.map { it.titleRes } },
        )

        val syncRow = sections.flatMap { it.rows }.single { it.destination == AdvancedDestination.Syncing }
        val ankiConnectRow = sections.flatMap { it.rows }.single { it.destination == AdvancedDestination.AnkiConnect }
        val backupRow = sections.flatMap { it.rows }.single { it.destination == AdvancedDestination.Backup }
        val wallpaperRow = sections.flatMap { it.rows }.single {
            it.destination == AdvancedDestination.BookCoverWallpaper
        }

        assertEquals(AdvancedSettingsIcon.Cloud, syncRow.icon)
        assertEquals(AdvancedSettingsIcon.AnkiConnect, ankiConnectRow.icon)
        assertEquals(AdvancedSettingsIcon.ExternalDrive, backupRow.icon)
        assertEquals(AdvancedSettingsIcon.Wallpaper, wallpaperRow.icon)
        assertFalse(backupRow.icon == AdvancedSettingsIcon.Cloud)
        assertTrue(sections.indexOfFirst { it.rows.any { row -> row.destination == AdvancedDestination.Syncing } } !=
            sections.indexOfFirst { it.rows.any { row -> row.destination == AdvancedDestination.Backup } })
    }
}
