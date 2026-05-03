package moe.antimony.hoshi.dictionary

import de.manhhao.hoshi.HoshiDicts

internal interface DictionaryNativeBridge {
    fun importDictionary(zipPath: String, outputDir: String): Boolean

    fun rebuildQuery(
        termPaths: Array<String>,
        freqPaths: Array<String>,
        pitchPaths: Array<String>,
    )
}

internal object HoshiDictionaryNativeBridge : DictionaryNativeBridge {
    override fun importDictionary(zipPath: String, outputDir: String): Boolean =
        HoshiDicts.importDictionary(zipPath, outputDir).success

    override fun rebuildQuery(
        termPaths: Array<String>,
        freqPaths: Array<String>,
        pitchPaths: Array<String>,
    ) {
        HoshiDicts.rebuildQuery(HoshiDicts.lookupObject, termPaths, freqPaths, pitchPaths)
    }
}
