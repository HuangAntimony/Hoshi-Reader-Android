package moe.antimony.hoshi.features.bookshelf

import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookShelf
import moe.antimony.hoshi.epub.BookSortOption
import moe.antimony.hoshi.ui.UiText

data class BookshelfUiState(
    val bookEntries: List<BookEntry> = emptyList(),
    val remoteBookEntries: List<RemoteBookEntry> = emptyList(),
    val bookProgressById: Map<String, Double> = emptyMap(),
    val remoteProgressById: Map<String, Double> = emptyMap(),
    val remoteImportProgressById: Map<String, Double> = emptyMap(),
    val remoteBusyBookIds: Set<String> = emptySet(),
    val coverSourcesById: Map<String, BookCoverSource> = emptyMap(),
    val remoteCoverSourcesById: Map<String, BookCoverSource> = emptyMap(),
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
    val blockingProgressMessage: UiText? = null,
    val statusMessage: UiText? = null,
    val errorMessage: UiText? = null,
    val openReaderBookId: String? = null,
)

data class RemoteBookEntry(
    val id: String,
    val folderId: String,
    val folderName: String,
    val title: String,
    val syncFiles: moe.antimony.hoshi.features.sync.DriveSyncFiles,
)

data class BookCoverSource(
    val path: String,
    val cacheKey: String,
)

data class BookshelfLoadResult(
    val entries: List<BookEntry>,
    val progressById: Map<String, Double>,
    val coverSourcesById: Map<String, BookCoverSource>,
    val shelves: List<BookShelf>,
    val settings: BookshelfSettings,
)

data class RemoteBookshelfLoadResult(
    val remoteEntries: List<RemoteBookEntry> = emptyList(),
    val remoteProgressById: Map<String, Double> = emptyMap(),
    val remoteCoverSourcesById: Map<String, BookCoverSource> = emptyMap(),
)
