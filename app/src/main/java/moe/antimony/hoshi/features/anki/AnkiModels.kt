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
data class AnkiSettings(
    val selectedDeckId: Long? = null,
    val selectedDeckName: String? = null,
    val selectedNoteTypeId: Long? = null,
    val selectedNoteTypeName: String? = null,
    val availableDecks: List<AnkiDeck> = emptyList(),
    val availableNoteTypes: List<AnkiNoteType> = emptyList(),
    val fieldMappings: Map<String, String> = emptyMap(),
    val tags: String = "",
    val allowDupes: Boolean = false,
    val compactGlossaries: Boolean = false,
    val embedMedia: Boolean = true,
)

data class AnkiPopupSettings(
    val isConfigured: Boolean = false,
    val needsAudio: Boolean = false,
    val allowDupes: Boolean = false,
    val compactGlossaries: Boolean = false,
) {
    val embedMedia: Boolean
        get() = isConfigured
}

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
    val popupSelectionText: String? = null,
    val sentenceOffset: Int? = null,
)

object AnkiHandlebarRenderer {
    private val handlebarRegex = Regex("\\{[^}]*\\}")
    private const val SingleGlossaryPrefix = "{single-glossary-"

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
            val dictionary = handlebar.removePrefix(SingleGlossaryPrefix).removeSuffix("}")
            return payload.singleGlossaryForDictionary(dictionary)
        }
        return when (handlebar) {
            "{expression}" -> payload.expression
            "{reading}" -> payload.reading
            "{furigana-plain}" -> payload.furiganaPlain
            "{audio}" -> payload.audio
            "{glossary}" -> payload.glossary
            "{glossary-first}" -> payload.glossaryFirst
            "{selected-glossary}" -> payload.singleGlossaryForDictionary(payload.selectedDictionary)
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

    private fun AnkiMiningPayload.singleGlossaryForDictionary(dictionary: String): String {
        if (dictionary.isBlank()) return ""
        singleGlossaries[dictionary]?.let { return it }
        val normalizedDictionary = dictionary.normalizedDictionaryName()
        return singleGlossaries.entries.firstOrNull { (name, _) ->
            name.normalizedDictionaryName() == normalizedDictionary
        }?.value.orEmpty()
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
