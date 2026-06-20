package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReaderSearchSheetStateTest {
    @Test
    fun editingQueryDoesNotTriggerSearch() {
        val state = ReaderSearchSheetState()

        state.updateQuery("先生")

        assertEquals("先生", state.query)
        assertEquals("", state.submittedQuery)
        assertEquals(0, state.searchNonce)
    }

    @Test
    fun submittingQueryRecordsSubmittedTextAndTriggersOneSearch() {
        val state = ReaderSearchSheetState()
        state.updateQuery("先生")

        state.submitQuery()

        assertEquals("先生", state.query)
        assertEquals("先生", state.submittedQuery)
        assertEquals(1, state.searchNonce)
    }

    @Test
    fun editingAfterSubmitKeepsLastResultsUntilNextSubmit() {
        val state = ReaderSearchSheetState()
        state.updateQuery("先生")
        state.submitQuery()

        state.updateQuery("頭")

        assertEquals("頭", state.query)
        assertEquals("先生", state.submittedQuery)
        assertEquals(1, state.searchNonce)
    }

    @Test
    fun clearingQueryClearsSubmittedSearchAndResultsWithoutTriggeringSearch() {
        val state = ReaderSearchSheetState()
        state.updateQuery("先生")
        state.submitQuery()
        state.results = listOf(
            ReaderSearchResult(
                chapterIndex = 0,
                chapterLabel = "Chapter",
                character = 10,
                snippet = "先生",
                snippetMatchStart = 0,
                snippetMatchEnd = 2,
            ),
        )
        state.searching = true
        state.failed = true

        state.updateQuery("")

        assertEquals("", state.query)
        assertEquals("", state.submittedQuery)
        assertEquals(emptyList<ReaderSearchResult>(), state.results)
        assertFalse(state.searching)
        assertFalse(state.failed)
        assertEquals(1, state.searchNonce)
    }
}
