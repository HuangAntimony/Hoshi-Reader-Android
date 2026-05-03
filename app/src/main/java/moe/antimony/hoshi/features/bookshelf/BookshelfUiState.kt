package moe.antimony.hoshi.features.bookshelf

import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookSortOption

data class BookshelfUiState(
    val bookEntries: List<BookEntry> = emptyList(),
    val bookProgressById: Map<String, Double> = emptyMap(),
    val sortOption: BookSortOption = BookSortOption.Recent,
    val sasayakiEnabled: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val openReaderBookId: String? = null,
)

data class BookshelfLoadResult(
    val entries: List<BookEntry>,
    val progressById: Map<String, Double>,
)
