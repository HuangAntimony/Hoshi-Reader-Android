package moe.antimony.hoshi.features.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupActionButtonWebViewTest {
    @Test
    fun iconPaddingScalesWithButtonFrame() {
        assertEquals(4, popupActionButtonIconPaddingPx(width = 28, height = 28))
        assertEquals(6, popupActionButtonIconPaddingPx(width = 42, height = 42))
        assertEquals(8, popupActionButtonIconPaddingPx(width = 56, height = 56))
    }

    @Test
    fun hiddenPopupWebViewDoesNotDispatchTouchEvents() {
        assertFalse(popupWebViewDispatchesTouch(isPopupInputEnabled = false))
        assertTrue(popupWebViewDispatchesTouch(isPopupInputEnabled = true))
    }
}
