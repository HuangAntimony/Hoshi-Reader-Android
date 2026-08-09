package moe.antimony.hoshi.features.anki

import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
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

const val AnkiSettingsSchemaVersion = 2

@Serializable(with = AnkiFormatIconSerializer::class)
enum class AnkiFormatIcon {
    Square,
    SquareSmall,
    Circle,
    CircleSmall,
    Diamond,
    DiamondSmall,
}

object AnkiFormatIconSerializer : KSerializer<AnkiFormatIcon> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("AnkiFormatIcon", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: AnkiFormatIcon) {
        encoder.encodeString(value.storageValue)
    }

    override fun deserialize(decoder: Decoder): AnkiFormatIcon {
        val raw = decoder.decodeString()
        return AnkiFormatIcon.entries.firstOrNull { it.name == raw || it.storageValue == raw }
            ?: AnkiFormatIcon.Square
    }
}

private val AnkiFormatIcon.storageValue: String
    get() = when (this) {
        AnkiFormatIcon.Square -> "plus.square"
        AnkiFormatIcon.SquareSmall -> "plus.square.small"
        AnkiFormatIcon.Circle -> "plus.circle"
        AnkiFormatIcon.CircleSmall -> "plus.circle.small"
        AnkiFormatIcon.Diamond -> "plus.diamond"
        AnkiFormatIcon.DiamondSmall -> "plus.diamond.small"
    }

@Serializable
data class AnkiCardFormat(
    val id: String,
    val name: String,
    val icon: AnkiFormatIcon = AnkiFormatIcon.Square,
    val selectedDeckId: Long? = null,
    val selectedDeckName: String? = null,
    val selectedNoteTypeId: Long? = null,
    val selectedNoteTypeName: String? = null,
    val fieldMappings: Map<String, String> = emptyMap(),
    val tags: String = "",
)

@Serializable
data class AnkiSettings(
    val schemaVersion: Int = AnkiSettingsSchemaVersion,
    val cardFormats: List<AnkiCardFormat> = emptyList(),
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
    val disableShowNotes: Boolean = false,
    val selectedGlossaryFallback: String = "",
    val showAllHandlebars: Boolean = false,
    val ankiDroidForceSync: Boolean = false,
    val ankiConnectUrl: String = "",
    val ankiConnectApiKey: String = "",
    val ankiConnectForceSync: Boolean = false,
)

const val MaxAnkiCardFormats = 3

internal fun AnkiSettings.addCardFormat(format: AnkiCardFormat): AnkiSettings =
    if (cardFormats.size >= MaxAnkiCardFormats || cardFormats.any { it.id == format.id }) {
        this
    } else {
        copy(cardFormats = cardFormats + format)
    }

internal fun AnkiSettings.updateCardFormat(
    formatId: String,
    transform: (AnkiCardFormat) -> AnkiCardFormat,
): AnkiSettings {
    if (cardFormats.none { it.id == formatId }) return this
    return copy(cardFormats = cardFormats.map { format ->
        if (format.id == formatId) transform(format).copy(id = format.id) else format
    })
}

internal fun AnkiSettings.removeCardFormat(formatId: String): AnkiSettings =
    if (cardFormats.size <= 1 || cardFormats.none { it.id == formatId }) {
        this
    } else {
        copy(cardFormats = cardFormats.filterNot { it.id == formatId })
    }

data class AnkiSettingsDecodeResult(
    val settings: AnkiSettings,
    val didMigrate: Boolean,
)

