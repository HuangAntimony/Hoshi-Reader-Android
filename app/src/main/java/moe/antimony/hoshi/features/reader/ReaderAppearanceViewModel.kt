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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    val progress: ReaderFontDownloadProgress,
)

data class ReaderAppearanceFontUiState(
    val library: ReaderFontLibraryState,
    val download: ReaderFontDownloadUiState? = null,
    val isImporting: Boolean = false,
    val error: UiText? = null,
    val failedSelection: ReaderFontSelection? = null,
)

sealed interface ReaderAppearanceFontEvent {
    data class Apply(val familyId: String, val variantId: String) : ReaderAppearanceFontEvent
}

@HiltViewModel
internal class ReaderAppearanceViewModel @Inject constructor(
    private val fontManager: ReaderFontManager,
    private val downloaderFactory: ReaderFontDownloaderFactory,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val downloadState = MutableStateFlow<ReaderFontDownloadUiState?>(null)
    private val importing = MutableStateFlow(false)
    private val error = MutableStateFlow<UiText?>(null)
    private val failedSelection = MutableStateFlow<ReaderFontSelection?>(null)
    private val _events = MutableSharedFlow<ReaderAppearanceFontEvent>(extraBufferCapacity = 1)
    private var downloadJob: Job? = null

    val events: SharedFlow<ReaderAppearanceFontEvent> = _events.asSharedFlow()
    val uiState: StateFlow<ReaderAppearanceFontUiState> = combine(
        fontManager.libraryState,
        downloadState,
        importing,
        error,
        failedSelection,
    ) { library, download, isImporting, currentError, failed ->
        ReaderAppearanceFontUiState(library, download, isImporting, currentError, failed)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ReaderAppearanceFontUiState(fontManager.libraryState.value),
    )

    fun selectVariant(familyId: String, variantId: String) {
        if (downloadJob?.isActive == true) return
        error.value = null
        failedSelection.value = null
        val family = fontManager.fontFamilies().firstOrNull { it.id == familyId } ?: return
        val variant = family.variants.firstOrNull { it.id == variantId } ?: return
        val remote = variant.remoteFile
        if (remote == null || variant.localFile != null) {
            _events.tryEmit(ReaderAppearanceFontEvent.Apply(familyId, variantId))
            return
        }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            downloadState.value = ReaderFontDownloadUiState(
                familyId,
                variantId,
                ReaderFontDownloadProgress(0, remote.expectedSize),
            )
            try {
                downloaderFactory.create(fontManager.managedFontsDirectory()).download(remote) { progress ->
                    downloadState.value = ReaderFontDownloadUiState(familyId, variantId, progress)
                }
                withContext(ioDispatcher) { fontManager.refresh() }
                _events.emit(ReaderAppearanceFontEvent.Apply(familyId, variantId))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error.value = UiText.Resource(R.string.reader_appearance_font_download_failed)
                failedSelection.value = ReaderFontSelection(familyId, variantId)
            } finally {
                downloadState.value = null
                if (downloadJob === currentCoroutineContext()[Job]) downloadJob = null
            }
        }
        downloadJob = job
        job.start()
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
