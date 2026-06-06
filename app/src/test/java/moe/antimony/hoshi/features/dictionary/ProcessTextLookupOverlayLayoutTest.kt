package moe.antimony.hoshi.features.dictionary

import moe.antimony.hoshi.features.reader.ReaderLookupPopupViewport
import moe.antimony.hoshi.features.reader.ReaderSelectionData
import moe.antimony.hoshi.features.reader.ReaderSelectionRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun processTextIframePayloadCoordinatesCenterOnlyRootPopup() {
        val root = popup("root", x = 10.0, y = 20.0)
        val child = popup("child", x = 50.0, y = 60.0)

        val displayed = processTextLookupDisplayedPopups(
            popups = listOf(root, child),
            viewport = ReaderLookupPopupViewport(width = 400.0, height = 800.0),
            topInset = 96.0,
        )

        val rootFrame = LookupPopupLayout(
            selectionRect = displayed[0].state.selection.rect,
            screenWidth = 400.0,
            screenHeight = 800.0,
            maxWidth = 320.0,
            maxHeight = 250.0,
            isVertical = false,
            topInset = 96.0,
        ).calculate()

        assertEquals(96.0, displayed[0].state.topInset, 0.0)
        assertEquals(200.0, rootFrame.centerX, 0.0)
        assertEquals(448.0, rootFrame.centerY, 0.0)
        assertEquals(child.state.selection.rect, displayed[1].state.selection.rect)
    }

    private fun popup(id: String, x: Double, y: Double): LookupPopupItem = LookupPopupItem(
        id = id,
        state = LookupPopupState(
            selection = ReaderSelectionData(
                text = id,
                sentence = id,
                rect = ReaderSelectionRect(x = x, y = y, width = 1.0, height = 1.0),
                normalizedOffset = null,
            ),
            results = emptyList(),
            isVertical = false,
            width = 320,
            height = 250,
        ),
    )
}
