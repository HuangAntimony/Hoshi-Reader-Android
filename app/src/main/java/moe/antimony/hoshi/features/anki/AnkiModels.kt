package moe.antimony.hoshi.features.anki

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class AnkiDeck(
    val id: Long,
    val name: String,
)

@Serializable
data class AnkiNoteType(
    val id: Long,
    val name: String,
    val fields: List<String>,
)

@Serializable
enum class AnkiBackendKind {
    AnkiDroid,
    AnkiConnect,
}

@Serializable
enum class AnkiDuplicateScope {
    Collection,
    Deck,
    DeckRoot,
}

@Serializable
data class AnkiSettings(
    val backendKind: AnkiBackendKind = AnkiBackendKind.AnkiDroid,
    val selectedDeckId: Long? = null,
    val selectedDeckName: String? = null,
    val selectedNoteTypeId: Long? = null,
    val selectedNoteTypeName: String? = null,
    val availableDecks: List<AnkiDeck> = emptyList(),
    val availableNoteTypes: List<AnkiNoteType> = emptyList(),
    val fieldMappings: Map<String, String> = emptyMap(),
    val tags: String = "",
    val allowDupes: Boolean = false,
    val checkDuplicatesAcrossAllModels: Boolean = false,
    val duplicateScope: AnkiDuplicateScope = AnkiDuplicateScope.Collection,
    val compactGlossaries: Boolean = false,
    val embedMedia: Boolean = true,
    val ankiDroidForceSync: Boolean = false,
    val ankiConnectUrl: String = "",
    val ankiConnectForceSync: Boolean = false,
)

data class AnkiPopupSettings(
    val isConfigured: Boolean = false,
    val useAnkiConnect: Boolean = false,
    val needsAudio: Boolean = false,
    val needsSasayakiAudio: Boolean = false,
    val allowDupes: Boolean = false,
    val compactGlossaries: Boolean = false,
) {
    val embedMedia: Boolean
        get() = isConfigured
}

internal fun Map<String, String>.referencesAnkiHandlebar(handlebar: String): Boolean =
    values.any { template -> handlebar in template }

internal fun Map<String, String>.activeAnkiFieldMappings(noteType: AnkiNoteType): Map<String, String> =
    noteType.fields.mapNotNull { field ->
        this[field]?.let { field to it }
    }.toMap()

@Serializable
data class DictionaryMedia(
    val dictionary: String,
    val path: String,
    val filename: String,
)

@Serializable
data class AnkiMiningPayload(
    val expression: String,
    val reading: String = "",
    val matched: String = "",
    val furiganaPlain: String = "",
    val frequenciesHtml: String = "",
    val freqHarmonicRank: String = "",
    val glossary: String = "",
    val glossaryFirst: String = "",
    val singleGlossaries: Map<String, String> = emptyMap(),
    val pitchPositions: String = "",
    val pitchCategories: String = "",
    val popupSelectionText: String = "",
    val audio: String = "",
    val selectedDictionary: String = "",
    val dictionaryMedia: List<DictionaryMedia> = emptyList(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(rawJson: String): AnkiMiningPayload {
            val root = json.parseToJsonElement(rawJson).jsonObject
            val singleGlossaries = root.string("singleGlossaries")
                .takeIf { it.isNotBlank() }
                ?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() }
                .orEmpty()
            val dictionaryMedia = root.string("dictionaryMedia")
                .takeIf { it.isNotBlank() }
                ?.let { runCatching { json.decodeFromString<List<DictionaryMedia>>(it) }.getOrNull() }
                .orEmpty()
            return AnkiMiningPayload(
                expression = root.string("expression"),
                reading = root.string("reading"),
                matched = root.string("matched"),
                furiganaPlain = root.string("furiganaPlain"),
                frequenciesHtml = root.string("frequenciesHtml"),
                freqHarmonicRank = root.string("freqHarmonicRank"),
                glossary = root.string("glossary"),
                glossaryFirst = root.string("glossaryFirst"),
                singleGlossaries = singleGlossaries,
                pitchPositions = root.string("pitchPositions"),
                pitchCategories = root.string("pitchCategories"),
                popupSelectionText = root.string("popupSelectionText"),
                audio = root.string("audio"),
                selectedDictionary = root.string("selectedDictionary"),
                dictionaryMedia = dictionaryMedia,
            )
        }

        private fun JsonObject.string(key: String): String =
            this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    }
}

data class AnkiMiningContext(
    val sentence: String,
    val documentTitle: String? = null,
    val coverPath: String? = null,
    val sasayakiAudioPath: String? = null,
    val sentenceOffset: Int? = null,
)

object AnkiHandlebarRenderer {
    private val handlebarRegex = Regex("\\{[^}]*\\}")
    private val glossaryHeaderRegex = Regex("""(<li data-dictionary="[^"]*">)<i>[^<]*</i> """)
    private val dictionaryLabelRegex = Regex("""<li data-dictionary="([^"]+)"><i>([^<]*)</i> """)
    private const val SingleGlossaryPrefix = "{single-glossary-"
    private const val BriefSuffix = "-brief"
    private const val NoDictionarySuffix = "-no-dictionary"

