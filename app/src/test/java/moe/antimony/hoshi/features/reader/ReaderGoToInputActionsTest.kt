package moe.antimony.hoshi.features.reader

import androidx.compose.ui.semantics.Role
import moe.antimony.hoshi.epub.EpubBook
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
    fun goToTabsPutSearchLast() {
        assertEquals(
            listOf(ReaderGoToTab.Chapters, ReaderGoToTab.Highlights, ReaderGoToTab.Gallery, ReaderGoToTab.Search),
            ReaderGoToTab.entries.toList(),
        )
    }

    @Test
    fun galleryResourceUrlEncodesUnicodeAndSpacesOnTheSafeEpubOrigin() {
        val book = EpubBook(title = "Book", chapters = emptyList())

        assertEquals(
            "https://appassets.androidplatform.net/epub/OPS/images/%E8%A1%A8%20%E7%B4%99.jpg",
            book.galleryResourceUrl("OPS/images/表 紙.jpg"),
        )
        assertEquals(null, book.galleryResourceUrl("../outside.png"))
    }

    @Test
    fun goToSheetDefaultsToChapters() {
        assertEquals(ReaderGoToTab.Chapters, readerGoToDefaultTab())
    }

    @Test
    fun bookHeaderCoverUsesSquareArtworkFrame() {
        val metrics = readerSheetDensityMetrics()

        assertEquals(metrics.chapterHeaderCoverWidthDp, metrics.chapterHeaderCoverHeightDp)
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
    fun searchTabActivationFocusesInputAndShowsKeyboard() {
        val events = mutableListOf<String>()

        readerSearchTabActivationAction(
            requestFocus = { events += "requestFocus" },
            showKeyboard = { events += "showKeyboard" },
        )

        assertEquals(listOf("requestFocus", "showKeyboard"), events)
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
