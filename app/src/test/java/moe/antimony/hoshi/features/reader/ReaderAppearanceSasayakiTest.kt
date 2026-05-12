package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderAppearanceSasayakiTest {
    @Test
    fun appearanceShowsStatisticsRowsWhenStatisticsAreEnabled() {
        assertEquals(
            listOf("Show Statistics Toggle", "Show Reading Speed", "Show Reading Time"),
            readerAppearanceStatisticsRows(ReaderSettings(enableStatistics = true)),
        )
    }

    @Test
    fun appearanceHidesStatisticsRowsWhenStatisticsAreDisabled() {
        assertTrue(readerAppearanceStatisticsRows(ReaderSettings(enableStatistics = false)).isEmpty())
    }

    @Test
    fun appearanceShowsSasayakiToggleWhenSasayakiIsEnabled() {
        assertEquals(
            listOf("Show Sasayaki Toggle"),
            readerAppearanceSasayakiRows(SasayakiSettings(enabled = true)),
        )
    }

    @Test
    fun appearanceHidesSasayakiToggleWhenSasayakiIsDisabled() {
        assertTrue(readerAppearanceSasayakiRows(SasayakiSettings(enabled = false)).isEmpty())
    }
}
