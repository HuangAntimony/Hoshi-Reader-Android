package moe.antimony.hoshi.features.sync

import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncSettingsDataRowsTest {
    @Test
    fun dataRowsMatchIosUploadStatsAndAudiobookToggles() {
        val rows = syncSettingsDataRows(
            syncSettings = SyncSettings(enabled = true, uploadBooks = true),
            readerSettings = ReaderSettings(enableStatistics = true, statisticsSyncEnabled = false),
            sasayakiSettings = SasayakiSettings(enabled = true, syncEnabled = true),
        )

        assertEquals(
            listOf(
                R.string.sync_upload_books,
                R.string.sync_stats,
                R.string.sync_audiobook_progress,
            ),
            rows.map { it.titleRes },
        )
        assertEquals(listOf(true, false, true), rows.map { it.checked })
    }

    @Test
    fun dataRowsHideStatsAndAudiobookWhenTheirFeaturesAreDisabledLikeIos() {
        val rows = syncSettingsDataRows(
            syncSettings = SyncSettings(enabled = true, uploadBooks = false),
            readerSettings = ReaderSettings(enableStatistics = false, statisticsSyncEnabled = true),
            sasayakiSettings = SasayakiSettings(enabled = false, syncEnabled = true),
        )

        assertEquals(listOf(R.string.sync_upload_books), rows.map { it.titleRes })
        assertEquals(listOf(false), rows.map { it.checked })
    }
}
