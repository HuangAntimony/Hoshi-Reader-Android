package moe.antimony.hoshi.features.anki

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import java.util.UUID
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
    val isAnkiDroidAvailable: Boolean = false,
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
        get() = settings.effectiveCardFormats().any { format ->
            val deckExists = availableDecks.any { deck ->
                deck.id == format.selectedDeckId || format.selectedDeckName != null && deck.name == format.selectedDeckName
            }
            val noteType = availableNoteTypes.firstOrNull { type ->
                type.id == format.selectedNoteTypeId ||
                    format.selectedNoteTypeName != null && type.name == format.selectedNoteTypeName
            }
            deckExists && noteType?.fields?.firstOrNull()?.let { field ->
                !format.fieldMappings[field].isNullOrBlank()
            } == true
        }

    val popupSettings: AnkiPopupSettings
        get() {
            val backendAvailable = when (settings.backendKind) {
                AnkiBackendKind.AnkiDroid -> isAnkiDroidAvailable
                AnkiBackendKind.AnkiConnect -> isAnkiConnectReachable
            }
            val formats = settings.effectiveCardFormats().map { format ->
                val deckExists = availableDecks.any { deck ->
                    deck.id == format.selectedDeckId || format.selectedDeckName != null && deck.name == format.selectedDeckName
                }
                val noteType = availableNoteTypes.firstOrNull { type ->
                    type.id == format.selectedNoteTypeId ||
                        format.selectedNoteTypeName != null && type.name == format.selectedNoteTypeName
                }
                val firstFieldMapped = noteType?.fields?.firstOrNull()?.let { field ->
                    !format.fieldMappings[field].isNullOrBlank()
                } == true
                AnkiPopupFormat(
                    id = format.id,
                    icon = format.icon,
                    isValid = backendAvailable && deckExists && noteType != null && firstFieldMapped,
                )
            }
            val activeMappings = settings.effectiveCardFormats().flatMap { format ->
                val noteType = availableNoteTypes.firstOrNull { type ->
                    type.id == format.selectedNoteTypeId ||
                        format.selectedNoteTypeName != null && type.name == format.selectedNoteTypeName
                }
                (noteType?.let(format.fieldMappings::activeAnkiFieldMappings) ?: format.fieldMappings).values
            }
            return AnkiPopupSettings(
                isConfigured = isConfigured,
                formats = formats,
                isBackendAvailable = backendAvailable,
                useAnkiConnect = settings.backendKind == AnkiBackendKind.AnkiConnect,
                needsAudio = activeMappings.any { "{audio}" in it },
                needsSasayakiAudio = activeMappings.any { "{sasayaki-audio}" in it },
                allowDupes = settings.allowDupes,
                compactGlossaries = settings.compactGlossaries,
                disableShowNotes = settings.disableShowNotes,
                embedMedia = isConfigured && settings.embedMedia,
            )
        }
}

private fun AnkiSettings.effectiveCardFormats(): List<AnkiCardFormat> =
    cardFormats.ifEmpty {
        listOf(
            AnkiCardFormat(
                id = "legacy",
                name = "Default",
                selectedDeckId = selectedDeckId,
                selectedDeckName = selectedDeckName,
                selectedNoteTypeId = selectedNoteTypeId,
                selectedNoteTypeName = selectedNoteTypeName,
                fieldMappings = fieldMappings,
                tags = tags,
            ),
        )
    }

enum class AnkiErrorAction {
    OpenPermissionSettings,
}

