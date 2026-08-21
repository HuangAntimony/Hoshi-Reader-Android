package moe.antimony.hoshi.features.reader

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.R
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.ui.UiText

data class ReaderFontDownloadUiState(
    val familyId: String,
    val variantId: String,
    val origin: ReaderFontDownloadOrigin,
    val progress: ReaderFontDownloadProgress,
)

enum class ReaderFontDownloadOrigin {
    FAMILY,
    VARIANT,
}

data class ReaderFontSelectionRequest(
    val familyId: String,
    val variantId: String,
    val origin: ReaderFontDownloadOrigin,
)

data class ReaderAppearanceFontUiState(
    val library: ReaderFontLibraryState,
    val download: ReaderFontDownloadUiState? = null,
    val isImporting: Boolean = false,
    val error: UiText? = null,
    val failedSelection: ReaderFontSelectionRequest? = null,
    val selectionToApply: ReaderFontSelection? = null,
)

@HiltViewModel
internal class ReaderAppearanceViewModel @Inject constructor(
    private val fontManager: ReaderFontManager,
    private val downloaderFactory: ReaderFontDownloaderFactory,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val downloadState = MutableStateFlow<ReaderFontDownloadUiState?>(null)
    private val importing = MutableStateFlow(false)
    private val error = MutableStateFlow<UiText?>(null)
    private val failedSelection = MutableStateFlow<ReaderFontSelectionRequest?>(null)
    private val selectionToApply = MutableStateFlow<ReaderFontSelection?>(null)
    private var downloadJob: Job? = null

    private val baseUiState = combine(
        fontManager.libraryState,
        downloadState,
        importing,
        error,
        failedSelection,
    ) { library, download, isImporting, currentError, failed ->
        ReaderAppearanceFontUiState(library, download, isImporting, currentError, failed)
    }
    val uiState: StateFlow<ReaderAppearanceFontUiState> = combine(
        baseUiState,
        selectionToApply,
    ) { state, pendingSelection ->
        state.copy(selectionToApply = pendingSelection)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ReaderAppearanceFontUiState(fontManager.libraryState.value),
    )

    fun selectVariant(
        familyId: String,
        variantId: String,
        origin: ReaderFontDownloadOrigin,
    ) {
        if (downloadJob?.isActive == true) return
        error.value = null
        failedSelection.value = null
        val family = fontManager.fontFamilies().firstOrNull { it.id == familyId } ?: return
        val variant = family.variants.firstOrNull { it.id == variantId } ?: return
        val remote = variant.remoteFile
        if (remote == null || variant.localFile != null) {
            selectionToApply.value = ReaderFontSelection(familyId, variantId)
            return
        }
        downloadState.value = ReaderFontDownloadUiState(
            familyId,
            variantId,
            origin,
            ReaderFontDownloadProgress(0, remote.expectedSize),
        )
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                downloaderFactory.create(fontManager.managedFontsDirectory()).download(remote) { progress ->
                    downloadState.value = ReaderFontDownloadUiState(familyId, variantId, origin, progress)
                }
                withContext(ioDispatcher) { fontManager.refresh() }
                selectionToApply.value = ReaderFontSelection(familyId, variantId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error.value = UiText.Resource(R.string.reader_appearance_font_download_failed)
                failedSelection.value = ReaderFontSelectionRequest(familyId, variantId, origin)
            } finally {
                downloadState.value = null
                if (downloadJob === currentCoroutineContext()[Job]) downloadJob = null
            }
        }
        downloadJob = job
        job.start()
    }

    fun acknowledgeSelection(selection: ReaderFontSelection) {
        selectionToApply.compareAndSet(selection, null)
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    fun importFont(contentResolver: ContentResolver, uri: Uri) {
        if (importing.value) return
        viewModelScope.launch {
            importing.value = true
            error.value = null
            failedSelection.value = null
            try {
                withContext(ioDispatcher) { fontManager.importFont(contentResolver, uri) }
            } catch (_: Exception) {
                error.value = UiText.Resource(R.string.reader_appearance_font_import_failed)
            } finally {
                importing.value = false
            }
        }
    }

    fun deleteFamily(familyId: String) {
        viewModelScope.launch {
            withContext(ioDispatcher) { fontManager.deleteFamily(familyId) }
        }
    }

    fun clearError() {
        error.value = null
        failedSelection.value = null
    }
}
