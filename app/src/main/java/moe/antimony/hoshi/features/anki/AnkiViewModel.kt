package moe.antimony.hoshi.features.anki

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

data class AnkiUiState(
    val settings: AnkiSettings = AnkiSettings(),
    val decks: List<AnkiDeck> = emptyList(),
    val noteTypes: List<AnkiNoteType> = emptyList(),
    val isFetching: Boolean = false,
    val errorMessage: String? = null,
) {
    val selectedNoteType: AnkiNoteType?
        get() = noteTypes.firstOrNull { it.id == settings.selectedNoteTypeId }
            ?: settings.selectedNoteTypeName?.let { name -> noteTypes.firstOrNull { it.name == name } }

    val isConfigured: Boolean
        get() = settings.selectedDeckId != null && settings.selectedNoteTypeId != null

    val popupSettings: AnkiPopupSettings
        get() = AnkiPopupSettings(
            isConfigured = isConfigured,
            needsAudio = settings.fieldMappings.values.contains("{audio}"),
            allowDupes = settings.allowDupes,
            compactGlossaries = settings.compactGlossaries,
        )
}

class AnkiViewModel(
    private val repository: AnkiRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AnkiUiState())
    val uiState: StateFlow<AnkiUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.settings.collectLatest { settings ->
                _uiState.value = _uiState.value.copy(settings = settings)
            }
        }
    }

    fun fetchConfiguration() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isFetching = true, errorMessage = null)
            when (val result = repository.fetchConfiguration()) {
                is AnkiFetchResult.Success -> _uiState.value = _uiState.value.copy(
                    decks = result.decks,
                    noteTypes = result.noteTypes,
                    isFetching = false,
                )
                is AnkiFetchResult.Error -> _uiState.value = _uiState.value.copy(
                    isFetching = false,
                    errorMessage = result.message,
                )
            }
        }
    }

    fun selectDeck(deck: AnkiDeck) {
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(selectedDeckId = deck.id, selectedDeckName = deck.name)
            }
        }
    }

    fun selectNoteType(noteType: AnkiNoteType) {
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    selectedNoteTypeId = noteType.id,
                    selectedNoteTypeName = noteType.name,
                    fieldMappings = LapisPreset.applyDefaults(noteType, emptyMap()),
                )
            }
        }
    }

    fun updateFieldMapping(field: String, value: String) {
        viewModelScope.launch {
            repository.updateSettings { settings ->
                val trimmed = value.trim()
                val mappings = if (trimmed.isEmpty()) {
                    settings.fieldMappings - field
                } else {
                    settings.fieldMappings + (field to value)
                }
                settings.copy(fieldMappings = mappings)
            }
        }
    }

    fun updateTags(tags: String) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(tags = tags) }
        }
    }

    fun updateAllowDupes(value: Boolean) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(allowDupes = value) }
        }
    }

    fun updateCompactGlossaries(value: Boolean) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(compactGlossaries = value) }
        }
    }

    fun mineEntry(rawPayload: String, context: AnkiMiningContext): Boolean =
        runBlocking {
            repository.mineEntry(
                rawPayload = rawPayload,
                context = context,
                decks = _uiState.value.decks,
                noteTypes = _uiState.value.noteTypes,
            )
        }

    fun duplicateCheck(expression: String): Boolean =
        runBlocking {
            repository.isDuplicate(expression, _uiState.value.noteTypes)
        }
}
