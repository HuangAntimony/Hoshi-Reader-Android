package moe.antimony.hoshi.features.dictionary

import moe.antimony.hoshi.features.reader.ReaderSelectionData
import moe.antimony.hoshi.features.reader.ReaderSelectionRect
import moe.antimony.hoshi.features.audio.AudioSettings
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LookupPopupTest {
    @Test
    fun verticalLayoutChoosesLargerSideLikeIosPopupLayout() {
        val layout = LookupPopupLayout(
            selectionRect = ReaderSelectionRect(x = 100.0, y = 200.0, width = 20.0, height = 30.0),
            screenWidth = 400.0,
            screenHeight = 800.0,
            maxWidth = 320.0,
            maxHeight = 250.0,
            isVertical = true,
        )

        val result = layout.calculate()

        assertEquals(270.0, result.width, 0.0)
        assertEquals(250.0, result.height, 0.0)
        assertEquals(259.0, result.centerX, 0.0)
        assertEquals(325.0, result.centerY, 0.0)
    }

    @Test
    fun horizontalLayoutAppearsBelowSelectionWhenThereIsRoom() {
        val layout = LookupPopupLayout(
            selectionRect = ReaderSelectionRect(x = 100.0, y = 100.0, width = 20.0, height = 30.0),
            screenWidth = 400.0,
            screenHeight = 800.0,
            maxWidth = 320.0,
            maxHeight = 250.0,
            isVertical = false,
        )

        val result = layout.calculate()

        assertEquals(320.0, result.width, 0.0)
        assertEquals(250.0, result.height, 0.0)
        assertEquals(234.0, result.centerX, 0.0)
        assertEquals(259.0, result.centerY, 0.0)
    }

    @Test
    fun fullWidthLayoutMatchesIosPopupLayout() {
        val layout = LookupPopupLayout(
            selectionRect = ReaderSelectionRect(x = 100.0, y = 100.0, width = 20.0, height = 30.0),
            screenWidth = 400.0,
            screenHeight = 800.0,
            maxWidth = 320.0,
            maxHeight = 250.0,
            isVertical = true,
            isFullWidth = true,
        )

        val result = layout.calculate()

        assertEquals(388.0, result.width, 0.0)
        assertEquals(250.0, result.height, 0.0)
        assertEquals(200.0, result.centerX, 0.0)
        assertEquals(669.0, result.centerY, 0.0)
    }

    @Test
    fun verticalLayoutUsesIosClampWhenPopupIsTallerThanAvailableHeight() {
        val layout = LookupPopupLayout(
            selectionRect = ReaderSelectionRect(x = 100.0, y = 0.0, width = 20.0, height = 30.0),
            screenWidth = 400.0,
            screenHeight = 244.62222290039062,
            maxWidth = 320.0,
            maxHeight = 250.0,
            isVertical = true,
        )

        val result = layout.calculate()

        assertEquals(131.0, result.centerY, 0.0)
    }

    @Test
    fun rootSelectionOffsetMovesOnlyRootPopupAnchor() {
        val popups = listOf("root", "child").mapIndexed { index, id ->
            LookupPopupItem(
                id = id,
                state = LookupPopupState(
                    selection = ReaderSelectionData(
                        text = id,
                        sentence = id,
                        rect = ReaderSelectionRect(
                            x = 10.0 + index,
                            y = 20.0 + index,
                            width = 30.0,
                            height = 40.0,
                        ),
                        normalizedOffset = null,
                    ),
                    results = emptyList(),
                ),
            )
        }

        val shifted = popups.withRootSelectionOffset(offsetX = 5.0, offsetY = 7.0)

        assertEquals(15.0, shifted[0].state.selection.rect.x, 0.0)
        assertEquals(27.0, shifted[0].state.selection.rect.y, 0.0)
        assertEquals(11.0, shifted[1].state.selection.rect.x, 0.0)
        assertEquals(21.0, shifted[1].state.selection.rect.y, 0.0)
    }

    @Test
    fun dismissPopupAtClosesTheSelectedPopupAndItsChildren() {
        val popups = listOf("root", "child", "grandchild").map { id ->
            LookupPopupItem(
                id = id,
                state = LookupPopupState(
                    selection = ReaderSelectionData(
                        text = id,
                        sentence = id,
                        rect = ReaderSelectionRect(x = 0.0, y = 0.0, width = 1.0, height = 1.0),
                        normalizedOffset = null,
                    ),
                    results = emptyList(),
                ),
            )
        }

        assertEquals(listOf("root"), dismissPopupAt(popups, 1).map { it.id })
        assertEquals(emptyList<String>(), dismissPopupAt(popups, 0).map { it.id })
    }

    @Test
    fun dismissingChildPopupSignalsParentSelectionClearLikeIos() {
        val popups = listOf("root", "child", "grandchild").map { id ->
            LookupPopupItem(
                id = id,
                state = LookupPopupState(
                    selection = ReaderSelectionData(
                        text = id,
                        sentence = id,
                        rect = ReaderSelectionRect(x = 0.0, y = 0.0, width = 1.0, height = 1.0),
                        normalizedOffset = null,
                    ),
                    results = emptyList(),
                ),
            )
        }

        val afterDismissingChild = dismissPopupAt(popups, 1)
        val afterDismissingGrandchild = dismissPopupAt(popups, 2)

        assertEquals(1, afterDismissingChild.single { it.id == "root" }.clearSelectionSignal)
        assertEquals(1, afterDismissingGrandchild.single { it.id == "child" }.clearSelectionSignal)
    }

    @Test
    fun scrollingRootOnlyPopupDoesNotRewritePopupState() {
        val popups = listOf("root").map { id ->
            LookupPopupItem(
                id = id,
                state = LookupPopupState(
                    selection = ReaderSelectionData(
                        text = id,
                        sentence = id,
                        rect = ReaderSelectionRect(x = 0.0, y = 0.0, width = 1.0, height = 1.0),
                        normalizedOffset = null,
                    ),
                    results = emptyList(),
                ),
            )
        }

        assertTrue(closeChildPopupsForScrolledParent(popups, 0) === popups)
    }

    @Test
    fun scrollingParentPopupClosesChildrenAndClearsSelection() {
        val popups = listOf("root", "child").map { id ->
            LookupPopupItem(
                id = id,
                state = LookupPopupState(
                    selection = ReaderSelectionData(
                        text = id,
                        sentence = id,
                        rect = ReaderSelectionRect(x = 0.0, y = 0.0, width = 1.0, height = 1.0),
                        normalizedOffset = null,
                    ),
                    results = emptyList(),
                ),
            )
        }

        val scrolled = closeChildPopupsForScrolledParent(popups, 0)

        assertEquals(listOf("root"), scrolled.map { it.id })
        assertEquals(1, scrolled.single().clearSelectionSignal)
    }

    @Test
    fun existingPopupsRetainSelectionAndHistorySignalsWhenThemeChanges() {
        val popups = listOf(
            LookupPopupItem(
                id = "root",
                clearSelectionSignal = 3,
                state = LookupPopupState(
                    selection = ReaderSelectionData(
                        text = "猫",
                        sentence = "猫です",
                        rect = ReaderSelectionRect(x = 0.0, y = 0.0, width = 1.0, height = 1.0),
                        normalizedOffset = 4,
                    ),
                    results = emptyList(),
                    darkMode = false,
                    eInkMode = false,
                    audioSettings = AudioSettings(enableAutoplay = false),
                ),
            ),
        )

        val themed = popups.withLookupPopupVisualOptions(
            darkMode = true,
            eInkMode = true,
            audioSettings = AudioSettings(enableAutoplay = true),
        )

        assertEquals("root", themed.single().id)
        assertEquals(3, themed.single().clearSelectionSignal)
        assertEquals("猫", themed.single().state.selection.text)
        assertTrue(themed.single().state.darkMode)
        assertTrue(themed.single().state.eInkMode)
        assertTrue(themed.single().state.audioSettings.enableAutoplay)
    }

    @Test
    fun popupSelectionOffsetTracksHistoryControls() {
        assertEquals(
            50.0,
            popupSelectionOffsetY(
                frameTopDp = 50.0,
                popupActionBar = false,
                backCount = 0,
                forwardCount = 0,
                hasSasayakiCue = false,
            ),
            0.0,
        )
        assertEquals(
            87.0,
            popupSelectionOffsetY(
                frameTopDp = 50.0,
                popupActionBar = false,
                backCount = 1,
                forwardCount = 0,
                hasSasayakiCue = false,
            ),
            0.0,
        )
    }

    @Test
    fun popupTouchStreamContinuesAfterMovingOutsideInitialHost() {
        val tracker = PopupTouchStreamTracker()

        assertTrue(tracker.shouldDispatch(MotionEvent.ACTION_DOWN, hitPopup = true))
        tracker.onDispatchResult(MotionEvent.ACTION_DOWN, handled = true)
        assertTrue(tracker.shouldDispatch(MotionEvent.ACTION_MOVE, hitPopup = false))
        assertTrue(tracker.shouldDispatch(MotionEvent.ACTION_UP, hitPopup = false))
        tracker.onDispatchResult(MotionEvent.ACTION_UP, handled = true)
        assertFalse(tracker.shouldDispatch(MotionEvent.ACTION_MOVE, hitPopup = false))
    }

}
