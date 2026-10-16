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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.settings.SettingsLoadState
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.withErrorDetails

internal data class ReaderSettingsHostState(
    // Retain the app root and theme while loadState gates controls for the current profile.
    val settings: ReaderSettings? = null,
    val loadState: SettingsLoadState<ReaderSettings> = SettingsLoadState.Loading,
    val error: UiText? = null,
)

/** App-wide settings readiness; never used to scope a tab or reader route ViewModel. */
@HiltViewModel
internal class ReaderSettingsHostViewModel internal constructor(
    private val settings: Flow<ReaderSettings>,
    private val save: suspend ((ReaderSettings) -> ReaderSettings) -> Unit,
    private val coroutineScope: CoroutineScope?,
    private val profileIds: Flow<String> = flowOf("default"),
) : ViewModel() {
    @Inject constructor(repository: ReaderSettingsRepository) : this(
        settings = repository.settings,
        save = repository::update,
        coroutineScope = null,
        profileIds = repository.profileIds,
    )

    private val scope get() = coroutineScope ?: viewModelScope
    private val _uiState = MutableStateFlow(ReaderSettingsHostState())
    val uiState = _uiState.asStateFlow()
    private var loadJob: Job? = null
    private val saveMutex = Mutex()
    @Volatile private var loadGeneration = 0L

    init {
        scope.launch {
            profileIds.distinctUntilChanged().collect { retry() }
        }
    }

    fun retry() {
        val generation = ++loadGeneration
        loadJob?.cancel()
        _uiState.update { it.copy(loadState = SettingsLoadState.Loading, error = null) }
        loadJob = scope.launch {
            try {
                settings.collect { value ->
                    if (generation == loadGeneration) {
                        _uiState.update { it.copy(settings = value, loadState = SettingsLoadState.Loaded(value)) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == loadGeneration) {
                    val message = UiText.Resource(R.string.display_settings_load_failed).withErrorDetails(failure)
                    _uiState.update { it.copy(loadState = SettingsLoadState.Failed(message), error = message) }
                }
            }
        }
    }

    fun update(transform: (ReaderSettings) -> ReaderSettings) {
        val generation = loadGeneration
        if (!canUpdate(generation)) return
        scope.launch {
            try {
                saveMutex.withLock {
                    if (!canUpdate(generation)) return@withLock
                    save { current -> if (canUpdate(generation)) transform(current) else current }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (canUpdate(generation)) {
                    _uiState.update { it.copy(error = UiText.Resource(R.string.display_settings_save_failed).withErrorDetails(failure)) }
                }
            }
        }
    }

    private fun canUpdate(generation: Long): Boolean =
        generation == loadGeneration && _uiState.value.loadState is SettingsLoadState.Loaded

    fun dismissError() = _uiState.update { it.copy(error = null) }
}
