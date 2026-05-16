package moe.antimony.hoshi.dictionary

import de.manhhao.hoshi.HoshiDicts

internal data class NativeDictionaryImportResult(
    val success: Boolean,
    val title: String,
    val termCount: Long,
    val metaCount: Long,
    val freqCount: Long,
    val pitchCount: Long,
    val mediaCount: Long,
)

internal interface DictionaryNativeBridge {
    fun importDictionary(zipPath: String, outputDir: String): NativeDictionaryImportResult

    fun rebuildQuery(
        termPaths: Array<String>,
        freqPaths: Array<String>,
        pitchPaths: Array<String>,
    )
}

internal object HoshiDictionaryNativeBridge : DictionaryNativeBridge {
    override fun importDictionary(zipPath: String, outputDir: String): NativeDictionaryImportResult =
        HoshiDicts.importDictionary(zipPath, outputDir).let { result ->
            NativeDictionaryImportResult(
                success = result.success,
                title = result.title,
                termCount = result.termCount,
                metaCount = result.metaCount,
                freqCount = result.freqCount,
                pitchCount = result.pitchCount,
                mediaCount = result.mediaCount,
            )
        }

    override fun rebuildQuery(
        termPaths: Array<String>,
        freqPaths: Array<String>,
        pitchPaths: Array<String>,
    ) {
        HoshiDicts.rebuildQuery(HoshiDicts.lookupObject, termPaths, freqPaths, pitchPaths)
    }
}
