package moe.antimony.hoshi.features.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
