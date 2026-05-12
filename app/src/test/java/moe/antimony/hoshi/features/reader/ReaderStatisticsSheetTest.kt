package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderStatisticsSheetTest {
    @Test
    fun statisticsSheetDoesNotRenderASeparateHeaderOrCloseButton() {
        val chrome = readerStatisticsSheetChrome()

        assertFalse(chrome.showHeader)
        assertFalse(chrome.showCloseButton)
    }

    @Test
    fun statisticsSheetUsesReaderPanelChrome() {
        val chrome = readerStatisticsSheetChrome()

        assertTrue(chrome.opensAsReaderPanel)
    }

    @Test
    fun readerPanelExpandedHeightLeavesTopQuarterAsDismissArea() {
        assertEquals(
            750f,
            readerPanelExpandedHeight(containerHeight = 1000f),
        )
    }

    @Test
    fun readerPanelHandleDragSettlesToFullPartialOrDismiss() {
        val partial = 500f
        val full = 750f
        val threshold = 96f

        assertEquals(
            full,
            readerPanelSettleTarget(
                currentHeight = 720f,
                startHeight = partial,
                totalDrag = -120f,
                partialHeight = partial,
                maxHeight = full,
                threshold = threshold,
            ),
        )
        assertEquals(
            partial,
            readerPanelSettleTarget(
                currentHeight = 830f,
                startHeight = full,
                totalDrag = 140f,
                partialHeight = partial,
                maxHeight = full,
                threshold = threshold,
            ),
        )
        assertNull(
            readerPanelSettleTarget(
                currentHeight = 360f,
                startHeight = partial,
                totalDrag = 160f,
                partialHeight = partial,
                maxHeight = full,
                threshold = threshold,
            ),
        )
        assertNull(
            readerPanelSettleTarget(
                currentHeight = 320f,
                startHeight = full,
                totalDrag = 500f,
                partialHeight = partial,
                maxHeight = full,
                threshold = threshold,
            ),
        )
    }
}
