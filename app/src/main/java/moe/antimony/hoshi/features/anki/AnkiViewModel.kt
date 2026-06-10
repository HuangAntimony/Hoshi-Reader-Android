package moe.antimony.hoshi.features.anki

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText

data class AnkiUiState(
    val settings: AnkiSettings = AnkiSettings(),
    val decks: List<AnkiDeck> = emptyList(),
    val noteTypes: List<AnkiNoteType> = emptyList(),
    val isFetching: Boolean = false,
    val isConnectingAnkiConnect: Boolean = false,
    val isAnkiConnectReachable: Boolean = false,
    val ankiConnectMessage: UiText? = null,
    val errorMessage: UiText? = null,
    val errorAction: AnkiErrorAction? = null,
) {
    val availableDecks: List<AnkiDeck>
        get() = decks.ifEmpty { settings.availableDecks }

    val availableNoteTypes: List<AnkiNoteType>
        get() = noteTypes.ifEmpty { settings.availableNoteTypes }

    val selectedNoteType: AnkiNoteType?
        get() = availableNoteTypes.firstOrNull { it.id == settings.selectedNoteTypeId }
            ?: settings.selectedNoteTypeName?.let { name -> availableNoteTypes.firstOrNull { it.name == name } }

    val isConfigured: Boolean
        get() = settings.selectedDeckId != null && settings.selectedNoteTypeId != null

    val popupSettings: AnkiPopupSettings
        get() {
            val activeMappings = selectedNoteType?.let(settings.fieldMappings::activeAnkiFieldMappings)
                ?: settings.fieldMappings
            return AnkiPopupSettings(
                isConfigured = isConfigured,
                useAnkiConnect = settings.backendKind == AnkiBackendKind.AnkiConnect,
                needsAudio = activeMappings.referencesAnkiHandlebar("{audio}"),
                needsSasayakiAudio = activeMappings.referencesAnkiHandlebar("{sasayaki-audio}"),
                allowDupes = settings.allowDupes,
                compactGlossaries = settings.compactGlossaries,
            )
        }
}

enum class AnkiErrorAction {
    OpenPermissionSettings,
}

