package moe.antimony.hoshi.features.bookshelf

import android.net.Uri
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookSortOption
import moe.antimony.hoshi.features.sync.StatisticsSyncMode
import moe.antimony.hoshi.features.sync.SyncDirection
import moe.antimony.hoshi.features.sync.SyncResult

internal data class BookImportItem(
    val uri: Uri,
    val displayName: String? = null,
)

internal data class PendingBookImport(
    val importKey: String,
    val displayName: String? = null,
    val importOperation: suspend () -> String,
)

internal class BookshelfViewModel(
    private val repository: BookshelfRepository,
    coroutineScope: CoroutineScope? = null,
    private val importGate: PendingImportGate<String> = PendingImportGate(),
) : ViewModel() {
    private val ownedScope = if (coroutineScope == null) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    } else {
        null
    }
    private val workScope = coroutineScope ?: ownedScope!!
    private val _uiState = MutableStateFlow(BookshelfUiState())
    val uiState: StateFlow<BookshelfUiState> = _uiState
    private var openBookInFlight = false

    fun reloadBookEntries() {
        reloadBookEntries(_uiState.value.sortOption)
    }

    fun changeSort(sortOption: BookSortOption) {
        workScope.launch {
            repository.changeSort(sortOption)
            _uiState.update { it.copy(sortOption = sortOption) }
            reloadBookEntriesSync(sortOption)
        }
    }

    fun openBook(entry: BookEntry) {
        if (openBookInFlight) {
            return
        }
        openBookInFlight = true
        workScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            try {
                val bookId = repository.openBook(entry)
                _uiState.update { it.copy(openReaderBookId = bookId) }
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        errorMessage = error.localizedMessage ?: "Failed to open EPUB.",
                    )
                }
            } finally {
                openBookInFlight = false
            }
        }
    }

    fun importBook(uri: Uri, displayName: String? = null) {
        importBook(uri.toString(), displayName) {
            repository.importBook(uri)
        }
    }

    fun importBooks(imports: List<BookImportItem>) {
        val pendingImports = imports.map { import ->
            PendingBookImport(
                importKey = import.uri.toString(),
                displayName = import.displayName,
            ) {
                repository.importBook(import.uri)
            }
        }
        importBooks(pendingImports)
    }

    internal fun importBook(
        importKey: String,
        displayName: String? = null,
        importOperation: suspend () -> String,
    ) {
        if (!importGate.tryStart(importKey)) {
            return
        }
        runLoading(
            errorPrefix = "Failed to import EPUB.",
            onComplete = { importGate.finish(importKey) },
            blockingProgressMessage = "Importing ${displayName?.takeIf { it.isNotBlank() } ?: "EPUB"}...",
            block = {
                importOperation()
                reloadBookEntriesSync()
            },
        )
    }

    internal fun importBooks(imports: List<PendingBookImport>) {
        if (imports.isEmpty()) {
            return
        }
        if (imports.size == 1) {
            val import = imports.single()
            importBook(import.importKey, import.displayName, import.importOperation)
            return
        }

        val importKey = imports.joinToString(separator = "\n") { it.importKey }
        if (!importGate.tryStart(importKey)) {
            return
        }

        workScope.launch {
            val failed = mutableListOf<String>()
            _uiState.update {
                it.copy(
                    isLoading = true,
                    blockingProgressMessage = "Importing 1 / ${imports.size}...",
                    errorMessage = null,
                )
            }
            try {
                imports.forEachIndexed { index, import ->
                    _uiState.update {
                        it.copy(blockingProgressMessage = "Importing ${index + 1} / ${imports.size}...")
                    }
                    try {
                        import.importOperation()
                    } catch (_: Throwable) {
                        failed += import.failureDisplayName()
                    }
                }
                reloadBookEntriesSync()
                if (failed.isNotEmpty()) {
                    _uiState.update {
                        it.copy(errorMessage = "Failed to import:\n${failed.joinToString(separator = "\n")}")
                    }
                }
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        errorMessage = error.localizedMessage ?: "Failed to import EPUB.",
                    )
                }
            } finally {
                _uiState.update { it.copy(isLoading = false, blockingProgressMessage = null) }
                importGate.finish(importKey)
            }
        }
    }

    fun deleteBook(entry: BookEntry) {
        workScope.launch {
            repository.deleteBook(entry)
            reloadBookEntriesSync()
        }
    }

    fun deleteSelectedBooks() {
        val selectedEntries = _uiState.value.bookEntries.filter { it.metadata.id in _uiState.value.selectedBookIds }
        if (selectedEntries.isEmpty()) return
        workScope.launch {
            repository.deleteBooks(selectedEntries)
            clearSelection()
            reloadBookEntriesSync()
        }
    }

    fun moveBook(entry: BookEntry, shelfName: String?) {
        workScope.launch {
            repository.moveBooks(setOf(entry.metadata.id), shelfName)
            reloadBookEntriesSync()
        }
    }

    fun moveSelectedBooks(shelfName: String?) {
        val selectedIds = _uiState.value.selectedBookIds
        if (selectedIds.isEmpty()) return
        workScope.launch {
            repository.moveBooks(selectedIds, shelfName)
            clearSelection()
            reloadBookEntriesSync()
        }
    }

    fun createShelf(name: String) {
        workScope.launch {
            repository.createShelf(name)
            reloadBookEntriesSync()
        }
    }

    fun deleteShelf(name: String) {
        workScope.launch {
            repository.deleteShelf(name)
            reloadBookEntriesSync()
        }
    }

    fun moveShelf(fromIndex: Int, toIndex: Int) {
        workScope.launch {
            repository.moveShelf(fromIndex, toIndex)
            reloadBookEntriesSync()
        }
    }

    fun markRead(entry: BookEntry) {
        workScope.launch {
            repository.markRead(entry)
            reloadBookEntriesSync()
        }
    }

    fun changeShowReading(showReading: Boolean) {
        workScope.launch {
            repository.changeShowReading(showReading)
            _uiState.update {
                it.copy(
                    showReading = showReading,
                    sections = bookshelfSections(
                        entries = it.bookEntries,
                        shelves = it.shelves,
                        progressById = it.bookProgressById,
                        showReading = showReading,
                        sortOption = it.sortOption,
                    ),
                )
            }
        }
    }

    fun startSelecting() {
        _uiState.update { it.copy(isSelecting = true, selectedBookIds = emptySet()) }
    }

    fun clearSelection() {
        _uiState.update { it.copy(isSelecting = false, selectedBookIds = emptySet()) }
    }

    fun toggleSelectedBook(entry: BookEntry) {
        _uiState.update { state ->
            val id = entry.metadata.id
            val selected = if (id in state.selectedBookIds) {
                state.selectedBookIds - id
            } else {
                state.selectedBookIds + id
            }
            state.copy(selectedBookIds = selected)
        }
    }

    fun setShelfExpanded(collapseKey: String, isExpanded: Boolean) {
        _uiState.update { state ->
            state.copy(shelfExpansionState = state.shelfExpansionState + (collapseKey to isExpanded))
        }
    }

    fun setSasayakiEnabled(enabled: Boolean) {
        _uiState.update { it.copy(sasayakiEnabled = enabled) }
    }

    fun rebuildLookupQuery() {
        workScope.launch {
            runCatching { repository.rebuildLookupQuery() }
        }
    }

    fun syncBook(
        entry: BookEntry,
        direction: SyncDirection?,
        syncStats: Boolean,
        statsSyncMode: StatisticsSyncMode,
        syncAudioBook: Boolean,
    ) {
        runLoading(
            errorPrefix = "Sync failed.",
            blockingProgressMessage = "Syncing...",
            block = {
                val result = repository.syncBook(
                    entry = entry,
                    direction = direction,
                    syncStats = syncStats,
                    statsSyncMode = statsSyncMode,
                    syncAudioBook = syncAudioBook,
                )
                reloadBookEntriesSync()
                _uiState.update { it.copy(statusMessage = result.bookshelfMessage()) }
            },
        )
    }

    fun consumeOpenReaderEvent() {
        _uiState.update { it.copy(openReaderBookId = null) }
    }

    private fun reloadBookEntries(sortOption: BookSortOption) {
        workScope.launch {
            reloadBookEntriesSync(sortOption)
        }
    }

    private suspend fun reloadBookEntriesSync(sortOption: BookSortOption = _uiState.value.sortOption) {
        val firstResult = loadBookEntries(sortOption)
        val result = if (firstResult.settings.sortOption != sortOption) {
            loadBookEntries(firstResult.settings.sortOption)
        } else {
            firstResult
        }
        _uiState.update {
            val validSelectedIds = it.selectedBookIds.intersect(result.entries.mapTo(mutableSetOf()) { entry -> entry.metadata.id })
            it.copy(
                bookEntries = result.entries,
                bookProgressById = result.progressById,
                shelves = result.shelves,
                sections = bookshelfSections(
                    entries = result.entries,
                    shelves = result.shelves,
                    progressById = result.progressById,
                    showReading = result.settings.showReading,
                    sortOption = result.settings.sortOption,
                ),
                sortOption = result.settings.sortOption,
                showReading = result.settings.showReading,
                selectedBookIds = validSelectedIds,
                hasLoadedBooks = true,
                errorMessage = null,
            )
        }
    }

    private suspend fun loadBookEntries(sortOption: BookSortOption): BookshelfLoadResult =
        repository.loadBooks(sortOption)

    private fun runLoading(
        errorPrefix: String,
        onComplete: () -> Unit = {},
        blockingProgressMessage: String? = null,
        block: suspend () -> Unit,
    ) {
        workScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    blockingProgressMessage = blockingProgressMessage,
                    statusMessage = null,
                    errorMessage = null,
                )
            }
            try {
                block()
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        errorMessage = error.localizedMessage ?: errorPrefix,
                    )
                }
            } finally {
                _uiState.update { it.copy(isLoading = false, blockingProgressMessage = null) }
                onComplete()
            }
        }
    }

    override fun onCleared() {
        ownedScope?.cancel()
    }
}

private fun PendingBookImport.failureDisplayName(): String =
    displayName?.takeIf { it.isNotBlank() }
        ?: importKey.substringAfterLast('/').takeIf { it.isNotBlank() }
        ?: "EPUB"

private fun SyncResult.bookshelfMessage(): String? =
    when (this) {
        is SyncResult.Exported -> "Synced $title to ッツ\n$characterCount characters"
        is SyncResult.Imported -> "Synced $title from ッツ\n$characterCount characters"
        is SyncResult.Synced -> "$title is already synced"
        SyncResult.Skipped -> null
    }
