package moe.antimony.hoshi.features.anki

object LapisPreset {
    private val defaults = mapOf(
        "Expression" to "{expression}",
        "ExpressionFurigana" to "{furigana-plain}",
        "ExpressionReading" to "{reading}",
        "ExpressionAudio" to "{audio}",
        "SelectionText" to "{popup-selection-text}",
        "MainDefinition" to "{glossary-first}",
        "Sentence" to "{sentence}",
        "SentenceAudio" to "{sasayaki-audio}",
        "Picture" to "{book-cover}",
        "Glossary" to "{glossary}",
        "PitchPosition" to "{pitch-accent-positions}",
        "PitchCategories" to "{pitch-accent-categories}",
        "Frequency" to "{frequencies}",
        "FreqSort" to "{frequency-harmonic-rank}",
        "MiscInfo" to "{document-title}",
        "IsWordAndSentenceCard" to "x",
    )

    fun matches(noteType: AnkiNoteType): Boolean {
        val fields = noteType.fields.toSet()
        return noteType.name.contains("lapis", ignoreCase = true) ||
            listOf("Expression", "MainDefinition", "Sentence").all(fields::contains)
    }

    fun defaultMappings(noteType: AnkiNoteType): Map<String, String> =
        noteType.fields.mapNotNull { field ->
            defaults[field]?.let { field to it }
        }.toMap()

    fun applyDefaults(
        noteType: AnkiNoteType,
        currentMappings: Map<String, String>,
    ): Map<String, String> {
        if (!matches(noteType)) return currentMappings
        val migratedMappings = currentMappings.mapValues { (field, value) ->
            when {
                field == "MainDefinition" && value == "{selected-glossary}" -> "{glossary-first}"
                else -> value
            }
        }.toMutableMap()
        if (
            "Picture" in noteType.fields &&
            migratedMappings["DefinitionPicture"] == "{book-cover}" &&
            migratedMappings["Picture"].isNullOrBlank()
        ) {
            migratedMappings.remove("DefinitionPicture")
            migratedMappings["Picture"] = "{book-cover}"
        }
        return defaultMappings(noteType) + migratedMappings
    }
}
