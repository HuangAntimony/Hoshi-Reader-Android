package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSheetDensityTest {
    @Test
    fun readerSheetRowsUseCompactVerticalMetrics() {
        val metrics = readerSheetDensityMetrics()

        assertEquals(8, metrics.chapterRowVerticalPaddingDp)
        assertEquals(6, metrics.appearanceRowVerticalPaddingDp)
        assertEquals(6, metrics.statisticsRowVerticalPaddingDp)
        assertEquals(6, metrics.sasayakiRowVerticalPaddingDp)
        assertTrue(metrics.appearanceSectionSpacingDp <= 10)
    }

    @Test
    fun readerSheetIconButtonsStayCompactForDenseRows() {
        val metrics = readerSheetDensityMetrics()

        assertEquals(36, metrics.stepperButtonSizeDp)
        assertEquals(20, metrics.stepperIconSizeDp)
    }

    @Test
    fun appearanceControlsUseMatchedCompactHeights() {
        val metrics = readerSheetDensityMetrics()

        assertEquals(34, metrics.appearanceSegmentedControlHeightDp)
        assertEquals(0, metrics.appearanceSwitchMinimumInteractiveSizeDp)
        assertEquals(metrics.appearanceRowVerticalPaddingDp, metrics.appearanceFontRowVerticalPaddingDp)
    }

}
