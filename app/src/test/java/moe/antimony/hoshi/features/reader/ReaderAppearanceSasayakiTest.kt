package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import moe.antimony.hoshi.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderAppearanceSasayakiTest {
    @Test
    fun appearanceFontOptionsIncludePublisherFontBeforeUserFonts() {
        assertEquals(
            listOf(
                ReaderFontManager.publisherFont,
                ReaderFontManager.defaultMinchoFont,
                ReaderFontManager.defaultGothicFont,
                "KleeOne-SemiBold",
            ),
            readerAppearanceFontOptions(
                importedFontNames = listOf("KleeOne-SemiBold"),
                selectedFont = ReaderFontManager.publisherFont,
            ),
        )
    }

    @Test
    fun appearanceShowsStatisticsRowsWhenStatisticsAreEnabled() {
        assertEquals(
            listOf(
                ReaderAppearanceStatisticsRow.Toggle,
                ReaderAppearanceStatisticsRow.ReadingSpeed,
                ReaderAppearanceStatisticsRow.ReadingTime,
            ),
            readerAppearanceStatisticsRows(ReaderSettings(enableStatistics = true)),
        )
    }

    @Test
    fun appearanceHidesStatisticsRowsWhenStatisticsAreDisabled() {
        assertTrue(readerAppearanceStatisticsRows(ReaderSettings(enableStatistics = false)).isEmpty())
    }

    @Test
    fun appearanceHidesProgressPositionWhenProgressIsAlwaysShown() {
        assertTrue(readerAppearanceShowsAlwaysShowProgress(ReaderSettings()))
        assertTrue(!readerAppearanceShowsProgressPosition(ReaderSettings()))
        assertTrue(
            readerAppearanceShowsProgressPosition(
                ReaderSettings(alwaysShowProgress = false),
            ),
        )
        assertTrue(
            !readerAppearanceShowsAlwaysShowProgress(
                ReaderSettings(showCharacters = false, showPercentage = false),
            ),
        )
    }

    @Test
    fun appearanceShowsSasayakiToggleWhenSasayakiIsEnabled() {
        assertEquals(
            listOf(R.string.reader_appearance_show_sasayaki_toggle),
            readerAppearanceSasayakiRows(SasayakiSettings(enabled = true)),
        )
    }

    @Test
    fun appearanceHidesSasayakiToggleWhenSasayakiIsDisabled() {
        assertTrue(readerAppearanceSasayakiRows(SasayakiSettings(enabled = false)).isEmpty())
    }

    @Test
    fun appearanceShowsCustomThemeControlsOnlyForCustomTheme() {
        assertTrue(readerAppearanceShowsCustomInterfaceTheme(ReaderSettings(theme = ReaderTheme.Custom)))
        assertTrue(!readerAppearanceShowsCustomInterfaceTheme(ReaderSettings(theme = ReaderTheme.Sepia)))
        assertEquals(
            listOf(
                ReaderAppearanceCustomColorRow.Background,
                ReaderAppearanceCustomColorRow.Text,
                ReaderAppearanceCustomColorRow.Info,
            ),
            readerAppearanceCustomColorRows(ReaderSettings(theme = ReaderTheme.Custom)),
        )
        assertTrue(readerAppearanceCustomColorRows(ReaderSettings(theme = ReaderTheme.Light)).isEmpty())
    }

    @Test
    fun bottomSafeAreaSliderUsesTwoDpSteps() {
        assertEquals(26, readerAppearanceBottomSafeAreaSliderSteps())
        assertEquals(18, readerAppearanceBottomSafeAreaFromSlider(17.2f))
        assertEquals(18, readerAppearanceBottomSafeAreaFromSlider(18f))
        assertEquals(40, readerAppearanceBottomSafeAreaFromSlider(39.2f))
        assertEquals(40, readerAppearanceBottomSafeAreaFromSlider(40.8f))
        assertEquals(72, readerAppearanceBottomSafeAreaFromSlider(100f))
    }
}
