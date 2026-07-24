package moe.antimony.hoshi.features.wallpaper

import moe.antimony.hoshi.R
import org.junit.Assert.assertEquals
import org.junit.Test

class BookCoverIReaderUiTest {
    @Test
    fun unsupportedDeviceExplainsThatTheIntegrationIsUnavailable() {
        assertEquals(
            R.string.book_cover_wallpaper_ireader_not_supported,
            iReaderBookCoverSummaryRes(
                IReaderBookCoverCapability(
                    isSupported = false,
                    isBookCoverScreenSaverSelected = false,
                ),
            ),
        )
    }

    @Test
    fun supportedDevicePromptsForTheSystemBookCoverOption() {
        assertEquals(
            R.string.book_cover_wallpaper_ireader_select_system_option,
            iReaderBookCoverSummaryRes(
                IReaderBookCoverCapability(
                    isSupported = true,
                    isBookCoverScreenSaverSelected = false,
                ),
            ),
        )
    }

    @Test
    fun selectedSystemOptionShowsTheAutomaticUpdateSummary() {
        assertEquals(
            R.string.book_cover_wallpaper_ireader_summary,
            iReaderBookCoverSummaryRes(
                IReaderBookCoverCapability(
                    isSupported = true,
                    isBookCoverScreenSaverSelected = true,
                ),
            ),
        )
    }
}