    fun render(
        template: String,
        payload: AnkiMiningPayload,
        context: AnkiMiningContext,
    ): String = handlebarRegex.replace(template) { match ->
        handlebarToValue(match.value, payload, context)
    }

    private fun handlebarToValue(
        handlebar: String,
        payload: AnkiMiningPayload,
        context: AnkiMiningContext,
    ): String {
        if (handlebar.startsWith(SingleGlossaryPrefix)) {
            return payload.singleGlossaryHandlebarValue(handlebar)
        }
        return when (handlebar) {
            "{expression}" -> payload.expression
            "{reading}" -> payload.reading
            "{furigana-plain}" -> payload.furiganaPlain
            "{audio}" -> payload.audio
            "{glossary}" -> payload.glossary
            "{glossary-brief}" -> stripGlossaryHeaders(payload.glossary)
            "{glossary-no-dictionary}" -> stripDictionaryName(payload.glossary)
            "{glossary-first}" -> payload.glossaryFirst
            "{glossary-first-brief}" -> stripGlossaryHeaders(payload.glossaryFirst)
            "{glossary-first-no-dictionary}" -> stripDictionaryName(payload.glossaryFirst)
            "{selected-glossary}" -> payload.singleGlossaryForDictionary(payload.selectedDictionary)
            "{selected-glossary-fallback}" -> payload.selectedGlossaryOrFallback()
            "{selected-glossary-brief}" -> stripGlossaryHeaders(
                payload.singleGlossaryForDictionary(payload.selectedDictionary),
            )
            "{selected-glossary-brief-fallback}" -> stripGlossaryHeaders(payload.selectedGlossaryOrFallback())
            "{selected-glossary-no-dictionary}" -> stripDictionaryName(
                payload.singleGlossaryForDictionary(payload.selectedDictionary),
            )
            "{selected-glossary-no-dictionary-fallback}" -> stripDictionaryName(payload.selectedGlossaryOrFallback())
            "{popup-selection-text}" -> payload.popupSelectionText
            "{sentence}" -> sentenceValue(payload, context)
            "{frequencies}" -> payload.frequenciesHtml
            "{frequency-harmonic-rank}" -> payload.freqHarmonicRank
            "{pitch-accent-positions}" -> payload.pitchPositions
            "{pitch-accent-categories}" -> payload.pitchCategories
            "{document-title}" -> context.documentTitle.orEmpty()
            "{book-cover}" -> context.coverPath.orEmpty()
            "{sasayaki-audio}" -> context.sasayakiAudioPath.orEmpty()
            else -> ""
        }
    }

    private fun AnkiMiningPayload.singleGlossaryHandlebarValue(handlebar: String): String {
        val dictionary = handlebar.removePrefix(SingleGlossaryPrefix).removeSuffix("}")
        return when {
            dictionary.endsWith(BriefSuffix) -> {
                val baseDictionary = dictionary.removeSuffix(BriefSuffix)
                stripGlossaryHeaders(singleGlossaryForDictionary(baseDictionary))
            }
            dictionary.endsWith(NoDictionarySuffix) -> {
                val baseDictionary = dictionary.removeSuffix(NoDictionarySuffix)
                stripDictionaryName(singleGlossaryForDictionary(baseDictionary))
            }
            else -> singleGlossaryForDictionary(dictionary)
        }
    }

    private fun AnkiMiningPayload.selectedGlossaryOrFallback(): String =
        singleGlossaryForDictionary(selectedDictionary).ifBlank { glossaryFirst }

    private fun AnkiMiningPayload.singleGlossaryForDictionary(dictionary: String): String {
        if (dictionary.isBlank()) return ""
        singleGlossaries[dictionary]?.let { return it }
        val normalizedDictionary = dictionary.normalizedDictionaryName()
        return singleGlossaries.entries.firstOrNull { (name, _) ->
            name.normalizedDictionaryName() == normalizedDictionary
        }?.value.orEmpty()
    }

    private fun stripGlossaryHeaders(html: String): String =
        glossaryHeaderRegex.replace(html) { match -> match.groupValues[1] }

    private fun stripDictionaryName(html: String): String =
        dictionaryLabelRegex.replace(html) { match ->
            val dict = match.groupValues[1]
            val label = match.groupValues[2]
            val stripped = label.replace(", $dict)", ")")
            if (stripped == "($dict)") {
                """<li data-dictionary="$dict">"""
            } else {
                """<li data-dictionary="$dict"><i>$stripped</i> """
            }
        }

    private fun String.normalizedDictionaryName(): String =
        trim().replace(Regex("""\s*\[[^]]+]\s*$"""), "")

    private fun sentenceValue(payload: AnkiMiningPayload, context: AnkiMiningContext): String {
        val matched = payload.matched.takeIf { it.isNotBlank() } ?: return context.sentence
        val offset = context.sentenceOffset
        if (
            offset != null &&
            offset >= 0 &&
            offset + matched.length <= context.sentence.length &&
            context.sentence.regionMatches(offset, matched, 0, matched.length)
        ) {
            return context.sentence.replaceRange(offset, offset + matched.length, "<b>$matched</b>")
        }
        return context.sentence.replaceFirst(matched, "<b>$matched</b>")
    }
}
