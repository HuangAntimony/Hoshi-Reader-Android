package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSheetDensityTest {
    @Test
    fun readerSheetRowsUseCompactVerticalMetrics() {
        val metrics = readerSheetDensityMetrics()

        assertEquals(10, metrics.chapterRowVerticalPaddingDp)
        assertEquals(8, metrics.appearanceRowVerticalPaddingDp)
        assertEquals(8, metrics.statisticsRowVerticalPaddingDp)
        assertEquals(8, metrics.sasayakiRowVerticalPaddingDp)
        assertTrue(metrics.appearanceSectionSpacingDp <= 12)
    }

    @Test
    fun readerSheetIconButtonsStayCompactForDenseRows() {
        val metrics = readerSheetDensityMetrics()

        assertEquals(36, metrics.stepperButtonSizeDp)
        assertEquals(20, metrics.stepperIconSizeDp)
        assertEquals(24, metrics.chapterCloseIconSizeDp)
        assertEquals(40, metrics.chapterCloseButtonSizeDp)
    }
}
