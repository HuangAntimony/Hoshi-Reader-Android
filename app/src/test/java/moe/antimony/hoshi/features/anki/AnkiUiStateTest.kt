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
        val settings = json.decodeFromString<AnkiSettings>("""{"allowDupes":false,"compactGlossaries":true}""")

        assertEquals(AnkiBackendKind.AnkiDroid, settings.backendKind)
        assertEquals(AnkiDuplicateScope.Collection, settings.duplicateScope)
        assertFalse(settings.checkDuplicatesAcrossAllModels)
        assertEquals("", settings.ankiConnectUrl)
        assertEquals("", settings.ankiConnectApiKey)
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

    @Test
    fun popupSettingsExposeEachFormatAndRequireTheFirstNoteFieldMapping() {
        val basic = AnkiNoteType(5L, "Basic", listOf("Front", "Back"))
        val state = AnkiUiState(
            settings = AnkiSettings(
                backendKind = AnkiBackendKind.AnkiDroid,
                availableDecks = listOf(AnkiDeck(3L, "Mining")),
                availableNoteTypes = listOf(basic),
                cardFormats = listOf(
                    AnkiCardFormat(
                        id = "valid",
                        name = "Word",
                        selectedDeckId = 3L,
                        selectedNoteTypeId = basic.id,
                        fieldMappings = mapOf("Front" to "{expression}"),
                    ),
                    AnkiCardFormat(
                        id = "missing-first-field",
                        name = "Sentence",
                        icon = AnkiFormatIcon.CircleSmall,
                        selectedDeckId = 3L,
                        selectedNoteTypeId = basic.id,
                        fieldMappings = mapOf("Back" to "{sentence}"),
                    ),
                ),
            ),
            isAnkiDroidAvailable = true,
        )

        assertEquals(
            listOf(
                AnkiPopupFormat("valid", AnkiFormatIcon.Square, isValid = true),
                AnkiPopupFormat("missing-first-field", AnkiFormatIcon.CircleSmall, isValid = false),
            ),
            state.popupSettings.formats,
        )
        assertTrue(state.popupSettings.isBackendAvailable)
    }

    @Test
    fun popupMediaNeedsAggregateOnlyActiveMappingsAcrossFormats() {
        val basic = AnkiNoteType(5L, "Basic", listOf("Front", "Media"))
        val state = AnkiUiState(
            settings = AnkiSettings(
                availableDecks = listOf(AnkiDeck(3L, "Mining")),
                availableNoteTypes = listOf(basic),
                cardFormats = listOf(
                    AnkiCardFormat(
                        id = "audio",
                        name = "Audio",
                        selectedDeckId = 3L,
                        selectedNoteTypeId = basic.id,
                        fieldMappings = mapOf("Front" to "{expression}", "Media" to "{audio}"),
                    ),
                    AnkiCardFormat(
                        id = "sasayaki",
                        name = "Sentence",
                        selectedDeckId = 3L,
                        selectedNoteTypeId = basic.id,
                        fieldMappings = mapOf(
                            "Front" to "{expression}",
                            "Media" to "{sasayaki-audio}",
                            "Stale" to "{book-cover}",
                        ),
                    ),
                ),
            ),
            isAnkiDroidAvailable = true,
        )

        assertTrue(state.popupSettings.needsAudio)
        assertTrue(state.popupSettings.needsSasayakiAudio)
    }

    @Test
    fun popupEmbedMediaHonorsTheGlobalSetting() {
        val noteType = AnkiNoteType(5, "Basic", listOf("Front"))
        val format = AnkiCardFormat(
            id = "format",
            name = "Default",
            selectedDeckId = 3,
            selectedNoteTypeId = 5,
            fieldMappings = mapOf("Front" to "{expression}"),
        )
        fun state(embedMedia: Boolean) = AnkiUiState(
            settings = AnkiSettings(
                cardFormats = listOf(format),
                availableDecks = listOf(AnkiDeck(3, "Mining")),
                availableNoteTypes = listOf(noteType),
                embedMedia = embedMedia,
            ),
        )

        assertFalse(state(embedMedia = false).popupSettings.embedMedia)
        assertTrue(state(embedMedia = true).popupSettings.embedMedia)
    }

    @Test
    fun disconnectedAnkiConnectDisablesOtherwiseValidFormats() {
        val basic = AnkiNoteType(5L, "Basic", listOf("Front"))
        val state = AnkiUiState(
            settings = AnkiSettings(
                backendKind = AnkiBackendKind.AnkiConnect,
                availableDecks = listOf(AnkiDeck(3L, "Mining")),
                availableNoteTypes = listOf(basic),
                cardFormats = listOf(
                    AnkiCardFormat(
                        id = "format",
                        name = "Word",
                        selectedDeckId = 3L,
                        selectedNoteTypeId = basic.id,
                        fieldMappings = mapOf("Front" to "{expression}"),
                    ),
                ),
            ),
            isAnkiConnectReachable = false,
        )

        assertFalse(state.popupSettings.isBackendAvailable)
        assertFalse(state.popupSettings.formats.single().isValid)
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
