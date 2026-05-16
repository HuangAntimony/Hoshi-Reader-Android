package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderFloatingToolbarAnchorTest {
    @Test
    fun touchAnchorProducesVisibleContentRect() {
        val rect = ReaderFloatingToolbarAnchor.rectForTouch(
            viewWidth = 1080,
            viewHeight = 2032,
            touchX = 625f,
            touchY = 623f,
            density = 3f,
        )

        assertTrue(rect.left >= 0)
        assertTrue(rect.top >= 0)
        assertTrue(rect.right <= 1080)
        assertTrue(rect.bottom <= 2032)
        assertTrue(rect.right > rect.left)
        assertTrue(rect.bottom > rect.top)
    }

    @Test
    fun touchAnchorClampsNearTopEdge() {
        val rect = ReaderFloatingToolbarAnchor.rectForTouch(
            viewWidth = 1080,
            viewHeight = 2032,
            touchX = 20f,
            touchY = -300f,
            density = 3f,
        )

        assertEquals(0, rect.left)
        assertEquals(0, rect.top)
        assertTrue(rect.right > rect.left)
        assertTrue(rect.bottom > rect.top)
    }
}
