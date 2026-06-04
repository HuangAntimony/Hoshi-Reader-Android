package moe.antimony.hoshi.features.dictionary

import androidx.lifecycle.ViewModel
import de.manhhao.hoshi.LookupResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.dictionary.DictionaryRepository
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.features.audio.AudioSettingsRepository
import moe.antimony.hoshi.features.anki.AnkiPopupSettings
import moe.antimony.hoshi.features.reader.ReaderSelectionData
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText

internal interface DictionarySearchRepository {
    val dictionarySettings: Flow<DictionarySettings>
    val audioSettings: Flow<AudioSettings>
    suspend fun rebuildLookupQuery()
    fun lookup(query: String, maxResults: Int, scanLength: Int, language: String): List<LookupResult>
    fun dictionaryStyles(): Map<String, String>
}

internal class AndroidDictionarySearchRepository(
    private val dictionaryRepository: DictionaryRepository,
    dictionarySettingsRepository: DictionarySettingsRepository,
    audioSettingsRepository: AudioSettingsRepository,
) : DictionarySearchRepository {
    override val dictionarySettings: Flow<DictionarySettings> = dictionarySettingsRepository.settings
    override val audioSettings: Flow<AudioSettings> = audioSettingsRepository.settings

    override suspend fun rebuildLookupQuery() {
        dictionaryRepository.rebuildLookupQuery()
    }

    override fun lookup(query: String, maxResults: Int, scanLength: Int, language: String): List<LookupResult> =
        dictionaryRepository.lookup(query, maxResults, scanLength, language)

    override fun dictionaryStyles(): Map<String, String> =
        dictionaryRepository.dictionaryStyles()
}

internal class DictionarySearchViewModel(
    private val repository: DictionarySearchRepository,
    coroutineScope: CoroutineScope? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val ownedScope = if (coroutineScope == null) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    } else {
        null
    }
    private val scope = coroutineScope ?: ownedScope!!
    private val _uiState = MutableStateFlow(DictionarySearchUiState())

    val uiState: StateFlow<DictionarySearchUiState> = _uiState.asStateFlow()

    init {
        scope.launch {
            withContext(ioDispatcher) {
                runCatching { repository.rebuildLookupQuery() }
            }
        }
        scope.launch {
            repository.dictionarySettings.collect { settings ->
                _uiState.update { it.copy(dictionarySettings = settings) }
            }
        }
        scope.launch {
            repository.audioSettings.collect { settings ->
                _uiState.update { it.copy(audioSettings = settings) }
            }
        }
    }

    fun updateQuery(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    fun runLookup(
        assets: LookupPopupAssets? = null,
        darkMode: Boolean = false,
        eInkMode: Boolean = false,
        ankiSettings: AnkiPopupSettings = AnkiPopupSettings(),
        fontFaceCss: String = "",
        popupScale: Double = 1.0,
    ) {
        val query = _uiState.value.query
        val dictionarySettings = _uiState.value.dictionarySettings.normalized()
        val audioSettings = _uiState.value.audioSettings
        scope.launch {
            _uiState.update { it.copy(isSearching = true, errorMessage = null) }
            runCatching {
                withContext(ioDispatcher) {
                    val trimmed = query.trim()
                    if (trimmed.isEmpty()) {
                        DictionarySearchContent.runLookup(
                            query = query,
                            lookup = { error("lookup should not run for blank query") },
                            assets = assets,
                            dictionarySettings = dictionarySettings,
                            darkMode = darkMode,
                            eInkMode = eInkMode,
                            audioSettings = audioSettings,
                            ankiSettings = ankiSettings,
                            fontFaceCss = fontFaceCss,
                            popupScale = popupScale,
                        )
                    } else {
                        repository.rebuildLookupQuery()
                        val styles = repository.dictionaryStyles()
                        DictionarySearchContent.runLookup(
                            query = query,
                            lookup = {
                                repository.lookup(
                                    it,
                                    dictionarySettings.maxResults,
                                    dictionarySettings.scanLength,
                                    dictionarySettings.lookupLanguage.code,
                                )
                            },
                            assets = assets,
                            dictionaryStyles = styles,
                            dictionarySettings = dictionarySettings,
                            darkMode = darkMode,
                            eInkMode = eInkMode,
                            audioSettings = audioSettings,
                            ankiSettings = ankiSettings,
                            fontFaceCss = fontFaceCss,
                            popupScale = popupScale,
                        )
                    }
                }
            }.onSuccess { state ->
                _uiState.update {
                    it.copy(
                        lastQuery = state.lastQuery,
                        html = state.html,
                        results = state.results,
                        hasSearched = true,
                        isSearching = false,
                        errorMessage = null,
                        dictionaryStyles = state.dictionaryStyles,
                        popups = emptyList(),
                        resultClearSelectionSignal = 0,
                        backCount = 0,
                        forwardCount = 0,
                        backSignal = 0,
                        forwardSignal = 0,
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        lastQuery = query.trim(),
                        html = "",
                        results = emptyList(),
                        hasSearched = true,
                        isSearching = false,
                        errorMessage = error.localizedMessage?.let(UiText::Literal)
                            ?: UiText.Resource(R.string.dictionary_lookup_failed),
                        dictionaryStyles = emptyMap(),
                        popups = emptyList(),
                        resultClearSelectionSignal = 0,
                        backCount = 0,
                        forwardCount = 0,
                        backSignal = 0,
                        forwardSignal = 0,
                    )
                }
            }
        }
    }

    fun lookupRedirect(query: String): List<LookupResult> {
        val settings = _uiState.value.dictionarySettings.normalized()
        return repository.lookup(query, settings.maxResults, settings.scanLength, settings.lookupLanguage.code)
    }

    fun recordLookupRedirected(count: Int) {
        if (count <= 0) return
        _uiState.update {
            it.copy(
                backCount = it.backCount + 1,
                forwardCount = 0,
            )
        }
    }

    fun navigateBack() {
        _uiState.update {
            if (it.backCount <= 0) {
                it
            } else {
                it.copy(
                    backSignal = it.backSignal + 1,
                    backCount = it.backCount - 1,
                    forwardCount = it.forwardCount + 1,
                )
            }
        }
    }

    fun navigateForward() {
        _uiState.update {
            if (it.forwardCount <= 0) {
                it
            } else {
                it.copy(
                    forwardSignal = it.forwardSignal + 1,
                    forwardCount = it.forwardCount - 1,
                    backCount = it.backCount + 1,
                )
            }
        }
    }

    fun openRootPopup(
        selection: ReaderSelectionData,
        options: LookupPopupOptions,
    ): Int? {
        val (popup, highlightCount) = createPopup(selection, options) ?: run {
            closePopups()
            return null
        }
        _uiState.update { it.copy(popups = listOf(popup)) }
        return highlightCount
    }

    fun createPopup(
        selection: ReaderSelectionData,
        options: LookupPopupOptions,
    ): Pair<LookupPopupItem, Int>? =
        createLookupPopupItem(
            selection = selection,
            options = options,
            dictionaryStyles = _uiState.value.dictionaryStyles,
            lookup = repository::lookup,
        )

    fun setPopups(popups: List<LookupPopupItem>) {
        _uiState.update { it.copy(popups = popups) }
    }

    fun closePopups() {
        _uiState.update { it.copy(popups = emptyList()) }
    }

    fun dismissRootPopup() {
        _uiState.update {
            it.copy(
                popups = emptyList(),
                resultClearSelectionSignal = it.resultClearSelectionSignal + 1,
            )
        }
    }

    override fun onCleared() {
        ownedScope?.cancel()
        super.onCleared()
    }
}
