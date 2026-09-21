package de.manhhao.hoshi

class ImportResult(
    val success: Boolean,
    val title: String,
    val termCount: Long,
    val metaCount: Long,
    val freqCount: Long,
    val pitchCount: Long,
    val kanjiCount: Long,
    val mediaCount: Long,
    val error: String,
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

class Pitch(
    val position: Int,
    val pattern: String,
    val nasal: IntArray,
    val devoice: IntArray,
)

class PitchEntry(
    val dictName: String,
    val pitches: Array<Pitch>,
    val transcriptions: Array<String>,
)

class KanjiStat(
    val key: String,
    val value: String,
)

class KanjiEntry(
    val dictName: String,
    val onyomi: String,
    val kunyomi: String,
    val tags: String,
    val definitions: Array<String>,
    val stats: Array<KanjiStat>,
)

class KanjiResult(
    val character: String,
    val entries: Array<KanjiEntry>,
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

enum class TraceSource {
    ALGORITHM,
    DICTIONARY,
    BOTH,
}

class TraceCandidate(
    val deinflected: String,
    val preprocessorSteps: Int,
    val source: TraceSource,
    val trace: Array<TransformGroup>,
)

class LookupResult(
    val matched: String,
    val term: TermResult,
    val traceCandidates: Array<TraceCandidate>,
)

enum class LookupFrequencyOrder(val nativeValue: Int) { Auto(0), Ascending(1), Descending(2), Disabled(3) }

data class LookupOptions(
    val frequencyOrder: LookupFrequencyOrder = LookupFrequencyOrder.Auto,
    val frequencyDictionary: String? = null,
)

object HoshiDicts {
    init {
        System.loadLibrary("hoshidicts_jni")
    }

    external fun importDictionary(zipPath: String, outputDir: String, lowRam: Boolean = false): ImportResult
    external fun createLookupObject(languageId: String): Long
    external fun destroyLookupObject(session: Long)
    external fun rebuildQuery(
        session: Long,
        termPaths: Array<String>,
        freqPaths: Array<String>,
        pitchPaths: Array<String>,
        kanjiPaths: Array<String>,
    )

    fun lookup(session: Long, text: String, maxResults: Int, scanLength: Int, options: LookupOptions = LookupOptions()): Array<LookupResult> =
        lookupWithOptions(session, text, maxResults, scanLength, options.frequencyOrder.nativeValue, options.frequencyDictionary)

    private external fun lookupWithOptions(session: Long, text: String, maxResults: Int, scanLength: Int,
                                          frequencyOrder: Int, frequencyDictionary: String?): Array<LookupResult>
    external fun queryKanji(session: Long, kanji: String): KanjiResult
    external fun getStyles(session: Long): Array<DictionaryStyle>
    external fun getMediaFile(session: Long, dictName: String, mediaPath: String): ByteArray?
}
