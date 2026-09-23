package moe.antimony.hoshi.features.sasayaki

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.ui.UiText

internal enum class SasayakiSubtitleExportAction { Save, Share }
internal data class SasayakiSubtitleExportRequest(val file: File, val action: SasayakiSubtitleExportAction)
internal data class SasayakiSubtitleExportUiState(
    val busy: Boolean = false,
    val request: SasayakiSubtitleExportRequest? = null,
    val message: UiText? = null,
)

@HiltViewModel
internal class SasayakiSubtitleExportViewModel @Inject constructor(
    private val repository: SasayakiSubtitleExportRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SasayakiSubtitleExportUiState(busy = savedState.contains(PendingSave)))
    val uiState = _uiState.asStateFlow()

    fun prepare(title: String, match: SasayakiMatchData, action: SasayakiSubtitleExportAction) {
        if (_uiState.value.busy) return
        _uiState.value = SasayakiSubtitleExportUiState(busy = true)
        viewModelScope.launch {
            try {
                val file = repository.prepare(title, match)
                if (action == SasayakiSubtitleExportAction.Save) savedState[PendingSave] = file.path
                _uiState.value = SasayakiSubtitleExportUiState(busy = true, request = SasayakiSubtitleExportRequest(file, action))
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { launchFailed() }
        }
    }

    fun launched() {
        _uiState.value = _uiState.value.copy(busy = savedState.contains(PendingSave), request = null)
    }

    fun save(destination: Uri?) {
        val file = savedState.remove<String>(PendingSave)?.let(::File) ?: return
        _uiState.value = SasayakiSubtitleExportUiState(busy = destination != null)
        if (destination == null) return
        viewModelScope.launch {
            try {
                repository.save(file, destination)
                _uiState.value = SasayakiSubtitleExportUiState(message = UiText.Resource(R.string.sasayaki_srt_saved))
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { launchFailed() }
        }
    }

    fun launchFailed() {
        savedState.remove<String>(PendingSave)
        _uiState.value = SasayakiSubtitleExportUiState(message = UiText.Resource(R.string.sasayaki_srt_export_failed))
    }

    fun dismissMessage() { _uiState.value = _uiState.value.copy(message = null) }

    private companion object { const val PendingSave = "sasayaki.srt.pendingSave" }
}
