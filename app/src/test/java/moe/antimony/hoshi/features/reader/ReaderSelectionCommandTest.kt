package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.epub.HighlightColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSelectionCommandTest {
    @Test
    fun selectTextCommandBuildsIosSelectionInvocation() {
        val command = ReaderSelectionCommand.SelectText(
            x = 12.5f,
            y = 24.25f,
            maxLength = 16,
        )

        assertEquals("window.hoshiSelection.selectText(12.5, 24.25, 16)", command.source)
    }

    @Test
    fun highlightCommandBuildsIosSelectionHighlightInvocation() {
        val command = ReaderSelectionCommand.HighlightSelection(count = 4)

        assertEquals("window.hoshiSelection.highlightSelection(4)", command.source)
    }

    @Test
    fun clearCommandBuildsIosSelectionClearInvocation() {
        assertEquals(
            "window.hoshiSelection.clearSelection()",
            ReaderSelectionCommand.ClearSelection.source,
        )
    }

    @Test
    fun readerHighlightCommandsBuildIosHighlightInvocations() {
        assertEquals(
            "window.hoshiHighlights.prepareHighlightSelection()",
            ReaderHighlightCommand.PrepareSelection.source,
        )
        assertEquals(
            """window.hoshiHighlights.createHighlight('yellow', 'highlight-1')""",
            ReaderHighlightCommand.Create(HighlightColor.Yellow, "highlight-1").source,
        )
        assertEquals(
            """window.hoshiHighlights.removeHighlight("highlight-1")""",
            ReaderHighlightCommand.Remove("highlight-1").source,
        )
    }

    @Test
    fun selectTextResultTreatsNullAndUndefinedAsNoSelection() {
        assertTrue(ReaderSelectionResult.fromWebViewResult(null).selectedNothing)
        assertTrue(ReaderSelectionResult.fromWebViewResult("null").selectedNothing)
        assertTrue(ReaderSelectionResult.fromWebViewResult("undefined").selectedNothing)
        assertFalse(ReaderSelectionResult.fromWebViewResult("\"猫\"").selectedNothing)
    }
}
