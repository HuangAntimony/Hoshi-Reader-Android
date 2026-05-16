package moe.antimony.hoshi.features.reader

import android.view.Menu
import android.view.MenuItem
import moe.antimony.hoshi.epub.HighlightColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderHighlightSelectionMenuTest {
    @Test
    fun exposesOnlySingleHighlightItemToAndroidSelectionToolbar() {
        assertEquals(
            listOf(ReaderHighlightSelectionMenu.parentItemId),
            ReaderHighlightSelectionMenu.actionModeItems.map { it.id },
        )
        assertEquals("Highlight", ReaderHighlightSelectionMenu.parentTitle)
    }

    @Test
    fun requestsHighlightAsLeadingVisibleToolbarAction() {
        val item = ReaderHighlightSelectionMenu.actionModeItems.single()

        assertEquals(Menu.NONE, item.order)
        assertEquals(MenuItem.SHOW_AS_ACTION_ALWAYS, item.showAsAction)
    }

    @Test
    fun positionsColorPickerNearSelectedTextWhenAnchorIsAvailable() {
        val position = ReaderHighlightSelectionMenu.colorPickerPopupPosition(
            viewLeft = 0,
            viewTop = 288,
            viewWidth = 1280,
            screenWidth = 1280,
            screenHeight = 2856,
            popupWidth = 720,
            popupHeight = 160,
            margin = 24,
            anchor = ReaderHighlightSelectionAnchor(left = 520, top = 600, right = 760, bottom = 1000),
        )

        assertEquals(280, position.x)
        assertEquals(704, position.y)
    }

    @Test
    fun exposesPaletteColorsOnlyAfterHighlightIsClicked() {
        assertEquals(5, ReaderHighlightSelectionMenu.colorItems.size)
        assertEquals(
            listOf("Yellow", "Green", "Blue", "Pink", "Purple"),
            ReaderHighlightSelectionMenu.colorItems.map { it.title },
        )
    }

    @Test
    fun mapsOnlyPaletteChildItemsToHighlightColors() {
        val colorItems = ReaderHighlightSelectionMenu.colorItems

        assertEquals(HighlightColor.Yellow, ReaderHighlightSelectionMenu.colorForItemId(colorItems[0].id))
        assertEquals(HighlightColor.Green, ReaderHighlightSelectionMenu.colorForItemId(colorItems[1].id))
        assertEquals(HighlightColor.Blue, ReaderHighlightSelectionMenu.colorForItemId(colorItems[2].id))
        assertEquals(HighlightColor.Pink, ReaderHighlightSelectionMenu.colorForItemId(colorItems[3].id))
        assertEquals(HighlightColor.Purple, ReaderHighlightSelectionMenu.colorForItemId(colorItems[4].id))
        assertNull(ReaderHighlightSelectionMenu.colorForItemId(ReaderHighlightSelectionMenu.parentItemId))
        assertNull(ReaderHighlightSelectionMenu.colorForItemId(0))
    }
}
