package moe.antimony.hoshi.features.bookshelf

import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookShelf
import moe.antimony.hoshi.epub.BookSortOption

data class BookshelfUiState(
    val bookEntries: List<BookEntry> = emptyList(),
    val bookProgressById: Map<String, Double> = emptyMap(),
    val shelves: List<BookShelf> = emptyList(),
    val sections: List<BookshelfSectionModel> = emptyList(),
    val sortOption: BookSortOption = BookSortOption.Recent,
    val showReading: Boolean = false,
    val isSelecting: Boolean = false,
    val selectedBookIds: Set<String> = emptySet(),
    val shelfExpansionState: Map<String, Boolean> = emptyMap(),
    val sasayakiEnabled: Boolean = false,
    val hasLoadedBooks: Boolean = false,
    val isLoading: Boolean = false,
    val blockingProgressMessage: String? = null,
    val errorMessage: String? = null,
    val openReaderBookId: String? = null,
)

data class BookshelfLoadResult(
    val entries: List<BookEntry>,
    val progressById: Map<String, Double>,
    val shelves: List<BookShelf>,
    val settings: BookshelfSettings,
)
