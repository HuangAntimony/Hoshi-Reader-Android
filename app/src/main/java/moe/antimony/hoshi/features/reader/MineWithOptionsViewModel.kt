package moe.antimony.hoshi.features.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.antimony.hoshi.features.anki.ExampleSentence
import moe.antimony.hoshi.features.anki.ExampleSentenceSource
import moe.antimony.hoshi.features.anki.ImageCandidate
import moe.antimony.hoshi.features.anki.ImageSearchSource

internal data class MineWithOptionsUiState(
    val term: String = "",
    val loading: Boolean = false,
    val candidates: List<ExampleSentence> = emptyList(),
    val imageLoading: Boolean = false,
    val imageCandidates: List<ImageCandidate> = emptyList(),
)

@HiltViewModel
internal class MineWithOptionsViewModel : ViewModel {
    private val exampleSentenceSource: ExampleSentenceSource
    private val imageSearchSource: ImageSearchSource
    private val injectedScope: CoroutineScope?
    private val workScope: CoroutineScope
        get() = injectedScope ?: viewModelScope

    @Inject
    constructor(
        exampleSentenceSource: ExampleSentenceSource,
        imageSearchSource: ImageSearchSource,
    ) : super() {
        this.exampleSentenceSource = exampleSentenceSource
        this.imageSearchSource = imageSearchSource
        this.injectedScope = null
    }

    internal constructor(
        exampleSentenceSource: ExampleSentenceSource,
        imageSearchSource: ImageSearchSource,
        coroutineScope: CoroutineScope,
    ) : super() {
        this.exampleSentenceSource = exampleSentenceSource
        this.imageSearchSource = imageSearchSource
        this.injectedScope = coroutineScope
    }

    private val _uiState = MutableStateFlow(MineWithOptionsUiState())
    val uiState: StateFlow<MineWithOptionsUiState> = _uiState.asStateFlow()

    private var loadedTerm: String? = null
    private var loadJob: Job? = null
    // Bumped each start(); a late coroutine from a superseded term won't write stale state.
    private var generation = 0

    fun start(term: String) {
        if (loadedTerm == term) return
        loadedTerm = term
        loadJob?.cancel()
        val gen = ++generation
        _uiState.value = MineWithOptionsUiState(term = term, loading = true, imageLoading = true)
        loadJob = workScope.launch {
            launch {
                val candidates = loadOrEmpty { exampleSentenceSource.candidates(term) }
                if (gen == generation) _uiState.update { it.copy(loading = false, candidates = candidates) }
            }
            launch {
                val images = loadOrEmpty { imageSearchSource.candidates(term) }
                if (gen == generation) _uiState.update { it.copy(imageLoading = false, imageCandidates = images) }
            }
        }
    }

    // Cancellation must propagate (structured concurrency); other failures degrade to empty
    // so the loading flag still clears.
    private suspend fun <T> loadOrEmpty(block: suspend () -> List<T>): List<T> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
}