@HiltViewModel
internal class AnkiViewModel @Inject constructor(
    private val repository: AnkiRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        AnkiUiState(isAnkiDroidAvailable = repository.isAnkiDroidAvailable()),
    )
    val uiState: StateFlow<AnkiUiState> = _uiState.asStateFlow()
    private var attemptedAnkiConnectPing = false
    private var ankiConnectIdentity: Pair<String, String>? = null

    init {
        viewModelScope.launch {
            repository.settings.collectLatest { settings ->
                val nextIdentity = settings.ankiConnectUrl to settings.ankiConnectApiKey
                if (settings.backendKind == AnkiBackendKind.AnkiConnect && ankiConnectIdentity != nextIdentity) {
                    ankiConnectIdentity = nextIdentity
                    attemptedAnkiConnectPing = false
                    _uiState.value = _uiState.value.copy(isAnkiConnectReachable = false)
                } else if (settings.backendKind == AnkiBackendKind.AnkiDroid) {
                    ankiConnectIdentity = null
                    attemptedAnkiConnectPing = false
                    _uiState.value = _uiState.value.copy(isAnkiConnectReachable = false)
                }
                _uiState.value = _uiState.value.copy(settings = settings)
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

    fun addCardFormat(name: String) {
        viewModelScope.launch {
            val deck = _uiState.value.availableDecks.firstOrNull { !it.name.equals("Default", ignoreCase = true) }
                ?: _uiState.value.availableDecks.firstOrNull()
            val noteType = _uiState.value.availableNoteTypes.firstOrNull()
            repository.updateSettings { settings ->
                settings.addCardFormat(
                    AnkiCardFormat(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        selectedDeckId = deck?.id,
                        selectedDeckName = deck?.name,
                        selectedNoteTypeId = noteType?.id,
                        selectedNoteTypeName = noteType?.name,
                        fieldMappings = noteType?.let(AnkiFieldTemplates::defaultMappings).orEmpty(),
                    ),
                )
            }
        }
    }

    fun removeCardFormat(formatId: String) {
        viewModelScope.launch {
            repository.updateSettings { it.removeCardFormat(formatId) }
        }
    }

    fun updateFormatName(formatId: String, name: String) = updateFormat(formatId) {
        it.copy(name = name.trim().ifBlank { it.name })
    }

    fun updateFormatIcon(formatId: String, icon: AnkiFormatIcon) = updateFormat(formatId) {
        it.copy(icon = icon)
    }

    fun selectDeck(formatId: String, deck: AnkiDeck) {
        viewModelScope.launch {
            repository.updateSettings {
                it.updateCardFormat(formatId) { format -> format.copy(
                    selectedDeckId = deck.id,
                    selectedDeckName = deck.name,
                ) }.copy(
                    availableDecks = (it.availableDecks + deck).distinctBy(AnkiDeck::id),
                )
            }
        }
    }

    fun selectNoteType(formatId: String, noteType: AnkiNoteType) {
        viewModelScope.launch {
            repository.updateSettings {
                it.updateCardFormat(formatId) { format -> format.copy(
                    selectedNoteTypeId = noteType.id,
                    selectedNoteTypeName = noteType.name,
                    fieldMappings = AnkiFieldTemplates.applyDefaultsIfUnmapped(noteType, format.fieldMappings),
                ) }.copy(
                    availableNoteTypes = (it.availableNoteTypes + noteType).distinctBy(AnkiNoteType::id),
                )
            }
        }
    }

    fun updateFieldMapping(formatId: String, field: String, value: String) {
        viewModelScope.launch {
            repository.updateSettings { settings ->
                settings.updateCardFormat(formatId) { format ->
                    val trimmed = value.trim()
                    val mappings = if (trimmed.isEmpty()) format.fieldMappings - field
                    else format.fieldMappings + (field to value)
                    format.copy(fieldMappings = mappings)
                }
            }
        }
    }

    fun updateTags(formatId: String, tags: String) = updateFormat(formatId) { it.copy(tags = tags) }

    private fun updateFormat(formatId: String, transform: (AnkiCardFormat) -> AnkiCardFormat) {
        viewModelScope.launch { repository.updateSettings { it.updateCardFormat(formatId, transform) } }
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

    fun updateAnkiConnectApiKey(value: String) {
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    ankiConnectApiKey = value,
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

    fun updateEmbedMedia(value: Boolean) {
        viewModelScope.launch { repository.updateSettings { it.copy(embedMedia = value) } }
    }

    fun updateDisableShowNotes(value: Boolean) {
        viewModelScope.launch { repository.updateSettings { it.copy(disableShowNotes = value) } }
    }

    fun updateSelectedGlossaryFallback(value: String) {
        viewModelScope.launch { repository.updateSettings { it.copy(selectedGlossaryFallback = value) } }
    }

    fun updateShowAllHandlebars(value: Boolean) {
        viewModelScope.launch { repository.updateSettings { it.copy(showAllHandlebars = value) } }
    }

    fun mineEntryAsync(formatId: String?, rawPayload: String, context: AnkiMiningContext, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val mined = runCatching {
                repository.mineEntry(
                    rawPayload = rawPayload,
                    context = context,
                    decks = _uiState.value.availableDecks,
                    noteTypes = _uiState.value.availableNoteTypes,
                    formatId = formatId,
                )
            }.getOrDefault(false)
            onResult(mined)
        }
    }

    fun mineEntryAsync(rawPayload: String, context: AnkiMiningContext, onResult: (Boolean) -> Unit) =
        mineEntryAsync(null, rawPayload, context, onResult)

    fun duplicateStatesAsync(valuesByHandlebar: Map<String, String>, onResult: (Map<String, Boolean>) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching {
                repository.duplicateStates(valuesByHandlebar, _uiState.value.availableDecks, _uiState.value.availableNoteTypes)
            }.getOrDefault(emptyMap()))
        }
    }

    fun showNotesAsync(formatId: String, valuesByHandlebar: Map<String, String>, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching {
                repository.showNotes(formatId, valuesByHandlebar, _uiState.value.availableDecks, _uiState.value.availableNoteTypes)
            }.getOrDefault(false))
        }
    }

    fun duplicateCheckAsync(expression: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val isDuplicate = runCatching {
                repository.isDuplicate(
                    expression = expression,
                    decks = _uiState.value.availableDecks,
                    noteTypes = _uiState.value.availableNoteTypes,
                )
            }.getOrDefault(false)
            onResult(isDuplicate)
        }
    }
}
