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
    fun appearanceKeepsStatisticsDisplayPreferencesAvailable() {
        assertEquals(
            listOf(
                ReaderAppearanceStatisticsRow.Toggle,
                ReaderAppearanceStatisticsRow.ReadingSpeed,
                ReaderAppearanceStatisticsRow.ReadingTime,
            ),
            readerAppearanceStatisticsRows(),
        )
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
                ReaderSettings(showProgress = false, showChapterProgress = false),
            ),
        )
        assertTrue(
            readerAppearanceShowsAlwaysShowProgress(
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
    fun topSafeAreaSliderUsesTwoDpSteps() {
        assertEquals(20, readerAppearanceTopSafeAreaSliderSteps())
        assertEquals(30, readerAppearanceTopSafeAreaFromSlider(29.2f))
        assertEquals(30, readerAppearanceTopSafeAreaFromSlider(30f))
        assertEquals(40, readerAppearanceTopSafeAreaFromSlider(39.2f))
        assertEquals(40, readerAppearanceTopSafeAreaFromSlider(40.8f))
        assertEquals(72, readerAppearanceTopSafeAreaFromSlider(100f))
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

    @Test
    fun pageSwipeThresholdIsVisibleOnlyForPagedReaderModes() {
        assertTrue(readerAppearanceShowsPageSwipeThreshold(ReaderViewMode.Paginated))
        assertTrue(readerAppearanceShowsPageSwipeThreshold(ReaderViewMode.VisualNovel))
        assertTrue(!readerAppearanceShowsPageSwipeThreshold(ReaderViewMode.Continuous))
    }

    @Test
    fun pageSwipeThresholdSliderUsesEighteenPixelSteps() {
        assertEquals(19, readerAppearancePageSwipeThresholdSliderSteps())
        assertEquals(0, readerAppearancePageSwipeThresholdFromSlider(0f))
        assertEquals(72, readerAppearancePageSwipeThresholdFromSlider(70f))
        assertEquals(360, readerAppearancePageSwipeThresholdFromSlider(500f))
    }
}
