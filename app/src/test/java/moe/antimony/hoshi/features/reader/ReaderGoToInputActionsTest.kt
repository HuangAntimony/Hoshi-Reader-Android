package moe.antimony.hoshi.features.reader

import androidx.compose.ui.semantics.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderGoToInputActionsTest {
    @Test
    fun tabsUseTabSelectionSemantics() {
        assertEquals(Role.Tab, ReaderGoToTabRole)
    }

    @Test
    fun searchImeActionSubmitsAndDismissesKeyboard() {
        val events = mutableListOf<String>()

        readerSearchImeAction(
            onSearch = { events += "search" },
            clearFocus = { events += "clearFocus" },
            hideKeyboard = { events += "hideKeyboard" },
        )

        assertEquals(listOf("search", "clearFocus", "hideKeyboard"), events)
    }

    @Test
    fun jumpImeActionConfirmsParsedNumberAndDismissesKeyboard() {
        val events = mutableListOf<String>()

        val handled = readerJumpImeAction(
            input = "15398",
            totalCharacters = 128_892,
            progressDisplay = ReaderProgressDisplay.characters(),
            onConfirm = { events += "confirm:$it" },
            hideKeyboard = { events += "hideKeyboard" },
        )

        assertTrue(handled)
        assertEquals(listOf("confirm:15398", "hideKeyboard"), events)
    }

    @Test
    fun jumpImeActionIgnoresBlankInput() {
        val events = mutableListOf<String>()

        val handled = readerJumpImeAction(
            input = "",
            totalCharacters = 128_892,
            progressDisplay = ReaderProgressDisplay.characters(),
            onConfirm = { events += "confirm:$it" },
            hideKeyboard = { events += "hideKeyboard" },
        )

        assertFalse(handled)
        assertEquals(emptyList<String>(), events)
    }
}
