package moe.antimony.hoshi.features.backup

import moe.antimony.hoshi.R
import moe.antimony.hoshi.importing.UnsupportedImportFileTypeException
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupFailureMessageTest {
    @Test
    fun backupFailuresKeepLocalizedSummaryAndUnderlyingCause() {
        val message = RuntimeException("Invalid backup archive", java.io.IOException("Unsafe TTU bookdata entry: ../bad.zip")).stableBackupFailureMessage(
            fallback = "Unable to import TTU bookdata backup.",
            unsupportedImportMessage = "Select a .zip TTU bookdata backup file.",
        )

        org.junit.Assert.assertTrue(message.startsWith("Unable to import TTU bookdata backup."))
        org.junit.Assert.assertTrue(message.contains("Invalid backup archive"))
        org.junit.Assert.assertTrue(message.contains("Unsafe TTU bookdata entry: ../bad.zip"))
    }

    @Test
    fun backupFailuresCanStillUseResourceBackedUnsupportedImportMessages() {
        val message = UnsupportedImportFileTypeException(
            message = "Select a .zip TTU bookdata backup file.",
            messageRes = R.string.import_select_ttu_bookdata_backup,
        ).stableBackupFailureMessage(
            fallback = "Unable to import TTU bookdata backup.",
            unsupportedImportMessage = "Select a .zip TTU bookdata backup file.",
        )

        assertEquals("Select a .zip TTU bookdata backup file.", message)
    }
}
