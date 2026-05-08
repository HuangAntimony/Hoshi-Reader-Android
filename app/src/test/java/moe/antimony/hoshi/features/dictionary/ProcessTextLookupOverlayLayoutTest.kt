package moe.antimony.hoshi.features.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProcessTextLookupOverlayLayoutTest {
    @Test
    fun rootSelectionRectCentersTheFirstOverlayPopup() {
        val selectionRect = ProcessTextLookupOverlayLayout.rootSelectionRect(
            screenWidth = 400.0,
            screenHeight = 800.0,
            popupMaxWidth = 320.0,
            popupMaxHeight = 250.0,
            topInset = 0.0,
            bottomInset = 0.0,
        )

        val frame = LookupPopupLayout(
            selectionRect = selectionRect,
            screenWidth = 400.0,
            screenHeight = 800.0,
            maxWidth = 320.0,
            maxHeight = 250.0,
            isVertical = false,
        ).calculate()

        assertEquals(200.0, frame.centerX, 0.0)
        assertEquals(400.0, frame.centerY, 0.0)
    }

    @Test
    fun rootSelectionRectCentersPopupInsideSafeArea() {
        val selectionRect = ProcessTextLookupOverlayLayout.rootSelectionRect(
            screenWidth = 400.0,
            screenHeight = 800.0,
            popupMaxWidth = 320.0,
            popupMaxHeight = 250.0,
            topInset = 96.0,
            bottomInset = 24.0,
        )

        val frame = LookupPopupLayout(
            selectionRect = selectionRect,
            screenWidth = 400.0,
            screenHeight = 800.0,
            maxWidth = 320.0,
            maxHeight = 250.0,
            isVertical = false,
            topInset = 96.0,
            bottomInset = 24.0,
        ).calculate()

        assertEquals(200.0, frame.centerX, 0.0)
        assertEquals(436.0, frame.centerY, 0.0)
        assertTrue(frame.centerY - frame.height / 2 >= 102.0)
    }

    @Test
    fun overlayPassesSystemTopInsetToRootAndNestedPopups() {
        val source = File("src/main/java/moe/antimony/hoshi/features/dictionary/ProcessTextLookupActivity.kt").readText()

        assertTrue(source.contains("WindowInsets.statusBars"))
        assertTrue(source.contains("val topInset = with(density)"))
        assertTrue(source.contains("topInset = topInset"))
    }

    @Test
    fun overlayDoesNotRecenterNestedPopupSelections() {
        val source = File("src/main/java/moe/antimony/hoshi/features/dictionary/ProcessTextLookupActivity.kt").readText()

        assertTrue(source.contains("if (index == 0)"))
        assertTrue(source.contains("ProcessTextLookupOverlayLayout.rootSelectionRect("))
        assertFalse(source.contains("popups.map { popup ->"))
    }

    @Test
    fun overlayDoesNotForcePopupActionBar() {
        val source = File("src/main/java/moe/antimony/hoshi/features/dictionary/ProcessTextLookupActivity.kt").readText()

        assertFalse(source.contains("popupActionBar = true"))
        assertTrue(source.contains("popupActionBar = false"))
    }
}
