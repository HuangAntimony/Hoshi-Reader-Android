package moe.antimony.hoshi.features.bookshelf

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookSortOption
import moe.antimony.hoshi.features.sync.GoogleDriveApiException
import moe.antimony.hoshi.features.sync.StatisticsSyncMode
import moe.antimony.hoshi.features.sync.SyncDirection
import moe.antimony.hoshi.features.sync.SyncResult
import moe.antimony.hoshi.ui.UiText

internal data class BookImportItem(
    val uri: Uri,
    val displayName: String? = null,
)

internal data class PendingBookImport(
    val importKey: String,
    val displayName: String? = null,
    val importOperation: suspend () -> String,
)

@HiltViewModel
internal class BookshelfViewModel : ViewModel {
    private val repository: BookshelfRepository
    private val importGate: PendingImportGate<String>
    private val injectedScope: CoroutineScope?
    private val workScope: CoroutineScope
        get() = injectedScope ?: viewModelScope

    @Inject
    constructor(repository: BookshelfRepository) : this(
        repository = repository,
        coroutineScope = null,
        importGate = PendingImportGate(),
        marker = Unit,
    )

    internal constructor(
        repository: BookshelfRepository,
        coroutineScope: CoroutineScope,
        importGate: PendingImportGate<String> = PendingImportGate(),
    ) : this(
        repository = repository,
        coroutineScope = coroutineScope,
        importGate = importGate,
        marker = Unit,
    )

    private constructor(
        repository: BookshelfRepository,
        coroutineScope: CoroutineScope?,
        importGate: PendingImportGate<String>,
        @Suppress("UNUSED_PARAMETER") marker: Unit,
    ) : super() {
        this.repository = repository
        this.importGate = importGate
        injectedScope = coroutineScope
    }

    private val _uiState = MutableStateFlow(BookshelfUiState())
    val uiState: StateFlow<BookshelfUiState> = _uiState
    private var openBookInFlight = false
    private var reloadGeneration = 0
    private var remoteLoadJob: Job? = null
    private val remoteImportsInFlight = mutableSetOf<String>()
    private val remoteDeletesInFlight = mutableSetOf<String>()

    fun reloadBookEntries() {
        reloadBookEntries(_uiState.value.sortOption)
    }

