package moe.antimony.hoshi.features.sasayaki

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.ui.UiText

internal enum class SasayakiMatchMode(@param:StringRes val labelRes: Int) {
    Subtitles(R.string.sasayaki_match_source_subtitles),
    Transcription(R.string.sasayaki_transcription),
}

internal data class SasayakiTranscriptionUiState(
    val mode: SasayakiMatchMode = SasayakiMatchMode.Subtitles,
    val stage: SasayakiTranscriptionStage = SasayakiTranscriptionStage.Idle,
    val through: Double = 0.0,
    val duration: Double = 0.0,
    val download: Double = 0.0,
    val downloadBytes: Long = 0,
    val remainingSeconds: Double? = null,
    val hasTranscript: Boolean = false,
    val transcriptComplete: Boolean = false,
    val hasSource: Boolean = false,
    val transcriptMatchesSource: Boolean = true,
    val isLoading: Boolean = false,
    val isUpdating: Boolean = false,
    val busyElsewhere: Boolean = false,
    val showClearConfirmation: Boolean = false,
    val error: UiText? = null,
) {
    val running get() = stage != SasayakiTranscriptionStage.Idle
    val controlsLocked get() = running || isLoading || isUpdating || busyElsewhere
    val canStart get() = hasSource && !controlsLocked
    val canPause get() = running && stage != SasayakiTranscriptionStage.Pausing && stage != SasayakiTranscriptionStage.Aligning
    val actionLabelRes: Int
        @StringRes get() = when {
            !hasTranscript || !transcriptMatchesSource -> R.string.sasayaki_transcription_start
            transcriptComplete -> R.string.sasayaki_transcription_realign
            through > 0 -> R.string.sasayaki_transcription_resume
            else -> R.string.sasayaki_transcription_start
        }
}

