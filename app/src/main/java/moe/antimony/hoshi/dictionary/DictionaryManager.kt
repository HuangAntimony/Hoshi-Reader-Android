package moe.antimony.hoshi.dictionary

object DictionaryManager {
    fun collectDictionaries(
        storedDicts: List<DictionaryInfo>,
        configDicts: List<DictionaryConfig.DictionaryEntry>,
    ): List<DictionaryInfo> {
        val result = mutableListOf<DictionaryInfo>()
        configDicts.sortedBy { it.order }.forEach { config ->
            val stored = storedDicts.firstOrNull { it.path.name == config.fileName } ?: return@forEach
            result += stored.copy(
                isEnabled = config.isEnabled,
                order = config.order,
            )
        }

        val collectedFileNames = result.mapTo(mutableSetOf()) { it.path.name }
        storedDicts.forEach { stored ->
            if (stored.path.name !in collectedFileNames) {
                result += stored.copy(
                    isEnabled = true,
                    order = result.size,
                )
            }
        }
        return result
    }
}
