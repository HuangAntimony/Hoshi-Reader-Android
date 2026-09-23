package moe.antimony.hoshi.features.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.ReadingSession
import moe.antimony.hoshi.ui.UiText

internal data class StatisticsSessionDraft(
    val id: String,
    val startedAt: Long,
    val characters: String,
    val hours: String,
    val minutes: String,
) {
    val totalMinutes: Int?
        get() {
            val h = hours.toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val m = minutes.toIntOrNull()?.takeIf { it in 0..59 } ?: return null
            return (h.toLong() * 60 + m).takeIf { it <= Int.MAX_VALUE }?.toInt()
        }
    val canSave: Boolean get() = characters.toIntOrNull()?.let { it >= 0 } == true && totalMinutes != null

    companion object {
        fun from(id: String, statistic: ReadingSession): StatisticsSessionDraft {
            val minutes = (statistic.readingTime / 60).roundToLong().coerceIn(0, Int.MAX_VALUE.toLong())
            return StatisticsSessionDraft(id, statistic.startedAt, statistic.charactersRead.toString(), (minutes / 60).toString(), (minutes % 60).toString())
        }
    }
}

internal data class StatisticsBookUiState(
    val book: StatisticsBookRecords? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val draft: StatisticsSessionDraft? = null,
    val error: UiText? = null,
    val closeRequested: Boolean = false,
)

@HiltViewModel
internal class StatisticsBookViewModel internal constructor(
    private val repository: StatisticsRepository,
    private val coroutineScope: CoroutineScope?,
) : ViewModel() {
    @Inject constructor(repository: StatisticsRepository, statisticsStore: moe.antimony.hoshi.epub.BookStatisticsStore, syncStorage: moe.antimony.hoshi.features.sync.SyncStorage) : this(repository, null) {
        scope.launch { combine(statisticsStore.changes, syncStorage.booksChanged) { _, _ -> Unit }.collect { folder?.let(::load) } }
    }

    private val scope get() = coroutineScope ?: viewModelScope
    private val _uiState = MutableStateFlow(StatisticsBookUiState())
    val uiState = _uiState.asStateFlow()
    private var folder: String? = null
    private var loadJob: Job? = null
    private var generation = 0

    fun load(folder: String) {
        if (_uiState.value.isSaving) return
        this.folder = folder
        val request = ++generation
        loadJob?.cancel()
        _uiState.update { it.copy(isLoading = true) }
        loadJob = scope.launch {
            try {
                val book = repository.loadBookStatistics(folder)
                if (request == generation) _uiState.update { it.copy(book = book, isLoading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (request == generation) _uiState.update { it.copy(isLoading = false, error = failure()) }
            }
        }
    }

    fun edit(id: String) {
        if (_uiState.value.isSaving) return
        val statistic = _uiState.value.book?.sessions?.get(id)?.value ?: return
        _uiState.update { it.copy(draft = StatisticsSessionDraft.from(id, statistic)) }
    }

    fun changeDraft(transform: (StatisticsSessionDraft) -> StatisticsSessionDraft) {
        if (!_uiState.value.isSaving) _uiState.update { it.copy(draft = it.draft?.let(transform)) }
    }

    fun cancelEdit() {
        if (!_uiState.value.isSaving) _uiState.update { it.copy(draft = null) }
    }

    fun saveSession() {
        val draft = _uiState.value.draft?.takeIf { it.canSave } ?: return
        val original = _uiState.value.book!!.sessions.getValue(draft.id).value!!
        val previous = StatisticsSessionDraft.from(draft.id, original)
        val characters = draft.characters.toInt().takeIf { draft.characters != previous.characters }
        val seconds = (requireNotNull(draft.totalMinutes) * 60.0).takeIf { draft.hours != previous.hours || draft.minutes != previous.minutes }
        mutate { folder -> repository.editSession(folder, draft.id, characters, seconds) }
    }

    fun deleteSession(id: String) = mutate { folder -> repository.deleteSessions(folder, listOf(id)) }

    fun deleteAll() {
        val ids = _uiState.value.book?.sessions?.keys.orEmpty().toList()
        mutate(closeAfter = true) { folder -> repository.deleteSessions(folder, ids) }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    private fun mutate(closeAfter: Boolean = false, operation: suspend (String) -> Unit) {
        val target = folder ?: return
        if (_uiState.value.isSaving) return
        ++generation
        loadJob?.cancel()
        _uiState.update { it.copy(isSaving = true, isLoading = false, error = null) }
        scope.launch {
            try {
                operation(target)
                val book = repository.loadBookStatistics(target)
                _uiState.update {
                    it.copy(book = book, draft = null, closeRequested = closeAfter || book == null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(error = failure()) }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    private fun failure() = UiText.Resource(R.string.statistics_operation_failed)
}
