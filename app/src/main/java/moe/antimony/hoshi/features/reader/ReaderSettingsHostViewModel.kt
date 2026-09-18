package moe.antimony.hoshi.features.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText

internal data class ReaderSettingsHostState(
    val settings: ReaderSettings? = null,
    val error: UiText? = null,
)

/** App-wide settings readiness; never used to scope a tab or reader route ViewModel. */
@HiltViewModel
internal class ReaderSettingsHostViewModel internal constructor(
    private val settings: Flow<ReaderSettings>,
    private val save: suspend ((ReaderSettings) -> ReaderSettings) -> Unit,
    private val coroutineScope: CoroutineScope?,
) : ViewModel() {
    @Inject constructor(repository: ReaderSettingsRepository) : this(
        repository.settings, repository::update, null,
    )

    private val scope get() = coroutineScope ?: viewModelScope
    private val _uiState = MutableStateFlow(ReaderSettingsHostState())
    val uiState = _uiState.asStateFlow()
    private var loadJob: Job? = null
    private val saveMutex = Mutex()

    init { retry() }

    fun retry() {
        loadJob?.cancel()
        _uiState.update { it.copy(error = null) }
        loadJob = scope.launch {
            try {
                settings.collect { value -> _uiState.update { it.copy(settings = value) } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(error = UiText.Resource(R.string.display_settings_load_failed)) }
            }
        }
    }

    fun update(transform: (ReaderSettings) -> ReaderSettings) {
        if (_uiState.value.settings == null) return
        scope.launch {
            try {
                saveMutex.withLock {
                    save(transform)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(error = UiText.Resource(R.string.display_settings_save_failed)) }
            }
        }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }
}
