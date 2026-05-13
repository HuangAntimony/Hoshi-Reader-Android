package moe.antimony.hoshi.features.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedSettingsRowsTest {
    @Test
    fun advancedSettingsRowsMatchIosSectionStructureForSyncAndBackup() {
        val sections = advancedSettingsSections()

        assertEquals(
            listOf(
                listOf("Audio", "Statistics", "Sasayaki (Audiobooks)"),
                listOf("ッツ Sync"),
                listOf("Backup"),
            ),
            sections.map { section -> section.rows.map { it.title } },
        )

        val syncRow = sections.flatMap { it.rows }.single { it.destination == AdvancedDestination.Syncing }
        val backupRow = sections.flatMap { it.rows }.single { it.destination == AdvancedDestination.Backup }

        assertEquals(AdvancedSettingsIcon.Cloud, syncRow.icon)
        assertEquals(AdvancedSettingsIcon.ExternalDrive, backupRow.icon)
        assertFalse(backupRow.icon == AdvancedSettingsIcon.Cloud)
        assertTrue(sections.indexOfFirst { it.rows.any { row -> row.destination == AdvancedDestination.Syncing } } !=
            sections.indexOfFirst { it.rows.any { row -> row.destination == AdvancedDestination.Backup } })
    }
}
