package moe.antimony.hoshi.features.statistics

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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.reader.ReaderSettingsRepository
import moe.antimony.hoshi.features.sync.SyncSettingsRepository
import moe.antimony.hoshi.ui.UiText

internal data class StatisticsSettingsUiState(
    val settings: ReaderSettings? = null,
    val syncEnabled: Boolean = false,
    val archivedBookCount: Int = 0,
    val isWorking: Boolean = false,
    val error: UiText? = null,
)

@HiltViewModel
internal class StatisticsSettingsViewModel internal constructor(
    readerSettings: Flow<ReaderSettings>,
    syncEnabled: Flow<Boolean>,
    private val updateSettings: suspend ((ReaderSettings) -> ReaderSettings) -> Unit,
    private val repository: StatisticsRepository,
    private val coroutineScope: CoroutineScope?,
) : ViewModel() {
    @Inject
    constructor(
        readerSettingsRepository: ReaderSettingsRepository,
        syncSettingsRepository: SyncSettingsRepository,
        repository: StatisticsRepository,
    ) : this(
        readerSettingsRepository.settings,
        syncSettingsRepository.settings.map { it.enabled },
        readerSettingsRepository::update,
        repository,
        null,
    )

    private val scope get() = coroutineScope ?: viewModelScope
    private val _uiState = MutableStateFlow(StatisticsSettingsUiState())
    val uiState = _uiState.asStateFlow()
    private var reloadJob: Job? = null
    private var generation = 0

    init {
        scope.launch {
            combine(readerSettings, syncEnabled) { settings, enabled -> settings to enabled }
                .collect { (settings, enabled) ->
                    _uiState.update { it.copy(settings = settings, syncEnabled = enabled) }
                }
        }
    }

    fun reload() {
        val request = ++generation
        reloadJob?.cancel()
        reloadJob = scope.launch {
            try {
                val count = repository.loadArchiveSummary()
                if (request == generation) _uiState.update { it.copy(archivedBookCount = count) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (request == generation) showError()
            }
        }
    }

    fun update(transform: (ReaderSettings) -> ReaderSettings) {
        scope.launch {
            try {
                updateSettings(transform)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showError()
            }
        }
    }

    fun clearArchive() {
        if (_uiState.value.isWorking) return
        ++generation
        reloadJob?.cancel()
        _uiState.update { it.copy(isWorking = true) }
        scope.launch {
            try {
                repository.clearArchive()
                _uiState.update { it.copy(archivedBookCount = 0) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showError()
            } finally {
                _uiState.update { it.copy(isWorking = false) }
                reload()
            }
        }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    private fun showError() = _uiState.update {
        it.copy(error = UiText.Resource(R.string.statistics_operation_failed))
    }
}
