package moe.antimony.hoshi.features.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.antimony.hoshi.features.anki.ExampleSentence
import moe.antimony.hoshi.features.anki.ExampleSentenceSource

internal data class MineWithOptionsUiState(
    val term: String = "",
    val loading: Boolean = false,
    val candidates: List<ExampleSentence> = emptyList(),
)

@HiltViewModel
internal class MineWithOptionsViewModel : ViewModel {
    private val exampleSentenceSource: ExampleSentenceSource
    private val injectedScope: CoroutineScope?
    private val workScope: CoroutineScope
        get() = injectedScope ?: viewModelScope

    @Inject
    constructor(exampleSentenceSource: ExampleSentenceSource) : super() {
        this.exampleSentenceSource = exampleSentenceSource
        this.injectedScope = null
    }

    internal constructor(
        exampleSentenceSource: ExampleSentenceSource,
        coroutineScope: CoroutineScope,
    ) : super() {
        this.exampleSentenceSource = exampleSentenceSource
        this.injectedScope = coroutineScope
    }

    private val _uiState = MutableStateFlow(MineWithOptionsUiState())
    val uiState: StateFlow<MineWithOptionsUiState> = _uiState.asStateFlow()

    private var loadedTerm: String? = null

    fun start(term: String) {
        if (loadedTerm == term) return
        loadedTerm = term
        _uiState.value = MineWithOptionsUiState(term = term, loading = true)
        workScope.launch {
            val candidates = exampleSentenceSource.candidates(term)
            _uiState.update { it.copy(loading = false, candidates = candidates) }
        }
    }
}
