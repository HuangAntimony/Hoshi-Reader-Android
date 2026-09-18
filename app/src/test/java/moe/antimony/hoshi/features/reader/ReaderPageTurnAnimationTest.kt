package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPageTurnAnimationTest {
    @Test
    fun animationOnlyAppliesToPaginatedNonEInkReading() {
        assertTrue(ReaderSettings().shouldAnimatePageTurns())
        assertFalse(ReaderSettings(pageTurnAnimation = false).shouldAnimatePageTurns())
        assertFalse(ReaderSettings(eInkMode = true).shouldAnimatePageTurns())
        assertFalse(ReaderSettings(viewMode = ReaderViewMode.Continuous).shouldAnimatePageTurns())
        assertFalse(ReaderSettings(viewMode = ReaderViewMode.VisualNovel).shouldAnimatePageTurns())
    }

    @Test
    fun slideFollowsTheReadingDirectionInBothWritingModes() {
        assertEquals(1, readerPageTurnSign(true, ReaderNavigationDirection.Forward))
        assertEquals(-1, readerPageTurnSign(true, ReaderNavigationDirection.Backward))
        assertEquals(-1, readerPageTurnSign(false, ReaderNavigationDirection.Forward))
        assertEquals(1, readerPageTurnSign(false, ReaderNavigationDirection.Backward))
    }
}
