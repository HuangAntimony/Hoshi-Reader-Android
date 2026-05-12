package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertFalse
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
    fun statisticsSheetSkipsPartialDetentToAvoidAndroidAnchorOscillation() {
        assertTrue(readerStatisticsSheetChrome().skipPartiallyExpanded)
    }
}
