package moe.antimony.hoshi.features.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json

class AnkiUiStateTest {
    @Test
    fun restoresEditableNoteTypeFromPersistedSettingsAfterProcessRestart() {
        val lapis = AnkiNoteType(
            id = 7L,
            name = "Lapis",
            fields = listOf("Expression", "Sentence", "Picture"),
        )
        val state = AnkiUiState(
            settings = AnkiSettings(
                selectedDeckId = 3L,
                selectedDeckName = "Mining",
                selectedNoteTypeId = lapis.id,
                selectedNoteTypeName = lapis.name,
                availableDecks = listOf(AnkiDeck(3L, "Mining")),
                availableNoteTypes = listOf(lapis),
                fieldMappings = mapOf("Expression" to "{expression}"),
            ),
            decks = emptyList(),
            noteTypes = emptyList(),
        )

        assertEquals(lapis, state.selectedNoteType)
        assertEquals(listOf(lapis), state.availableNoteTypes)
        assertTrue(state.isConfigured)
    }

    @Test
    fun oldPersistedAnkiSettingsDefaultToCollectionScopeAndSelectedModelOnly() {
        val settings = Json { ignoreUnknownKeys = true }
            .decodeFromString<AnkiSettings>("""{"allowDupes":false,"compactGlossaries":true}""")

        assertEquals(AnkiBackendKind.AnkiDroid, settings.backendKind)
        assertEquals(AnkiDuplicateScope.Collection, settings.duplicateScope)
        assertFalse(settings.checkDuplicatesAcrossAllModels)
        assertEquals("", settings.ankiConnectUrl)
        assertFalse(settings.ankiConnectForceSync)
        assertFalse(settings.ankiDroidForceSync)
    }

    @Test
    fun popupMediaNeedsFollowHandlebarsReferencedInsideTemplates() {
        val state = AnkiUiState(
            settings = AnkiSettings(
                fieldMappings = mapOf(
                    "Audio" to "<div>{audio}</div>",
                    "SentenceAudio" to "clip: {sasayaki-audio}",
                ),
            ),
        )

        assertTrue(state.popupSettings.needsAudio)
        assertTrue(state.popupSettings.needsSasayakiAudio)
    }

    @Test
    fun popupMediaNeedsIgnoreInactiveMappingsFromOtherNoteTypes() {
        val basic = AnkiNoteType(
            id = 5L,
            name = "Basic",
            fields = listOf("Front"),
        )
        val state = AnkiUiState(
            settings = AnkiSettings(
                selectedDeckId = 3L,
                selectedNoteTypeId = basic.id,
                selectedNoteTypeName = basic.name,
                availableNoteTypes = listOf(basic),
                fieldMappings = mapOf(
                    "Front" to "{expression}",
                    "ExpressionAudio" to "{audio}",
                    "SentenceAudio" to "{sasayaki-audio}",
                ),
            ),
        )

        assertFalse(state.popupSettings.needsAudio)
        assertFalse(state.popupSettings.needsSasayakiAudio)
    }

    @Test
    fun popupMediaNeedsAreOffWhenMediaHandlebarsAreAbsent() {
        val state = AnkiUiState(
            settings = AnkiSettings(fieldMappings = mapOf("Expression" to "{expression}")),
        )

        assertFalse(state.popupSettings.needsAudio)
        assertFalse(state.popupSettings.needsSasayakiAudio)
    }
}
