package moe.antimony.hoshi.features.dictionary

import moe.antimony.hoshi.dictionary.DictionaryInfo
import moe.antimony.hoshi.dictionary.DictionaryType
import moe.antimony.hoshi.dictionary.DictionaryUpdateCandidate
import moe.antimony.hoshi.ui.UiText

internal data class DictionaryUiState(
    val selectedType: DictionaryType = DictionaryType.Term,
    val dictionaries: Map<DictionaryType, List<DictionaryInfo>> = emptyMap(),
    val updatableDictionaries: List<DictionaryUpdateCandidate> = emptyList(),
    val settings: DictionarySettings = DictionarySettings(),
    val mutationOperation: DictionaryMutationOperation? = null,
    val isImporting: Boolean = false,
    val isUpdating: Boolean = false,
    val showBlockingProgress: Boolean = false,
    val currentImportMessage: UiText? = null,
    val errorMessage: UiText? = null,
) {
    val isMutationInProgress: Boolean
        get() = mutationOperation != null

    val currentDictionaries: List<DictionaryInfo>
        get() = dictionaries[selectedType].orEmpty()
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
