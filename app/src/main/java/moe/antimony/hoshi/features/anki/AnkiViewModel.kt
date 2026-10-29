package moe.antimony.hoshi.features.anki

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.settings.SettingsLoadState
import moe.antimony.hoshi.features.settings.map
import moe.antimony.hoshi.features.settings.settingsLoadStates
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.withErrorDetails

data class AnkiUiState(
    val profileId: String? = null,
    val settingsLoadState: SettingsLoadState<AnkiSettings> = SettingsLoadState.Loading,
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
    val excludedDictionaries: List<String> = emptyList(),
) {
    internal val settingsPageState: SettingsLoadState<AnkiUiState>
        get() = settingsLoadState.map { this }

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

    fun usesHandlebar(formatId: String?, handlebar: String): Boolean {
        val formats = settings.effectiveCardFormats()
        val format = (if (formatId == null) formats.firstOrNull() else formats.firstOrNull { it.id == formatId }) ?: return false
        val noteType = availableNoteTypes.firstOrNull { it.id == format.selectedNoteTypeId || it.name == format.selectedNoteTypeName }
        val mappings = noteType?.let(format.fieldMappings::activeAnkiFieldMappings) ?: format.fieldMappings
        return mappings.values.any { handlebar in it }
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
                needsSentenceAudio = activeMappings.any { "{sentence-audio}" in it },
                allowDupes = settings.allowDupes,
                preventAccidentalDuplicates = settings.allowDupes && settings.preventAccidentalDuplicates,
                compactGlossaries = settings.compactGlossaries,
                disableShowNotes = settings.disableShowNotes,
                excludedDictionaries = excludedDictionaries,
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

internal data class AnkiFetchRequest(
    val profileId: String,
    val identity: AnkiConnectionIdentity,
    val generation: Long,
)

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
    private val settingsRetry = MutableStateFlow(0)
    private var activeProfileId: String? = null
    private var configurationJob: Job? = null
    private var connectionJob: Job? = null
    private var exclusionsJob: Job? = null
    private var fetchRequestGeneration = 0L

    init {
        viewModelScope.launch {
            combine(repository.profileIds, settingsRetry) { profileId, _ -> profileId }.onEach { profileId ->
                if (activeProfileId != profileId) {
                    invalidateFetchRequests()
                    connectionJob?.cancel()
                    activeProfileId = profileId
                    attemptedAnkiConnectPing = false
                    ankiConnectIdentity = null
                    _uiState.value = AnkiUiState(
                        profileId = profileId,
                        isAnkiDroidAvailable = repository.isAnkiDroidAvailable(),
                    )
                } else {
                    _uiState.update { it.copy(settingsLoadState = SettingsLoadState.Loading) }
                }
                exclusionsJob?.cancel()
                exclusionsJob = viewModelScope.launch {
                    repository.excludedDictionaries.catch { error ->
                        if (error is CancellationException) throw error
                    }.collect { dictionaries ->
                        if (activeProfileId == profileId) {
                            _uiState.update { it.copy(excludedDictionaries = dictionaries) }
                        }
                    }
                }
            }.collectLatest { profileId ->
                repository.settings.settingsLoadStates().collectLatest settings@ { loadState ->
                    if (activeProfileId != profileId) return@settings
                    val settings = (loadState as? SettingsLoadState.Loaded)?.value
                    if (settings == null) {
                        _uiState.update { it.copy(settingsLoadState = loadState) }
                        return@settings
                    }
                    if (_uiState.value.settings.connectionIdentity != settings.connectionIdentity) {
                        invalidateFetchRequests()
                        _uiState.update { it.copy(decks = emptyList(), noteTypes = emptyList(), errorMessage = null, errorAction = null) }
                    }
                    val nextIdentity = settings.ankiConnectUrl to settings.ankiConnectApiKey
                    if (settings.backendKind == AnkiBackendKind.AnkiConnect && ankiConnectIdentity != nextIdentity) {
                        ankiConnectIdentity = nextIdentity
                        attemptedAnkiConnectPing = false
                        connectionJob?.cancel()
                        _uiState.value = _uiState.value.copy(isAnkiConnectReachable = false, isConnectingAnkiConnect = false)
                    } else if (settings.backendKind == AnkiBackendKind.AnkiDroid) {
                        ankiConnectIdentity = null
                        attemptedAnkiConnectPing = false
                        connectionJob?.cancel()
                        _uiState.value = _uiState.value.copy(isAnkiConnectReachable = false, isConnectingAnkiConnect = false)
                    }
                    _uiState.update { it.copy(
                        settings = settings, settingsLoadState = loadState,
                        isAnkiDroidAvailable = repository.isAnkiDroidAvailable(settings.ankiDroidClient),
                    ) }
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
    }

    fun retrySettings() {
        settingsRetry.update { it + 1 }
    }

    fun createFetchRequest(): AnkiFetchRequest? {
        val state = _uiState.value
        if (state.settingsLoadState !is SettingsLoadState.Loaded) return null
        return AnkiFetchRequest(activeProfileId ?: return null, state.settings.connectionIdentity, fetchRequestGeneration)
    }

    fun isFetchRequestCurrent(request: AnkiFetchRequest): Boolean =
        request == createFetchRequest()

    fun onFetchPermissionResult(request: AnkiFetchRequest, granted: Boolean) {
        if (!isFetchRequestCurrent(request)) return
        if (granted) fetchConfiguration(request) else showFetchPermissionDenied()
    }

    private fun invalidateFetchRequests() {
        fetchRequestGeneration++
        configurationJob?.cancel()
        _uiState.update { it.copy(isFetching = false) }
    }

    fun updateAnkiDroidClient(client: AnkiDroidClient) {
        invalidateFetchRequests()
        viewModelScope.launch { repository.updateSettings { it.withAnkiDroidClient(client) } }
    }

    fun fetchConfiguration(request: AnkiFetchRequest? = createFetchRequest()) {
        if (request == null || !isFetchRequestCurrent(request)) return
        configurationJob?.cancel()
        configurationJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isFetching = true, errorMessage = null, errorAction = null)
            val result = try {
                repository.fetchConfiguration(request.profileId, request.identity)
            } catch (error: CancellationException) {
                if (isFetchRequestCurrent(request)) _uiState.update { it.copy(isFetching = false) }
                throw error
            }
            if (!isFetchRequestCurrent(request)) return@launch
            when (result) {
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

    fun isAnkiDroidAvailable(): Boolean = repository.isAnkiDroidAvailable(_uiState.value.settings.ankiDroidClient).also { available ->
        _uiState.update { it.copy(isAnkiDroidAvailable = available) }
    }

    fun showFetchApiUnavailable() {
        _uiState.value = _uiState.value.copy(
            isFetching = false,
            errorMessage = UiText.Resource(AnkiFetchFailure.ApiUnavailable.userMessageRes, _uiState.value.settings.ankiDroidClient.displayName),
            errorAction = null,
        )
    }

    fun showFetchPermissionDenied() {
        _uiState.value = _uiState.value.copy(
            isFetching = false,
            errorMessage = UiText.Resource(AnkiFetchFailure.PermissionDenied.userMessageRes, _uiState.value.settings.ankiDroidClient.displayName),
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
                        tags = DefaultAnkiTag,
                    ),
                )
            }
        }
    }

    fun duplicateCardFormat(formatId: String, name: String, onCreated: () -> Unit) {
        val newFormatId = UUID.randomUUID().toString()
        viewModelScope.launch {
            var created = false
            repository.updateSettings { settings ->
                val updated = settings.duplicateCardFormat(
                    sourceFormatId = formatId,
                    newFormatId = newFormatId,
                    newName = name,
                )
                created = updated != settings
                updated
            }
            if (created) onCreated()
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

    fun updatePreventAccidentalDuplicates(value: Boolean) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(preventAccidentalDuplicates = value) }
        }
    }

    fun updateBackendKind(value: AnkiBackendKind) {
        invalidateFetchRequests()
        viewModelScope.launch {
            repository.updateSettings { it.copy(backendKind = value) }
        }
    }

    fun updateAnkiConnectUrl(value: String) {
        invalidateFetchRequests()
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
        invalidateFetchRequests()
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
        val profileId = activeProfileId
        connectionJob?.cancel()
        connectionJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isConnectingAnkiConnect = true,
                ankiConnectMessage = null,
            )
            val result = repository.pingAnkiConnect()
            if (activeProfileId != profileId) return@launch
            when (result) {
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

    fun updateDisableShowNotes(value: Boolean) {
        viewModelScope.launch { repository.updateSettings { it.copy(disableShowNotes = value) } }
    }

    fun updateSelectedGlossaryFallback(value: String) {
        viewModelScope.launch { repository.updateSettings { it.copy(selectedGlossaryFallback = value) } }
    }

    fun updateShowAllHandlebars(value: Boolean) {
        viewModelScope.launch { repository.updateSettings { it.copy(showAllHandlebars = value) } }
    }

    suspend fun minePreparedEntry(
        formatId: String?,
        rawPayload: String,
        context: AnkiMiningContext,
        onWarning: (Throwable) -> Unit,
    ): Boolean {
        var synchronizationFailure: Throwable? = null
        val added = repository.mineEntry(
            rawPayload, context, _uiState.value.availableDecks, _uiState.value.availableNoteTypes, formatId,
            onSynchronizationFailure = { synchronizationFailure = it },
        )
        synchronizationFailure?.let { error ->
            reportPopupOperationFailure(error, onWarning, UiText.Resource(R.string.anki_sync_failed))
        }
        return added
    }

    fun mineEntryAsync(
        formatId: String?, rawPayload: String, context: AnkiMiningContext,
        onError: (Throwable) -> Unit,
        onWarning: (Throwable) -> Unit = onError,
        onResult: (Boolean) -> Unit,
    ) {
        viewModelScope.launch {
            val mined = runCatching {
                minePreparedEntry(formatId, rawPayload, context, onWarning)
            }.getOrElse { error -> reportPopupOperationFailure(error, onError) }
            onResult(mined)
        }
    }

    fun mineEntryAsync(rawPayload: String, context: AnkiMiningContext, onError: (Throwable) -> Unit, onResult: (Boolean) -> Unit) =
        mineEntryAsync(null, rawPayload, context, onError, onResult = onResult)

    fun duplicateStatesAsync(valuesByHandlebar: Map<String, String>, onResult: (Map<String, Boolean>) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching {
                repository.duplicateStates(valuesByHandlebar, _uiState.value.availableDecks, _uiState.value.availableNoteTypes)
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                ErrorDiagnosticEvents.buffer.record(UiText.Resource(R.string.anki_operation_failed), error)
                emptyMap()
            })
        }
    }

    fun showNotesAsync(formatId: String, valuesByHandlebar: Map<String, String>, onError: (Throwable) -> Unit, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching {
                repository.showNotes(formatId, valuesByHandlebar, _uiState.value.availableDecks, _uiState.value.availableNoteTypes)
            }.getOrElse { error -> reportPopupOperationFailure(error, onError) })
        }
    }

    private fun reportPopupOperationFailure(
        error: Throwable,
        onError: (Throwable) -> Unit,
        operation: UiText = UiText.Resource(R.string.anki_operation_failed),
    ): Boolean {
        if (error is CancellationException) throw error
        _uiState.update { it.copy(errorMessage = operation.withErrorDetails(error)) }
        onError(error)
        return false
    }

    fun duplicateCheckAsync(expression: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val isDuplicate = runCatching {
                repository.isDuplicate(
                    expression = expression,
                    decks = _uiState.value.availableDecks,
                    noteTypes = _uiState.value.availableNoteTypes,
                )
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                ErrorDiagnosticEvents.buffer.record(UiText.Resource(R.string.anki_operation_failed), error)
                false
            }
            onResult(isDuplicate)
        }
    }
}