/** Lives with the Reader route so a paused task can deliver its final match after the sheet closes. */
@HiltViewModel
internal class SasayakiTranscriptionViewModel internal constructor(
    private val coordinator: SasayakiTranscriptionCoordinator,
    private val repository: SasayakiTranscriptionRepository,
    private val coroutineScope: CoroutineScope?,
) : ViewModel() {
    @Inject constructor(
        coordinator: SasayakiTranscriptionCoordinator,
        repository: SasayakiTranscriptionRepository,
    ) : this(coordinator, repository, null)

    private val scope get() = coroutineScope ?: viewModelScope
    private val _uiState = MutableStateFlow(SasayakiTranscriptionUiState())
    val uiState = _uiState.asStateFlow()
    private var root: File? = null
    private var source: String? = null
    private var transcript: TranscriptSummary? = null
    private var onMatchUpdated: ((SasayakiMatchData) -> Unit)? = null
    private var observedRevision = 0L
    private var observedCompletionRevision = 0L
    private var wasRunning = false
    private var loadJob: Job? = null
    private var startJob: Job? = null
    private var loadGeneration = 0
    private var localError: UiText? = null

    init {
        scope.launch {
            coordinator.state.collect { task ->
                val ownTask = task.root == root && root != null
                val completed = ownTask && !task.running && (wasRunning || task.completionRevision > observedCompletionRevision)
                wasRunning = ownTask && task.running
                if (ownTask && task.revision > observedRevision) {
                    observedRevision = task.revision
                    task.match?.let { onMatchUpdated?.invoke(it) }
                }
                if (completed) {
                    observedCompletionRevision = task.completionRevision
                    root?.let(::loadTranscript)
                }
                render(task)
            }
        }
    }

    fun bind(root: File, source: String?, onMatchUpdated: (SasayakiMatchData) -> Unit) {
        this.onMatchUpdated = onMatchUpdated
        this.source = source
        if (this.root != root) {
            this.root?.let(coordinator::pause)
            startJob?.cancel()
            this.root = root
            transcript = null
            localError = null
            observedRevision = coordinator.state.value.revision
            observedCompletionRevision = coordinator.state.value.completionRevision
            wasRunning = false
            _uiState.value = SasayakiTranscriptionUiState()
            loadTranscript(root)
            coordinator.state.value.takeIf { it.root == root && it.running }?.match?.let(onMatchUpdated)
        }
        render(coordinator.state.value)
    }

    fun selectMode(mode: SasayakiMatchMode) {
        if (!_uiState.value.controlsLocked) _uiState.value = _uiState.value.copy(mode = mode)
    }

    fun start() {
        if (!_uiState.value.canStart) return
        val activeRoot = root ?: return
        val activeSource = source ?: return
        localError = null
        _uiState.value = _uiState.value.copy(isUpdating = true, error = null, showClearConfirmation = false)
        startJob = scope.launch {
            try {
                coordinator.start(activeRoot, activeSource)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showError()
            } finally {
                if (root == activeRoot) {
                    _uiState.value = _uiState.value.copy(isUpdating = false)
                    render(coordinator.state.value)
                }
            }
        }
    }

    fun pause() {
        if (startJob?.isActive == true) {
            startJob?.cancel()
            _uiState.value = _uiState.value.copy(isUpdating = false)
        }
        root?.let(coordinator::pause)
    }

    fun confirmDownload() {
        root?.let(coordinator::confirmDownload)
    }

    fun requestClear() {
        if (_uiState.value.hasTranscript && !_uiState.value.controlsLocked) {
            _uiState.value = _uiState.value.copy(showClearConfirmation = true)
        }
    }

    fun dismissClear() {
        _uiState.value = _uiState.value.copy(showClearConfirmation = false)
    }

    fun confirmClear() {
        if (!_uiState.value.showClearConfirmation || _uiState.value.controlsLocked) return
        val activeRoot = root ?: return
        localError = null
        _uiState.value = _uiState.value.copy(isUpdating = true, error = null, showClearConfirmation = false)
        scope.launch {
            try {
                if (coordinator.clear(activeRoot) && root == activeRoot) transcript = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showError()
            } finally {
                if (root == activeRoot) {
                    _uiState.value = _uiState.value.copy(isUpdating = false)
                    render(coordinator.state.value)
                }
            }
        }
    }

    private fun loadTranscript(activeRoot: File) {
        val generation = ++loadGeneration
        loadJob?.cancel()
        _uiState.value = _uiState.value.copy(isLoading = true)
        loadJob = scope.launch {
            try {
                val loaded = repository.load(activeRoot)
                if (generation == loadGeneration) {
                    transcript = loaded?.let { TranscriptSummary(it.through, it.duration, it.source, it.isComplete) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == loadGeneration) showError()
            } finally {
                if (generation == loadGeneration) {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    render(coordinator.state.value)
                }
            }
        }
    }

    private fun render(task: SasayakiTranscriptionState) {
        val ownTask = task.takeIf { it.root == root && root != null }
        val runningTask = ownTask?.takeIf { it.running }
        val progress = runningTask?.takeIf { it.duration > 0 }
        val savedProgress = transcript?.takeIf { it.source == null || it.source == source }
        _uiState.value = _uiState.value.copy(
            mode = if (runningTask != null) SasayakiMatchMode.Transcription else _uiState.value.mode,
            stage = runningTask?.stage ?: SasayakiTranscriptionStage.Idle,
            through = progress?.through ?: savedProgress?.through ?: 0.0,
            duration = progress?.duration ?: savedProgress?.duration ?: 0.0,
            download = runningTask?.download ?: 0.0,
            downloadBytes = runningTask?.downloadBytes ?: 0,
            remainingSeconds = runningTask?.remainingSeconds,
            hasTranscript = runningTask?.hasTranscript == true || transcript != null,
            transcriptComplete = transcript?.isComplete == true,
            hasSource = source != null,
            transcriptMatchesSource = transcript?.source == null || transcript?.source == source,
            busyElsewhere = task.running && ownTask == null,
            error = localError ?: ownTask?.error,
        )
    }

    private fun showError() {
        localError = UiText.Resource(R.string.sasayaki_transcription_failed)
    }

    override fun onCleared() {
        root?.let(coordinator::pause)
        onMatchUpdated = null
        super.onCleared()
    }

    private data class TranscriptSummary(
        val through: Double,
        val duration: Double,
        val source: String?,
        val isComplete: Boolean,
    )
}
