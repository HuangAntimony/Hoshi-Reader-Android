package moe.antimony.hoshi.features.bookshelf

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookShelf
import moe.antimony.hoshi.epub.BookSortOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookshelfViewModelTest {
    @Test
    fun initialStateWaitsForFirstShelfLoadBeforeShowingEmptyBooks() {
        val viewModel = BookshelfViewModel(FakeBookshelfRepository(), testScope())

        assertFalse(viewModel.uiState.value.hasLoadedBooks)
    }

    @Test
    fun reloadBooksPublishesEntriesProgressAndSasayakiState() {
        val entry = bookEntry("book-a")
        val repository = FakeBookshelfRepository(
            entries = listOf(entry),
            progressById = mapOf("book-a" to 0.25),
            shelves = listOf(BookShelf("Manga", listOf("book-a"))),
            settings = BookshelfSettings(sortOption = BookSortOption.Title, showReading = true),
        )
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.reloadBookEntries()
        viewModel.setSasayakiEnabled(true)

        assertEquals(listOf(entry), viewModel.uiState.value.bookEntries)
        assertEquals(mapOf("book-a" to 0.25), viewModel.uiState.value.bookProgressById)
        assertEquals(listOf(BookShelf("Manga", listOf("book-a"))), viewModel.uiState.value.shelves)
        assertEquals(BookSortOption.Title, viewModel.uiState.value.sortOption)
        assertTrue(viewModel.uiState.value.showReading)
        assertTrue(viewModel.uiState.value.hasLoadedBooks)
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
    fun changingReadingShelfVisibilityPersistsAndRebuildsSections() {
        val repository = FakeBookshelfRepository(
            entries = listOf(bookEntry("book-a")),
            progressById = mapOf("book-a" to 0.5),
        )
        val viewModel = BookshelfViewModel(repository, testScope())
        viewModel.reloadBookEntries()

        viewModel.changeShowReading(true)

        assertEquals(listOf(true), repository.showReadingUpdates)
        assertTrue(viewModel.uiState.value.showReading)
        assertEquals(listOf("Reading", "Unshelved"), viewModel.uiState.value.sections.map { it.title })
    }

    @Test
    fun openingBookEmitsOpenReaderEventWithoutRefreshingShelf() {
        val entry = bookEntry("book-a")
        val repository = FakeBookshelfRepository(entries = listOf(entry), openBookId = "book-a")
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.openBook(entry)

        assertEquals("book-a", viewModel.uiState.value.openReaderBookId)
        assertEquals(emptyList<BookEntry>(), viewModel.uiState.value.bookEntries)
        assertEquals(emptyList<BookSortOption>(), repository.loadRequests)
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
    fun selectionModeTogglesBooksAndBatchMovesSelectionToShelf() {
        val first = bookEntry("book-a")
        val second = bookEntry("book-b")
        val repository = FakeBookshelfRepository(entries = listOf(first, second))
        val viewModel = BookshelfViewModel(repository, testScope())
        viewModel.reloadBookEntries()

        viewModel.startSelecting()
        viewModel.toggleSelectedBook(first)
        viewModel.toggleSelectedBook(second)
        viewModel.moveSelectedBooks("Manga")

        assertEquals(listOf(setOf("book-a", "book-b") to "Manga"), repository.movedBooks)
        assertFalse(viewModel.uiState.value.isSelecting)
        assertEquals(emptySet<String>(), viewModel.uiState.value.selectedBookIds)
    }

    @Test
    fun createDeleteAndMoveShelfDelegateToRepositoryAndReload() {
        val repository = FakeBookshelfRepository()
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.createShelf("Manga")
        viewModel.deleteShelf("Manga")
        viewModel.moveShelf(fromIndex = 1, toIndex = 0)

        assertEquals(listOf("Manga"), repository.createdShelves)
        assertEquals(listOf("Manga"), repository.deletedShelves)
        assertEquals(listOf(1 to 0), repository.movedShelves)
        assertEquals(listOf(BookSortOption.Recent, BookSortOption.Recent, BookSortOption.Recent), repository.loadRequests)
    }

    @Test
    fun markReadWritesCompletedBookmarkThroughRepository() {
        val entry = bookEntry("book-a")
        val repository = FakeBookshelfRepository(entries = listOf(entry))
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.markRead(entry)

        assertEquals(listOf(entry), repository.markedReadEntries)
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
        var shelves: List<BookShelf> = emptyList(),
        var settings: BookshelfSettings = BookshelfSettings(),
    ) : BookshelfRepository {
        val loadRequests = mutableListOf<BookSortOption>()
        val deletedEntries = mutableListOf<BookEntry>()
        val movedBooks = mutableListOf<Pair<Set<String>, String?>>()
        val createdShelves = mutableListOf<String>()
        val deletedShelves = mutableListOf<String>()
        val movedShelves = mutableListOf<Pair<Int, Int>>()
        val markedReadEntries = mutableListOf<BookEntry>()
        val showReadingUpdates = mutableListOf<Boolean>()

        override suspend fun loadBooks(sortOption: BookSortOption): BookshelfLoadResult {
            loadRequests += sortOption
            return BookshelfLoadResult(entries, progressById, shelves, settings)
        }

        override suspend fun openBook(entry: BookEntry): String = openBookId

        override suspend fun importBook(uri: android.net.Uri): String = importBookId

        override suspend fun deleteBook(entry: BookEntry) {
            deletedEntries += entry
        }

        override suspend fun deleteBooks(entries: Collection<BookEntry>) {
            deletedEntries += entries
        }

        override suspend fun moveBooks(bookIds: Set<String>, shelfName: String?) {
            movedBooks += bookIds to shelfName
        }

        override suspend fun createShelf(name: String) {
            createdShelves += name
        }

        override suspend fun deleteShelf(name: String) {
            deletedShelves += name
        }

        override suspend fun moveShelf(fromIndex: Int, toIndex: Int) {
            movedShelves += fromIndex to toIndex
        }

        override suspend fun markRead(entry: BookEntry) {
            markedReadEntries += entry
        }

        override suspend fun changeSort(sortOption: BookSortOption) {
            settings = settings.copy(sortOption = sortOption)
        }

        override suspend fun changeShowReading(showReading: Boolean) {
            settings = settings.copy(showReading = showReading)
            showReadingUpdates += showReading
        }

        override suspend fun rebuildLookupQuery() = Unit
    }
}