@HiltViewModel
internal class AnkiViewModel @Inject constructor(
    private val repository: AnkiRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AnkiUiState())
    val uiState: StateFlow<AnkiUiState> = _uiState.asStateFlow()
    private var attemptedRestoreFetch = false
    private var attemptedAnkiConnectPing = false

    init {
        viewModelScope.launch {
            repository.settings.collectLatest { settings ->
                _uiState.value = _uiState.value.copy(settings = settings)
                if (
                    !attemptedRestoreFetch &&
                    settings.selectedDeckId != null &&
                    settings.selectedNoteTypeId != null &&
                    (settings.availableDecks.isEmpty() || settings.availableNoteTypes.isEmpty())
                ) {
                    attemptedRestoreFetch = true
                    fetchConfiguration()
                }
                if (
                    !attemptedAnkiConnectPing &&
                    settings.backendKind == AnkiBackendKind.AnkiConnect &&
                    settings.ankiConnectUrl.isNotBlank()
                ) {
                    attemptedAnkiConnectPing = true
                    pingAnkiConnect()
                }
            }
        }
    }

    fun fetchConfiguration() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isFetching = true, errorMessage = null, errorAction = null)
            when (val result = repository.fetchConfiguration()) {
                is AnkiFetchResult.Success -> _uiState.value = _uiState.value.copy(
                    decks = result.decks,
                    noteTypes = result.noteTypes,
                    isFetching = false,
                    isAnkiConnectReachable = _uiState.value.settings.backendKind == AnkiBackendKind.AnkiConnect ||
                        _uiState.value.isAnkiConnectReachable,
                    errorAction = null,
                )
                is AnkiFetchResult.Error -> _uiState.value = _uiState.value.copy(
                    isFetching = false,
                    errorMessage = result.message,
                    errorAction = if (result.failure == AnkiFetchFailure.PermissionDenied) {
                        AnkiErrorAction.OpenPermissionSettings
                    } else {
                        null
                    },
                )
            }
        }
    }

    fun isAnkiDroidAvailable(): Boolean = repository.isAnkiDroidAvailable()

    fun showFetchApiUnavailable() {
        _uiState.value = _uiState.value.copy(
            isFetching = false,
            errorMessage = UiText.Resource(AnkiFetchFailure.ApiUnavailable.userMessageRes),
            errorAction = null,
        )
    }

    fun showFetchPermissionDenied() {
        _uiState.value = _uiState.value.copy(
            isFetching = false,
            errorMessage = UiText.Resource(AnkiFetchFailure.PermissionDenied.userMessageRes),
            errorAction = AnkiErrorAction.OpenPermissionSettings,
        )
    }

    fun selectDeck(deck: AnkiDeck) {
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    selectedDeckId = deck.id,
                    selectedDeckName = deck.name,
                    availableDecks = (it.availableDecks + deck).distinctBy(AnkiDeck::id),
                )
            }
        }
    }

    fun selectNoteType(noteType: AnkiNoteType) {
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    selectedNoteTypeId = noteType.id,
                    selectedNoteTypeName = noteType.name,
                    availableNoteTypes = (it.availableNoteTypes + noteType).distinctBy(AnkiNoteType::id),
                    fieldMappings = AnkiFieldTemplates.applyDefaultsIfUnmapped(noteType, it.fieldMappings),
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

    fun updateBackendKind(value: AnkiBackendKind) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(backendKind = value) }
        }
    }

    fun updateAnkiConnectUrl(value: String) {
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    ankiConnectUrl = value,
                    backendKind = AnkiBackendKind.AnkiConnect,
                )
            }
            _uiState.value = _uiState.value.copy(isAnkiConnectReachable = false, ankiConnectMessage = null)
        }
    }

    fun pingAnkiConnect() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isConnectingAnkiConnect = true,
                ankiConnectMessage = null,
            )
            when (val result = repository.pingAnkiConnect()) {
                AnkiConnectConnectionResult.Connected -> _uiState.value = _uiState.value.copy(
                    isConnectingAnkiConnect = false,
                    isAnkiConnectReachable = true,
                    ankiConnectMessage = UiText.Resource(R.string.anki_connect_connected),
                )
                is AnkiConnectConnectionResult.Error -> _uiState.value = _uiState.value.copy(
                    isConnectingAnkiConnect = false,
                    isAnkiConnectReachable = false,
                    ankiConnectMessage = result.message,
                )
            }
        }
    }

    fun updateCheckDuplicatesAcrossAllModels(value: Boolean) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(checkDuplicatesAcrossAllModels = value) }
        }
    }

    fun updateDuplicateScope(value: AnkiDuplicateScope) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(duplicateScope = value) }
        }
    }

    fun updateAnkiConnectForceSync(value: Boolean) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(ankiConnectForceSync = value) }
        }
    }

    fun updateAnkiDroidForceSync(value: Boolean) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(ankiDroidForceSync = value) }
        }
    }

    fun updateCompactGlossaries(value: Boolean) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(compactGlossaries = value) }
        }
    }

    fun mineEntryAsync(rawPayload: String, context: AnkiMiningContext, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val mined = runCatching {
                repository.mineEntry(
                    rawPayload = rawPayload,
                    context = context,
                    decks = _uiState.value.decks,
                    noteTypes = _uiState.value.noteTypes,
                )
            }.getOrDefault(false)
            onResult(mined)
        }
    }

    fun duplicateCheckAsync(expression: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val isDuplicate = runCatching {
                repository.isDuplicate(
                    expression = expression,
                    decks = _uiState.value.decks,
                    noteTypes = _uiState.value.noteTypes,
                )
            }.getOrDefault(false)
            onResult(isDuplicate)
        }
    }
}
