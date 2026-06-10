package moe.antimony.hoshi.features.anki

object AnkiFieldTemplates {
    private val templates = mapOf(
        "Lapis" to mapOf(
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
        ),
        "Kiku" to mapOf(
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
        ),
        "Senren" to mapOf(
            "word" to "{expression}",
            "reading" to "{reading}",
            "sentence" to "{sentence}",
            "sentenceCard" to "x",
            "selectionText" to "{popup-selection-text}",
            "definition" to "{glossary-first}",
            "wordAudio" to "{audio}",
            "sentenceAudio" to "{sasayaki-audio}",
            "picture" to "{book-cover}",
            "glossary" to "{glossary}",
            "pitchPositions" to "{pitch-accent-positions}",
            "pitchCategories" to "{pitch-accent-categories}",
            "frequencies" to "{frequencies}",
            "freqSort" to "{frequency-harmonic-rank}",
            "miscInfo" to "{document-title}",
        ),
    )

    fun matches(noteType: AnkiNoteType): Boolean =
        templates.containsKey(noteType.name)

    fun defaultMappings(noteType: AnkiNoteType): Map<String, String> {
        val defaults = templates[noteType.name].orEmpty()
        return noteType.fields.mapNotNull { field ->
            defaults[field]?.let { field to it }
        }.toMap()
    }

    fun applyDefaultsIfUnmapped(
        noteType: AnkiNoteType,
        currentMappings: Map<String, String>,
    ): Map<String, String> {
        if (!matches(noteType)) return currentMappings
        if (noteType.fields.any { field -> currentMappings[field] != null }) return currentMappings
        return defaultMappings(noteType)
    }
}
