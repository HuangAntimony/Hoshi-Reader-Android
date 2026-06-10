package moe.antimony.hoshi.dictionary

import de.manhhao.hoshi.DictionaryStyle
import de.manhhao.hoshi.HoshiDicts
import de.manhhao.hoshi.LookupResult
import javax.inject.Inject
import javax.inject.Singleton

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
    fun importDictionary(zipPath: String, outputDir: String, lowRam: Boolean): NativeDictionaryImportResult

    fun createLookupObject(): Long = 0L

    fun destroyLookupObject(session: Long) = Unit

    fun rebuildQuery(
        session: Long,
        termPaths: Array<String>,
        freqPaths: Array<String>,
        pitchPaths: Array<String>,
    ) = Unit

    fun lookup(session: Long, text: String, maxResults: Int, scanLength: Int): List<LookupResult> = emptyList()

    fun getStyles(session: Long): List<DictionaryStyle> = emptyList()

    fun getMediaFile(session: Long, dictionary: String, path: String): ByteArray? = null
}

@Singleton
internal class HoshiDictionaryNativeBridge @Inject constructor() : DictionaryNativeBridge {
    override fun importDictionary(zipPath: String, outputDir: String, lowRam: Boolean): NativeDictionaryImportResult =
        HoshiDicts.importDictionary(zipPath, outputDir, lowRam).let { result ->
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

    override fun createLookupObject(): Long =
        HoshiDicts.createLookupObject()

    override fun destroyLookupObject(session: Long) {
        HoshiDicts.destroyLookupObject(session)
    }

    override fun rebuildQuery(
        session: Long,
        termPaths: Array<String>,
        freqPaths: Array<String>,
        pitchPaths: Array<String>,
    ) {
        HoshiDicts.rebuildQuery(session, termPaths, freqPaths, pitchPaths)
    }

    override fun lookup(session: Long, text: String, maxResults: Int, scanLength: Int): List<LookupResult> =
        HoshiDicts.lookup(session, text, maxResults, scanLength).toList()

    override fun getStyles(session: Long): List<DictionaryStyle> =
        HoshiDicts.getStyles(session).toList()

    override fun getMediaFile(session: Long, dictionary: String, path: String): ByteArray? =
        HoshiDicts.getMediaFile(session, dictionary, path)
}
