package moe.antimony.hoshi.features.bookshelf

import android.net.Uri
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookSortOption

internal class BookshelfViewModel(
    private val repository: BookshelfRepository,
    coroutineScope: CoroutineScope? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
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

    fun reloadBookEntries() {
        reloadBookEntries(_uiState.value.sortOption)
    }

    fun changeSort(sortOption: BookSortOption) {
        _uiState.update { it.copy(sortOption = sortOption) }
        reloadBookEntries(sortOption)
    }

    fun openBook(entry: BookEntry) {
        runLoading(
            errorPrefix = "Failed to open EPUB.",
            block = {
                val bookId = repository.openBook(entry)
                reloadBookEntriesSync()
                _uiState.update { it.copy(openReaderBookId = bookId) }
            },
        )
    }

    fun importBook(uri: Uri) {
        importBook(uri.toString()) {
            repository.importBook(uri)
        }
    }

    internal fun importBook(importKey: String, importOperation: suspend () -> String) {
        if (!importGate.tryStart(importKey)) {
            return
        }
        runLoading(
            errorPrefix = "Failed to import EPUB.",
            onComplete = { importGate.finish(importKey) },
            block = {
                val bookId = importOperation()
                reloadBookEntriesSync()
                _uiState.update { it.copy(openReaderBookId = bookId) }
            },
        )
    }

    fun deleteBook(entry: BookEntry) {
        workScope.launch {
            withContext(ioDispatcher) {
                repository.deleteBook(entry)
                loadBookEntries(_uiState.value.sortOption)
            }.also { result ->
                _uiState.update {
                    it.copy(
                        bookEntries = result.entries,
                        bookProgressById = result.progressById,
                    )
                }
            }
        }
    }

    fun setSasayakiEnabled(enabled: Boolean) {
        _uiState.update { it.copy(sasayakiEnabled = enabled) }
    }

    fun rebuildLookupQuery() {
        workScope.launch(ioDispatcher) {
            runCatching { repository.rebuildLookupQuery() }
        }
    }

    fun consumeOpenReaderEvent() {
        _uiState.update { it.copy(openReaderBookId = null) }
    }

    private fun reloadBookEntries(sortOption: BookSortOption) {
        workScope.launch {
            val result = withContext(ioDispatcher) {
                loadBookEntries(sortOption)
            }
            _uiState.update {
                it.copy(
                    bookEntries = result.entries,
                    bookProgressById = result.progressById,
                    errorMessage = null,
                )
            }
        }
    }

    private suspend fun reloadBookEntriesSync() {
        val result = loadBookEntries(_uiState.value.sortOption)
        _uiState.update {
            it.copy(
                bookEntries = result.entries,
                bookProgressById = result.progressById,
            )
        }
    }

    private suspend fun loadBookEntries(sortOption: BookSortOption): BookshelfLoadResult =
        repository.loadBooks(sortOption)

    private fun runLoading(
        errorPrefix: String,
        onComplete: () -> Unit = {},
        block: suspend () -> Unit,
    ) {
        workScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                withContext(ioDispatcher) {
                    block()
                }
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        errorMessage = error.localizedMessage ?: errorPrefix,
                    )
                }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
                onComplete()
            }
        }
    }

    override fun onCleared() {
        ownedScope?.cancel()
    }
}
