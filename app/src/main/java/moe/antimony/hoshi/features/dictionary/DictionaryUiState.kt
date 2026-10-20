package moe.antimony.hoshi.features.dictionary

import moe.antimony.hoshi.dictionary.DictionaryInfo
import moe.antimony.hoshi.dictionary.DictionaryType
import moe.antimony.hoshi.dictionary.DictionaryUpdateCandidate
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.features.settings.SettingsLoadState
import moe.antimony.hoshi.features.settings.map

internal data class DictionaryUiState(
    val profileId: String? = null,
    val settingsLoadState: SettingsLoadState<DictionarySettings> = SettingsLoadState.Loading,
    val dictionariesLoadState: SettingsLoadState<Map<DictionaryType, List<DictionaryInfo>>> = SettingsLoadState.Loading,
    val selectedType: DictionaryType = DictionaryType.Term,
    val dictionaries: Map<DictionaryType, List<DictionaryInfo>> = emptyMap(),
    val updatableDictionaries: List<DictionaryUpdateCandidate> = emptyList(),
    val settings: DictionarySettings = DictionarySettings(),
    val mutationOperation: DictionaryMutationOperation? = null,
    val isImporting: Boolean = false,
    val isUpdating: Boolean = false,
    val isInstallingStrokeOrderFont: Boolean = false,
    val strokeOrderFontInstalled: Boolean = false,
    val showBlockingProgress: Boolean = false,
    val currentImportMessage: UiText? = null,
    val errorMessage: UiText? = null,
) {
    val settingsPageState: SettingsLoadState<DictionaryUiState>
        get() = when (val dictionariesState = dictionariesLoadState) {
            is SettingsLoadState.Failed -> dictionariesState
            SettingsLoadState.Loading -> when (val settingsState = settingsLoadState) {
                is SettingsLoadState.Failed -> settingsState
                else -> SettingsLoadState.Loading
            }
            is SettingsLoadState.Loaded -> settingsLoadState.map { this }
        }

    val isMutationInProgress: Boolean
        get() = mutationOperation != null

    val currentDictionaries: List<DictionaryInfo>
        get() = dictionaries[selectedType].orEmpty()

    val showStrokeOrderFontDownload: Boolean
        get() = dictionaries[DictionaryType.Kanji].orEmpty().isNotEmpty()

    val canDownloadStrokeOrderFont: Boolean
        get() = showStrokeOrderFontDownload &&
            !strokeOrderFontInstalled &&
            !isInstallingStrokeOrderFont &&
            !isMutationInProgress &&
            !isImporting &&
            !isUpdating
}

internal data class DictionaryListLayout(
    val dictionaryStartGlobalIndex: Int,
    val showErrorDialog: Boolean,
) {
    companion object {
        fun from(errorMessage: UiText?): DictionaryListLayout =
            DictionaryListLayout(
                dictionaryStartGlobalIndex = 1,
                showErrorDialog = errorMessage != null,
            )
    }
}