internal fun decodeAnkiSettings(
    raw: String,
    newFormatId: () -> String,
): AnkiSettingsDecodeResult {
    val root = runCatching { ankiSettingsJson.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return AnkiSettingsDecodeResult(
            settings = AnkiSettings(cardFormats = listOf(defaultAnkiCardFormat(newFormatId()))),
            didMigrate = true,
        )
    val decoded = runCatching { ankiSettingsJson.decodeFromJsonElement(AnkiSettings.serializer(), root) }
        .getOrElse { AnkiSettings() }
    val hasVersionTwoFormats = root["schemaVersion"]?.jsonPrimitive?.contentOrNull == AnkiSettingsSchemaVersion.toString() &&
        root["cardFormats"] != null
    if (hasVersionTwoFormats && decoded.cardFormats.isNotEmpty()) {
        val seenIds = mutableSetOf<String>()
        val normalizedFormats = decoded.cardFormats.take(MaxAnkiCardFormats).map { format ->
            val id = format.id.takeIf { it.isNotBlank() && seenIds.add(it) }
                ?: newFormatId().also(seenIds::add)
            format.copy(id = id, name = format.name.ifBlank { "Default" })
        }
        val normalized = decoded.copy(cardFormats = normalizedFormats)
        return AnkiSettingsDecodeResult(normalized, didMigrate = normalized != decoded)
    }
    val format = if (hasVersionTwoFormats) {
        defaultAnkiCardFormat(newFormatId())
    } else {
        AnkiCardFormat(
            id = newFormatId(),
            name = "Default",
            selectedDeckId = decoded.selectedDeckId,
            selectedDeckName = decoded.selectedDeckName,
            selectedNoteTypeId = decoded.selectedNoteTypeId,
            selectedNoteTypeName = decoded.selectedNoteTypeName,
            fieldMappings = decoded.fieldMappings,
            tags = decoded.tags,
        )
    }
    return AnkiSettingsDecodeResult(
        settings = decoded.copy(
            schemaVersion = AnkiSettingsSchemaVersion,
            cardFormats = listOf(format),
        ),
        didMigrate = true,
    )
}

internal fun defaultAnkiCardFormat(id: String): AnkiCardFormat =
    AnkiCardFormat(
        id = id,
        name = "Default",
    )

private val ankiSettingsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

data class AnkiPopupFormat(
    val id: String,
    val icon: AnkiFormatIcon,
    val isValid: Boolean,
)

data class AnkiPopupSettings(
    val isConfigured: Boolean = false,
    val formats: List<AnkiPopupFormat> = emptyList(),
    val isBackendAvailable: Boolean = false,
    val useAnkiConnect: Boolean = false,
    val needsAudio: Boolean = false,
    val needsSasayakiAudio: Boolean = false,
    val allowDupes: Boolean = false,
    val compactGlossaries: Boolean = false,
    val disableShowNotes: Boolean = false,
    val embedMedia: Boolean = false,
)

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
    val pitchAccentGraphs: String = "",
    val phoneticTranscriptions: String = "",
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
                pitchAccentGraphs = root.string("pitchAccentGraphs"),
                phoneticTranscriptions = root.string("phoneticTranscriptions"),
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
        selectedGlossaryFallback: String = "",
    ): String = handlebarRegex.replace(template) { match ->
        handlebarToValue(match.value, payload, context, selectedGlossaryFallback)
    }

    private fun handlebarToValue(
        handlebar: String,
        payload: AnkiMiningPayload,
        context: AnkiMiningContext,
        selectedGlossaryFallback: String,
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
            "{selected-glossary}" -> payload.selectedGlossaryOrConfiguredFallback(
                context,
                selectedGlossaryFallback,
            )
            "{selected-glossary-fallback}" -> payload.selectedGlossaryOrFallback()
            "{selected-glossary-brief}" -> stripGlossaryHeaders(
                payload.selectedGlossaryOrConfiguredFallback(context, selectedGlossaryFallback),
            )
            "{selected-glossary-brief-fallback}" -> stripGlossaryHeaders(payload.selectedGlossaryOrFallback())
            "{selected-glossary-no-dictionary}" -> stripDictionaryName(
                payload.selectedGlossaryOrConfiguredFallback(context, selectedGlossaryFallback),
            )
            "{selected-glossary-no-dictionary-fallback}" -> stripDictionaryName(payload.selectedGlossaryOrFallback())
            "{popup-selection-text}" -> payload.popupSelectionText
            "{sentence}" -> sentenceValue(payload, context)
            "{cloze-prefix}" -> clozeParts(payload, context).prefix
            "{cloze-body}" -> clozeParts(payload, context).body
            "{cloze-suffix}" -> clozeParts(payload, context).suffix
            "{frequencies}" -> payload.frequenciesHtml
            "{frequency-harmonic-rank}" -> payload.freqHarmonicRank
            "{pitch-accent-positions}" -> payload.pitchPositions
            "{pitch-accent-categories}" -> payload.pitchCategories
            "{pitch-accent-graphs}" -> payload.pitchAccentGraphs
            "{pitch-accent-graphs-first}" -> firstPitchAccentGraph(payload.pitchAccentGraphs)
            "{phonetic-transcriptions}" -> payload.phoneticTranscriptions
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

    private fun AnkiMiningPayload.selectedGlossaryOrConfiguredFallback(
        context: AnkiMiningContext,
        selectedGlossaryFallback: String,
    ): String {
        val selected = singleGlossaryForDictionary(selectedDictionary)
        if (selected.isNotBlank()) return selected
        if (selectedGlossaryFallback in SelectedGlossaryHandlebars) return ""
        return handlebarToValue(
            handlebar = selectedGlossaryFallback,
            payload = this,
            context = context,
            selectedGlossaryFallback = "",
        )
    }

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
        val parts = clozeParts(payload, context)
        if (parts.body.isEmpty()) return context.sentence
        return "${parts.prefix}<b>${parts.body}</b>${parts.suffix}"
    }

    private fun clozeParts(payload: AnkiMiningPayload, context: AnkiMiningContext): ClozeParts {
        val matched = payload.matched.takeIf { it.isNotBlank() }
            ?: return ClozeParts(context.sentence, "", "")
        val offset = context.sentenceOffset
        val start = if (
            offset != null &&
            offset >= 0 &&
            offset + matched.length <= context.sentence.length &&
            context.sentence.regionMatches(offset, matched, 0, matched.length)
        ) {
            offset
        } else {
            context.sentence.indexOf(matched).takeIf { it >= 0 }
                ?: return ClozeParts(context.sentence, "", "")
        }
        return ClozeParts(
            prefix = context.sentence.substring(0, start),
            body = context.sentence.substring(start, start + matched.length),
            suffix = context.sentence.substring(start + matched.length),
        )
    }

    private fun firstPitchAccentGraph(html: String): String =
        Regex("""<svg\b[\s\S]*?</svg>""").find(html)?.value.orEmpty()

    private data class ClozeParts(
        val prefix: String,
        val body: String,
        val suffix: String,
    )

    private val SelectedGlossaryHandlebars = setOf(
        "{selected-glossary}",
        "{selected-glossary-brief}",
        "{selected-glossary-no-dictionary}",
        "{selected-glossary-fallback}",
        "{selected-glossary-brief-fallback}",
        "{selected-glossary-no-dictionary-fallback}",
    )
}
