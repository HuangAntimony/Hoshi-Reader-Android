package de.manhhao.hoshi

class ImportResult(
    val success: Boolean,
    val title: String,
    val termCount: Long,
    val metaCount: Long,
    val freqCount: Long,
    val pitchCount: Long,
    val mediaCount: Long,
)

class DictionaryStyle(
    val dictName: String,
    val styles: String,
)

class Frequency(
    val value: Int,
    val displayValue: String,
)

class GlossaryEntry(
    val dictName: String,
    val glossary: String,
    val definitionTags: String,
    val termTags: String,
)

class FrequencyEntry(
    val dictName: String,
    val frequencies: Array<Frequency>,
)

class PitchEntry(
    val dictName: String,
    val pitchPositions: IntArray,
)

class TermResult(
    val expression: String,
    val reading: String,
    val rules: String,
    val glossaries: Array<GlossaryEntry>,
    val frequencies: Array<FrequencyEntry>,
    val pitches: Array<PitchEntry>,
)

class TransformGroup(
    val name: String,
    val description: String,
)

class LookupResult(
    val matched: String,
    val deinflected: String,
    val process: Array<TransformGroup>,
    val term: TermResult,
    val preprocessorSteps: Int,
)

object HoshiDicts {
    init {
        System.loadLibrary("hoshidicts_jni")
    }

    val lookupObject: Long = createLookupObject()

    external fun importDictionary(zipPath: String, outputDir: String, lowRam: Boolean = false): ImportResult
    external fun createLookupObject(): Long
    external fun destroyLookupObject(session: Long)
    external fun rebuildQuery(
        session: Long,
        termPaths: Array<String>,
        freqPaths: Array<String>,
        pitchPaths: Array<String>,
    )

    external fun setLookupLanguage(session: Long, language: String)
    external fun lookup(session: Long, text: String, maxResults: Int, scanLength: Int): Array<LookupResult>
    external fun getStyles(session: Long): Array<DictionaryStyle>
    external fun getMediaFile(session: Long, dictName: String, mediaPath: String): ByteArray?
}
