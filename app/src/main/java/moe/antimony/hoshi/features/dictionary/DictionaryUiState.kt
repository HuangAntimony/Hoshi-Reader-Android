package moe.antimony.hoshi.features.dictionary

import moe.antimony.hoshi.dictionary.DictionaryInfo
import moe.antimony.hoshi.dictionary.DictionaryType

internal data class DictionaryUiState(
    val selectedType: DictionaryType = DictionaryType.Term,
    val dictionaries: Map<DictionaryType, List<DictionaryInfo>> = emptyMap(),
    val settings: DictionarySettings = DictionarySettings(),
    val isImporting: Boolean = false,
    val errorMessage: String? = null,
) {
    val currentDictionaries: List<DictionaryInfo>
        get() = dictionaries[selectedType].orEmpty()
}
