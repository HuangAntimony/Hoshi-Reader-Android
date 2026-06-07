package moe.antimony.hoshi.features.backup

import moe.antimony.hoshi.R
import moe.antimony.hoshi.importing.UnsupportedImportFileTypeException
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupFailureMessageTest {
    @Test
    fun backupFailuresUseStableFallbackInsteadOfRawExceptionText() {
        val message = RuntimeException("Unsafe TTU bookdata entry: ../bad.zip").stableBackupFailureMessage(
            fallback = "Unable to import TTU bookdata backup.",
            unsupportedImportMessage = "Select a .zip TTU bookdata backup file.",
        )

        assertEquals("Unable to import TTU bookdata backup.", message)
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
