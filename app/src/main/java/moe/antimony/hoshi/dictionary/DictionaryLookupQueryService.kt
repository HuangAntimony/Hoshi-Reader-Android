package moe.antimony.hoshi.dictionary

import java.io.File

internal class DictionaryLookupQueryService(
    private val nativeBridge: DictionaryNativeBridge = HoshiDictionaryNativeBridge,
) {
    fun rebuild(
        termDictionaries: List<File>,
        frequencyDictionaries: List<File>,
        pitchDictionaries: List<File>,
    ) {
        nativeBridge.rebuildQuery(
            termPaths = termDictionaries.toAbsolutePathArray(),
            freqPaths = frequencyDictionaries.toAbsolutePathArray(),
            pitchPaths = pitchDictionaries.toAbsolutePathArray(),
        )
    }

    private fun List<File>.toAbsolutePathArray(): Array<String> =
        map { it.absolutePath }.toTypedArray()
}
