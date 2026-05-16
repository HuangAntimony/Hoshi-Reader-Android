package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.epub.HighlightColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderHighlightSelectionMenuTest {
    @Test
    fun exposesSingleHighlightParentWithPaletteChildren() {
        assertEquals("Highlight", ReaderHighlightSelectionMenu.parentTitle)
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
