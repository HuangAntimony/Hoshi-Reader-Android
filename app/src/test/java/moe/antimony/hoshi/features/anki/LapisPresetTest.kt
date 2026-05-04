package moe.antimony.hoshi.features.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LapisPresetTest {
    @Test
    fun detectsLapisFieldsAndBuildsIosCompatibleDefaultMappings() {
        val noteType = AnkiNoteType(
            id = 10L,
            name = "Lapis",
            fields = listOf(
                "Expression",
                "ExpressionFurigana",
                "ExpressionReading",
                "ExpressionAudio",
                "SelectionText",
                "MainDefinition",
                "DefinitionPicture",
                "Sentence",
                "SentenceAudio",
                "Picture",
                "Glossary",
                "PitchPosition",
                "PitchCategories",
                "Frequency",
                "FreqSort",
                "MiscInfo",
                "IsWordAndSentenceCard",
            ),
        )

        assertTrue(LapisPreset.matches(noteType))
        assertEquals(
            mapOf(
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
            LapisPreset.defaultMappings(noteType),
        )
    }

    @Test
    fun preservesUserMappingsWhenApplyingLapisDefaults() {
        val noteType = AnkiNoteType(
            id = 10L,
            name = "Lapis",
            fields = listOf("Expression", "MainDefinition"),
        )

        val merged = LapisPreset.applyDefaults(
            noteType = noteType,
            currentMappings = mapOf("MainDefinition" to "{single-glossary-JMdict}"),
        )

        assertEquals("{expression}", merged.getValue("Expression"))
        assertEquals("{single-glossary-JMdict}", merged.getValue("MainDefinition"))
    }

    @Test
    fun preservesLegacySelectedGlossaryMappingWithoutMigration() {
        val noteType = AnkiNoteType(
            id = 10L,
            name = "Lapis",
            fields = listOf("Expression", "MainDefinition"),
        )

        val merged = LapisPreset.applyDefaults(
            noteType = noteType,
            currentMappings = mapOf("MainDefinition" to "{selected-glossary}"),
        )

        assertEquals("{selected-glossary}", merged.getValue("MainDefinition"))
    }

    @Test
    fun preservesLegacyDefinitionPictureMappingWithoutMigration() {
        val noteType = AnkiNoteType(
            id = 10L,
            name = "Lapis",
            fields = listOf("Expression", "DefinitionPicture", "Picture"),
        )

        val merged = LapisPreset.applyDefaults(
            noteType = noteType,
            currentMappings = mapOf("DefinitionPicture" to "{book-cover}"),
        )

        assertEquals("{book-cover}", merged.getValue("DefinitionPicture"))
        assertEquals("{book-cover}", merged.getValue("Picture"))
    }

    @Test
    fun doesNotMatchUnrelatedNoteTypes() {
        assertFalse(
            LapisPreset.matches(
                AnkiNoteType(id = 11L, name = "Basic", fields = listOf("Front", "Back")),
            ),
        )
    }
}