    fun refreshRemoteBooks() {
        if (!_uiState.value.hasLoadedBooks) return
        remoteLoadJob?.cancel()
        val generation = reloadGeneration
        val localEntries = _uiState.value.bookEntries
        _uiState.update { it.copy(errorMessage = null) }
        reloadRemoteBookEntries(localEntries, generation, suppressOfflineErrors = false)
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
                        errorMessage = error.localizedMessage?.let(UiText::Literal)
                            ?: UiText.Resource(R.string.bookshelf_open_failed),
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
            errorPrefix = UiText.Resource(R.string.bookshelf_import_failed),
            onComplete = { importGate.finish(importKey) },
            blockingProgressMessage = displayName
                ?.takeIf { it.isNotBlank() }
                ?.let { UiText.Resource(R.string.bookshelf_importing_named_format, it) }
                ?: UiText.Resource(R.string.bookshelf_importing_epub),
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
            importBooksBatch(imports, importKey)
        }
    }

    fun importBookFolderItems(importsProvider: suspend () -> List<BookImportItem>) {
        importBookFolder {
            importsProvider().map { import ->
                PendingBookImport(
                    importKey = import.uri.toString(),
                    displayName = import.displayName,
                ) {
                    repository.importBook(import.uri)
                }
            }
        }
    }

    internal fun importBookFolder(importsProvider: suspend () -> List<PendingBookImport>) {
        workScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    blockingProgressMessage = UiText.Resource(R.string.bookshelf_scanning_folder),
                    statusMessage = null,
                    errorMessage = null,
                )
            }
            try {
                val imports = importsProvider()
                if (imports.isEmpty()) {
                    _uiState.update {
                        it.copy(errorMessage = UiText.Resource(R.string.bookshelf_no_epub_files_found))
                    }
                    return@launch
                }
                if (imports.size == 1) {
                    _uiState.update { it.copy(isLoading = false, blockingProgressMessage = null) }
                    val import = imports.single()
                    importBook(import.importKey, import.displayName, import.importOperation)
                    return@launch
                }
                val importKey = imports.joinToString(separator = "\n") { it.importKey }
                if (!importGate.tryStart(importKey)) {
                    return@launch
                }
                importBooksBatch(imports, importKey)
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        errorMessage = error.localizedMessage?.let(UiText::Literal)
                            ?: UiText.Resource(R.string.bookshelf_import_failed),
                    )
                }
            } finally {
                _uiState.update { it.copy(isLoading = false, blockingProgressMessage = null) }
            }
        }
    }

    private suspend fun importBooksBatch(imports: List<PendingBookImport>, importKey: String) {
        val failed = mutableListOf<String>()
        _uiState.update {
            it.copy(
                isLoading = true,
                blockingProgressMessage = UiText.Resource(R.string.bookshelf_importing_progress_format, 1, imports.size),
                errorMessage = null,
            )
        }
        try {
            imports.forEachIndexed { index, import ->
                _uiState.update {
                    it.copy(
                        blockingProgressMessage = UiText.Resource(
                            R.string.bookshelf_importing_progress_format,
                            index + 1,
                            imports.size,
                        ),
                    )
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
                    it.copy(
                        errorMessage = UiText.Resource(
                            R.string.bookshelf_import_failed_list_format,
                            failed.joinToString(separator = "\n"),
                        ),
                    )
                }
            }
        } catch (error: Throwable) {
            _uiState.update {
                it.copy(
                    errorMessage = error.localizedMessage?.let(UiText::Literal)
                        ?: UiText.Resource(R.string.bookshelf_import_failed),
                )
            }
        } finally {
            _uiState.update { it.copy(isLoading = false, blockingProgressMessage = null) }
            importGate.finish(importKey)
        }
    }

    fun deleteBook(entry: BookEntry) {
        workScope.launch {
            repository.deleteBook(entry)
            reloadBookEntriesSync()
        }
    }

    fun exportBook(entry: BookEntry, uri: Uri) {
        runLoading(
            errorPrefix = UiText.Resource(R.string.bookshelf_export_failed),
            blockingProgressMessage = UiText.Resource(R.string.bookshelf_exporting_epub),
            block = {
                repository.exportBook(entry, uri)
                _uiState.update { it.copy(statusMessage = UiText.Resource(R.string.bookshelf_exported_epub_format, entry.displayTitle)) }
            },
        )
    }

    fun importRemoteBook(
        entry: RemoteBookEntry,
        syncStats: Boolean,
        syncAudioBook: Boolean,
    ) {
        if (entry.id in remoteDeletesInFlight) return
        if (!remoteImportsInFlight.add(entry.id)) return
        workScope.launch {
            _uiState.update {
                it.copy(
                    statusMessage = null,
                    errorMessage = null,
                    remoteImportProgressById = it.remoteImportProgressById + (entry.id to 0.0),
                    remoteBusyBookIds = it.remoteBusyBookIds + entry.id,
                )
            }
            try {
                repository.importRemoteBook(
                    entry = entry,
                    syncStats = syncStats,
                    syncAudioBook = syncAudioBook,
                ) { progress ->
                    _uiState.update {
                        it.copy(remoteImportProgressById = it.remoteImportProgressById + (entry.id to progress))
                    }
                }
                removeRemoteBook(entry.id)
                reloadBookEntriesSync()
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        errorMessage = UiText.Resource(R.string.bookshelf_remote_book_import_failed),
                    )
                }
            } finally {
                remoteImportsInFlight -= entry.id
                _uiState.update {
                    it.copy(
                        remoteImportProgressById = it.remoteImportProgressById - entry.id,
                        remoteBusyBookIds = it.remoteBusyBookIds - entry.id,
                    )
                }
            }
        }
    }

    fun deleteRemoteBook(entry: RemoteBookEntry) {
        if (entry.id in remoteImportsInFlight) return
        if (!remoteDeletesInFlight.add(entry.id)) return
        workScope.launch {
            _uiState.update {
                it.copy(
                    statusMessage = null,
                    errorMessage = null,
                    remoteBusyBookIds = it.remoteBusyBookIds + entry.id,
                )
            }
            try {
                repository.deleteRemoteBook(entry)
                removeRemoteBook(entry.id)
                reloadBookEntriesSync()
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        errorMessage = UiText.Resource(R.string.bookshelf_remote_book_delete_failed),
                    )
                }
            } finally {
                remoteDeletesInFlight -= entry.id
                _uiState.update { it.copy(remoteBusyBookIds = it.remoteBusyBookIds - entry.id) }
            }
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

    fun renameBook(entry: BookEntry, title: String) {
        workScope.launch {
            repository.renameBook(entry, title.trim().takeIf { it.isNotEmpty() })
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
            errorPrefix = UiText.Resource(R.string.bookshelf_sync_failed),
            blockingProgressMessage = UiText.Resource(R.string.bookshelf_syncing),
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

    fun consumeStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    fun consumeErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun reloadBookEntries(sortOption: BookSortOption) {
        workScope.launch {
            reloadBookEntriesSync(sortOption)
        }
    }

    private suspend fun reloadBookEntriesSync(sortOption: BookSortOption = _uiState.value.sortOption) {
        val generation = ++reloadGeneration
        remoteLoadJob?.cancel()
        val firstResult = loadBookEntries(sortOption)
        val result = if (firstResult.settings.sortOption != sortOption) {
            loadBookEntries(firstResult.settings.sortOption)
        } else {
            firstResult
        }
        if (generation != reloadGeneration) return
        _uiState.update {
            val validSelectedIds = it.selectedBookIds.intersect(result.entries.mapTo(mutableSetOf()) { entry -> entry.metadata.id })
            it.copy(
                bookEntries = result.entries,
                bookProgressById = result.progressById,
                coverSourcesById = result.coverSourcesById,
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
        reloadRemoteBookEntries(result.entries, generation, suppressOfflineErrors = true)
    }

    private suspend fun loadBookEntries(sortOption: BookSortOption): BookshelfLoadResult =
        repository.loadBooks(sortOption)

    private fun reloadRemoteBookEntries(
        localEntries: List<BookEntry>,
        generation: Int,
        suppressOfflineErrors: Boolean,
    ) {
        remoteLoadJob = workScope.launch {
            try {
                val remoteResult = repository.loadRemoteBooks(localEntries)
                if (generation != reloadGeneration) return@launch
                _uiState.update {
                    it.copy(
                        remoteBookEntries = remoteResult.remoteEntries,
                        remoteProgressById = remoteResult.remoteProgressById,
                        remoteCoverSourcesById = remoteResult.remoteCoverSourcesById,
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (generation != reloadGeneration) return@launch
                if (suppressOfflineErrors && error.isOfflineRemoteLoadError()) return@launch
                _uiState.update {
                    it.copy(
                        errorMessage = UiText.Resource(R.string.bookshelf_remote_books_load_failed),
                    )
                }
            }
        }
    }

    private fun Throwable.isOfflineRemoteLoadError(): Boolean =
        this is GoogleDriveApiException && message == GoogleDriveApiException.NoInternetConnectionMessage

    private fun removeRemoteBook(remoteBookId: String) {
        _uiState.update {
            it.copy(
                remoteBookEntries = it.remoteBookEntries.filterNot { remote -> remote.id == remoteBookId },
                remoteProgressById = it.remoteProgressById - remoteBookId,
                remoteImportProgressById = it.remoteImportProgressById - remoteBookId,
                remoteBusyBookIds = it.remoteBusyBookIds - remoteBookId,
                remoteCoverSourcesById = it.remoteCoverSourcesById - remoteBookId,
            )
        }
    }

    private fun runLoading(
        errorPrefix: UiText,
        onComplete: () -> Unit = {},
        blockingProgressMessage: UiText? = null,
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
                        errorMessage = error.localizedMessage?.let(UiText::Literal) ?: errorPrefix,
                    )
                }
            } finally {
                _uiState.update { it.copy(isLoading = false, blockingProgressMessage = null) }
                onComplete()
            }
        }
    }

}

private fun PendingBookImport.failureDisplayName(): String =
    displayName?.takeIf { it.isNotBlank() }
        ?: importKey.substringAfterLast('/').takeIf { it.isNotBlank() }
        ?: "EPUB"

private fun SyncResult.bookshelfMessage(): UiText? =
    when (this) {
        is SyncResult.Exported -> UiText.Resource(R.string.bookshelf_synced_to_ttu_format, title, characterCount)
        is SyncResult.Imported -> UiText.Resource(R.string.bookshelf_synced_from_ttu_format, title, characterCount)
        is SyncResult.Synced -> UiText.Resource(R.string.bookshelf_already_synced_format, title)
        SyncResult.Skipped -> null
    }
