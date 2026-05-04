package moe.antimony.hoshi.features.bookshelf

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookSortOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookshelfViewModelTest {
    @Test
    fun reloadBooksPublishesEntriesProgressAndSasayakiState() {
        val entry = bookEntry("book-a")
        val repository = FakeBookshelfRepository(
            entries = listOf(entry),
            progressById = mapOf("book-a" to 0.25),
        )
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.reloadBookEntries()
        viewModel.setSasayakiEnabled(true)

        assertEquals(listOf(entry), viewModel.uiState.value.bookEntries)
        assertEquals(mapOf("book-a" to 0.25), viewModel.uiState.value.bookProgressById)
        assertTrue(viewModel.uiState.value.sasayakiEnabled)
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun changingSortOptionReloadsBooksWithThatOption() {
        val repository = FakeBookshelfRepository()
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.changeSort(BookSortOption.Title)

        assertEquals(BookSortOption.Title, viewModel.uiState.value.sortOption)
        assertEquals(listOf(BookSortOption.Title), repository.loadRequests)
    }

    @Test
    fun openingBookRefreshesShelfAndEmitsOpenReaderEvent() {
        val entry = bookEntry("book-a")
        val repository = FakeBookshelfRepository(entries = listOf(entry), openBookId = "book-a")
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.openBook(entry)

        assertEquals("book-a", viewModel.uiState.value.openReaderBookId)
        assertEquals(listOf(entry), viewModel.uiState.value.bookEntries)
        assertFalse(viewModel.uiState.value.isLoading)

        viewModel.consumeOpenReaderEvent()

        assertNull(viewModel.uiState.value.openReaderBookId)
    }

    @Test
    fun importingBookPublishesOpenReaderEventOnSuccess() {
        val repository = FakeBookshelfRepository(importBookId = "imported-book")
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.importBook("content://books/import.epub") {
            repository.importBookId
        }

        assertEquals("imported-book", viewModel.uiState.value.openReaderBookId)
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun importingBookReportsErrorAndAllowsRetryOnFailure() {
        val repository = FakeBookshelfRepository()
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.importBook("content://books/import.epub") {
            error("bad epub")
        }

        assertEquals("bad epub", viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isLoading)

        viewModel.importBook("content://books/import.epub") {
            "retry-book"
        }

        assertEquals("retry-book", viewModel.uiState.value.openReaderBookId)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun deleteBookReloadsBooksAfterRepositoryDeletion() {
        val entry = bookEntry("book-a")
        val repository = FakeBookshelfRepository(entries = emptyList())
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.deleteBook(entry)

        assertEquals(listOf(entry), repository.deletedEntries)
        assertEquals(emptyList<BookEntry>(), viewModel.uiState.value.bookEntries)
    }

    @Test
    fun sasayakiEnabledCanBeDrivenByObservedSettingsState() {
        val viewModel = BookshelfViewModel(FakeBookshelfRepository(), testScope())

        viewModel.setSasayakiEnabled(true)

        assertTrue(viewModel.uiState.value.sasayakiEnabled)
    }

    private fun testScope(): CoroutineScope = CoroutineScope(Dispatchers.Unconfined)

    private fun bookEntry(id: String): BookEntry =
        BookEntry(
            root = File(id),
            metadata = BookMetadata(
                id = id,
                title = id,
                cover = null,
                folder = id,
                lastAccess = 0.0,
            ),
        )

    private class FakeBookshelfRepository(
        var entries: List<BookEntry> = emptyList(),
        var progressById: Map<String, Double> = emptyMap(),
        var openBookId: String = "book-a",
        var importBookId: String = "imported-book",
    ) : BookshelfRepository {
        val loadRequests = mutableListOf<BookSortOption>()
        val deletedEntries = mutableListOf<BookEntry>()

        override suspend fun loadBooks(sortOption: BookSortOption): BookshelfLoadResult {
            loadRequests += sortOption
            return BookshelfLoadResult(entries, progressById)
        }

        override suspend fun openBook(entry: BookEntry): String = openBookId

        override suspend fun importBook(uri: android.net.Uri): String = importBookId

        override suspend fun deleteBook(entry: BookEntry) {
            deletedEntries += entry
        }

        override suspend fun rebuildLookupQuery() = Unit
    }
}
