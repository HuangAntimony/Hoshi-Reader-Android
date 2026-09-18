package moe.antimony.hoshi.features.display

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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText

internal data class DisplayPaletteDraft(
    val slot: DisplayPaletteSlot,
    val backgroundColor: Long,
    val textColor: Long,
    val infoColor: Long,
)

internal data class DisplayAccentDraft(
    val color: Long,
)

internal data class DisplaySettingsUiState(
    val settings: AppDisplaySettings? = null,
    val paletteDraft: DisplayPaletteDraft? = null,
    val accentDraft: DisplayAccentDraft? = null,
    val isSaving: Boolean = false,
    val error: UiText? = null,
)

@HiltViewModel
internal class DisplaySettingsViewModel internal constructor(
    private val settings: Flow<AppDisplaySettings>,
    private val updateSettings: suspend ((AppDisplaySettings) -> AppDisplaySettings) -> Unit,
    private val setAutoSwitchValue: suspend (Boolean, Boolean) -> Unit,
    private val selectPresetValue: suspend (DisplayPaletteSlot, DisplayPalettePreset) -> Unit,
    private val coroutineScope: CoroutineScope?,
) : ViewModel() {
    @Inject constructor(repository: AppDisplaySettingsRepository) : this(
        settings = repository.settings,
        updateSettings = repository::update,
        setAutoSwitchValue = repository::setAutoSwitch,
        selectPresetValue = repository::selectPreset,
        coroutineScope = null,
    )

    private val scope: CoroutineScope get() = coroutineScope ?: viewModelScope
    private val _uiState = MutableStateFlow(DisplaySettingsUiState())
    val uiState = _uiState.asStateFlow()
    private var loadJob: Job? = null

    init {
        retryLoad()
    }

    fun retryLoad() {
        loadJob?.cancel()
        _uiState.update { it.copy(error = null) }
        loadJob = scope.launch {
            try {
                settings.collect { confirmed ->
                    _uiState.update { it.copy(settings = confirmed) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(error = UiText.Resource(R.string.display_settings_load_failed)) }
            }
        }
    }

    fun setAutoSwitch(enabled: Boolean, systemDark: Boolean) = save {
        setAutoSwitchValue(enabled, systemDark)
    }

    fun setEInkMode(enabled: Boolean) = save {
        updateSettings { it.copy(eInkMode = enabled) }
    }

    fun setEInkDarkTheme(dark: Boolean) = save {
        updateSettings { it.copy(eInkDarkTheme = dark) }
    }

    fun selectPalettePreset(slot: DisplayPaletteSlot, preset: DisplayPalettePreset) {
        if (preset == DisplayPalettePreset.Custom) {
            openPaletteEditor(slot)
        } else {
            save { selectPresetValue(slot, preset) }
        }
    }

    fun openPaletteEditor(slot: DisplayPaletteSlot) {
        val selection = _uiState.value.settings?.selection(slot) ?: return
        _uiState.update {
            it.copy(
                paletteDraft = DisplayPaletteDraft(
                    slot = slot,
                    backgroundColor = selection.customBackgroundColor,
                    textColor = selection.customTextColor,
                    infoColor = selection.customInfoColor,
                ),
                error = null,
            )
        }
    }

    fun updatePaletteDraft(
        backgroundColor: Long? = null,
        textColor: Long? = null,
        infoColor: Long? = null,
    ) {
        _uiState.update { state ->
            state.paletteDraft?.let { draft ->
                state.copy(
                    paletteDraft = draft.copy(
                        backgroundColor = backgroundColor ?: draft.backgroundColor,
                        textColor = textColor ?: draft.textColor,
                        infoColor = infoColor ?: draft.infoColor,
                    ),
                )
            } ?: state
        }
    }

    fun savePaletteDraft() {
        val draft = _uiState.value.paletteDraft ?: return
        save(
            onSuccess = { _uiState.update { it.copy(paletteDraft = null) } },
        ) {
            updateSettings { settings ->
                settings
                    .withCustomPalette(
                        slot = draft.slot,
                        backgroundColor = draft.backgroundColor,
                        textColor = draft.textColor,
                        infoColor = draft.infoColor,
                    )
                    .withSelectedPreset(draft.slot, DisplayPalettePreset.Custom)
            }
        }
    }

    fun dismissPaletteEditor() = _uiState.update { it.copy(paletteDraft = null) }

    fun selectSystemAccent() = save {
        updateSettings { it.copy(accentSource = DisplayAccentSource.System) }
    }

    fun selectAccentPreset(color: Long) = save {
        updateSettings {
            it.copy(
                accentSource = DisplayAccentSource.Custom,
                accentSeed = color.opaque(),
            )
        }
    }

    fun openAccentEditor() {
        val settings = _uiState.value.settings ?: return
        _uiState.update {
            it.copy(accentDraft = DisplayAccentDraft(settings.accentSeed.opaque()), error = null)
        }
    }

    fun updateAccentDraft(color: Long) = _uiState.update { state ->
        state.accentDraft?.let { state.copy(accentDraft = DisplayAccentDraft(color.opaque())) } ?: state
    }

    fun saveAccentDraft() {
        val draft = _uiState.value.accentDraft ?: return
        save(
            onSuccess = { _uiState.update { it.copy(accentDraft = null) } },
        ) {
            updateSettings {
                it.copy(
                    accentSource = DisplayAccentSource.Custom,
                    accentSeed = draft.color.opaque(),
                )
            }
        }
    }

    fun dismissAccentEditor() = _uiState.update { it.copy(accentDraft = null) }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    private fun save(onSuccess: () -> Unit = {}, operation: suspend () -> Unit) {
        if (_uiState.value.isSaving) return
        scope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                operation()
                onSuccess()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(error = UiText.Resource(R.string.display_settings_save_failed)) }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }
}

private fun Long.opaque(): Long = (this and 0x00FFFFFFL) or 0xFF000000L
