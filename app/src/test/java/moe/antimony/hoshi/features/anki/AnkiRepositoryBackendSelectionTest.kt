package moe.antimony.hoshi.features.anki

import android.content.ContextWrapper
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.dictionary.DictionaryCategory
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.features.audio.LocalAudioRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiRepositoryBackendSelectionTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun addingAFormatDefaultsToHoshiWithoutChangingExistingTags() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val settings = InMemoryAnkiSettingsRepository(
            AnkiSettings(cardFormats = listOf(AnkiCardFormat(id = "saved", name = "Saved", tags = ""))),
        )
        val viewModel = AnkiViewModel(repository(settingsRepository = settings))
        try {
            runCurrent()
            viewModel.addCardFormat("New")
            runCurrent()
            assertEquals(listOf("", "hoshi"), settings.current.cardFormats.map { it.tags })
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun mineEntryResolvesTagsForEachFormatThroughBothBackends() = runBlocking {
        val deck = AnkiDeck(10L, "Mining")
        val noteType = AnkiNoteType(20L, "Basic", listOf("Front", "Back"))
        val template = "hoshi book::{document-title} {expression} {bilingual-definition} {selected-glossary}"
        for (kind in AnkiBackendKind.entries) {
            val ankiDroid = RecordingBackend(decks = listOf(deck), noteTypes = listOf(noteType))
            val ankiConnect = RecordingBackend(decks = listOf(deck), noteTypes = listOf(noteType))
            val format = AnkiCardFormat(
                id = "word",
                name = "Word",
                selectedDeckId = deck.id,
                selectedNoteTypeId = noteType.id,
                fieldMappings = mapOf("Front" to "{expression}", "Back" to template),
                tags = template,
            )
            val repository = repository(
                backend = ankiDroid,
                settingsRepository = InMemoryAnkiSettingsRepository(
                    AnkiSettings(
                        backendKind = kind,
                        ankiConnectUrl = "https://anki.example.com",
                        selectedGlossaryFallback = "{bilingual-definition}",
                        cardFormats = listOf(format, format.copy(id = "custom", tags = "custom\n{reading}")),
                    ),
                ),
                ankiConnectBackendFactory = { _, _ -> ankiConnect },
                loadTermDictionaries = {
                    listOf(AnkiTermDictionary("JMdict", DictionaryCategory.Bilingual))
                },
            )
            val active = if (kind == AnkiBackendKind.AnkiDroid) ankiDroid else ankiConnect
            val inactive = if (kind == AnkiBackendKind.AnkiDroid) ankiConnect else ankiDroid
            for ((id, expected) in listOf(
                "word" to setOf("hoshi", "book::My_Book", "食べる", "to_eat"),
                "custom" to setOf("custom", "たべる"),
            )) {
                assertTrue(repository.mineEntry(
                    rawPayload = """{"expression":"食べる","reading":"たべる","singleGlossaries":"{\"JMdict\":\"to eat\"}"}""",
                    context = AnkiMiningContext(sentence = "パンを食べる。", documentTitle = "My Book"),
                    decks = emptyList(),
                    noteTypes = emptyList(),
                    formatId = id,
                ))
                assertEquals("backend=$kind format=$id", expected, active.lastTags)
                assertEquals("hoshi book::My Book 食べる to eat to eat", active.lastFields["Back"])
                assertFalse(inactive.addNoteCalled)
            }
        }
    }

    @Test
    fun mineEntryNormalizesOnlySubstitutionsAndDropsEmptyAndDuplicateTags() = runBlocking {
        val cases = listOf(
            Triple(" literal\t{document-title}\n{expression} literal ", " \tMy\n\r Book\u00a0第三\u3000巻\u0085 ",
                setOf("literal", "My_Book_第三_巻", "猫")),
            Triple("{document-title} {unknown} {reading}", null, emptySet()),
            Triple("pre{unknown}post {expression}{expression}", "", setOf("prepost", "猫猫")),
            Triple("{document-title}", "{expression}", setOf("{expression}")),
            Triple("{document-title}", "\t\u00a0\u3000\u0085\n", emptySet()),
        )
        for ((template, title, expected) in cases) {
            val backend = RecordingBackend()
            val repository = repository(
                backend = backend,
                settingsRepository = InMemoryAnkiSettingsRepository(
                    AnkiSettings(
                        selectedDeckId = 1L,
                        selectedNoteTypeId = 2L,
                        fieldMappings = mapOf("Front" to "{expression}"),
                        tags = template,
                    ),
                ),
            )
            assertTrue(repository.mineEntry(
                rawPayload = """{"expression":"猫"}""",
                context = AnkiMiningContext(sentence = "", documentTitle = title),
                decks = emptyList(),
                noteTypes = emptyList(),
            ))
            assertEquals("template=$template", expected, backend.lastTags)
        }
    }

    @Test
    fun fetchConfigurationUsesAnkiConnectBackendWhenSelected() = runBlocking {
        val settingsRepository = InMemoryAnkiSettingsRepository(
            AnkiSettings(
                backendKind = AnkiBackendKind.AnkiConnect,
                ankiConnectUrl = "https://anki.example.com",
                ankiConnectApiKey = "hoshi-secret",
            ),
        )
        val ankiDroid = RecordingBackend(available = false)
        val ankiConnect = RecordingBackend(
            decks = listOf(AnkiDeck(10L, "Mining")),
            noteTypes = listOf(AnkiNoteType(20L, "Lapis", listOf("Expression"))),
        )
        var capturedApiKey = ""
        val repository = repository(
            backend = ankiDroid,
            settingsRepository = settingsRepository,
            ankiConnectBackendFactory = { endpoint, apiKey ->
                assertEquals("https://anki.example.com", endpoint)
                capturedApiKey = apiKey
                ankiConnect
            },
        )

        assertEquals(
            AnkiFetchResult.Success(
                decks = listOf(AnkiDeck(10L, "Mining")),
                noteTypes = listOf(AnkiNoteType(20L, "Lapis", listOf("Expression"))),
            ),
            repository.fetchConfiguration(),
        )
        assertEquals(0, ankiDroid.fetchDecksCalls)
        assertEquals(1, ankiConnect.fetchDecksCalls)
        assertEquals("hoshi-secret", capturedApiKey)
        assertEquals(10L, settingsRepository.current.selectedDeckId)
        assertEquals(20L, settingsRepository.current.selectedNoteTypeId)
    }

    @Test
    fun pingAnkiConnectPassesSavedApiKeyToBackendFactory() = runBlocking {
        var capturedEndpoint = ""
        var capturedApiKey = ""
        val repository = repository(
            settingsRepository = InMemoryAnkiSettingsRepository(
                AnkiSettings(
                    backendKind = AnkiBackendKind.AnkiConnect,
                    ankiConnectUrl = "https://anki.example.com",
                    ankiConnectApiKey = "hoshi-secret",
                ),
            ),
            ankiConnectBackendFactory = { endpoint, apiKey ->
                capturedEndpoint = endpoint
                capturedApiKey = apiKey
                RecordingBackend()
            },
        )

        assertEquals(AnkiConnectConnectionResult.Connected, repository.pingAnkiConnect())
        assertEquals("https://anki.example.com", capturedEndpoint)
        assertEquals("hoshi-secret", capturedApiKey)
    }

    @Test
    fun fetchConfigurationRejectsPublicHttpAnkiConnectUrlBeforeNetworkRequests() = runBlocking {
        val ankiConnect = RecordingBackend()
        val repository = repository(
            settingsRepository = InMemoryAnkiSettingsRepository(
                AnkiSettings(
                    backendKind = AnkiBackendKind.AnkiConnect,
                    ankiConnectUrl = "http://anki.example.com:8765",
                ),
            ),
            ankiConnectBackendFactory = { _, _ -> ankiConnect },
        )

        assertEquals(
            AnkiFetchResult.Error(UiText.Literal("Public AnkiConnect HTTP URLs are blocked. Use HTTPS for internet hosts.")),
            repository.fetchConfiguration(),
        )
        assertEquals(0, ankiConnect.fetchDecksCalls)
    }

    @Test
    fun mineEntryUsesActiveAnkiConnectBackendAndForceSyncsAfterSuccessfulAdd() = runBlocking {
        val deck = AnkiDeck(10L, "Mining")
        val noteType = AnkiNoteType(20L, "Lapis", listOf("Expression"))
        val ankiDroid = RecordingBackend(decks = listOf(deck), noteTypes = listOf(noteType))
        val ankiConnect = RecordingBackend(decks = listOf(deck), noteTypes = listOf(noteType))
        val repository = repository(
            backend = ankiDroid,
            settingsRepository = InMemoryAnkiSettingsRepository(
                AnkiSettings(
                    backendKind = AnkiBackendKind.AnkiConnect,
                    ankiConnectUrl = "https://anki.example.com",
                    ankiConnectForceSync = true,
                    selectedDeckId = deck.id,
                    selectedDeckName = deck.name,
                    selectedNoteTypeId = noteType.id,
                    selectedNoteTypeName = noteType.name,
                    availableDecks = listOf(deck),
                    availableNoteTypes = listOf(noteType),
                    fieldMappings = mapOf("Expression" to "{expression}"),
                ),
            ),
            ankiConnectBackendFactory = { _, _ -> ankiConnect },
        )

        assertTrue(
            repository.mineEntry(
                rawPayload = """{"expression":"食べる"}""",
                context = AnkiMiningContext(sentence = "パンを食べる。"),
                decks = emptyList(),
                noteTypes = emptyList(),
            ),
        )

        assertFalse(ankiDroid.addNoteCalled)
        assertTrue(ankiConnect.addNoteCalled)
        assertEquals(1, ankiConnect.syncCalls)
    }

    @Test
    fun mineEntryRendersCategoryHandlebarsFromTheCurrentTermDictionarySnapshot() = runBlocking {
        val deck = AnkiDeck(10L, "Mining")
        val noteType = AnkiNoteType(20L, "Basic", listOf("Front"))
        val backend = RecordingBackend(decks = listOf(deck), noteTypes = listOf(noteType))
        val repository = repository(
            backend = backend,
            settingsRepository = InMemoryAnkiSettingsRepository(
                AnkiSettings(
                    selectedDeckId = deck.id,
                    selectedDeckName = deck.name,
                    selectedNoteTypeId = noteType.id,
                    selectedNoteTypeName = noteType.name,
                    availableDecks = listOf(deck),
                    availableNoteTypes = listOf(noteType),
                    fieldMappings = mapOf("Front" to "{bilingual-definition}"),
                ),
            ),
            loadTermDictionaries = {
                listOf(AnkiTermDictionary("JMdict", DictionaryCategory.Bilingual))
            },
        )

        assertTrue(
            repository.mineEntry(
                rawPayload = """{"expression":"言葉","singleGlossaries":"{\"JMdict\":\"translation\"}"}""",
                context = AnkiMiningContext(sentence = "言葉を調べる。"),
                decks = emptyList(),
                noteTypes = emptyList(),
            ),
        )

        assertEquals(mapOf("Front" to "translation"), backend.lastFields)
    }

    @Test
    fun mineEntryCapturesTermDictionaryCategoriesBeforeBackendWorkCanSwitchProfiles() = runBlocking {
        val deck = AnkiDeck(10L, "Mining")
        val noteType = AnkiNoteType(20L, "Basic", listOf("Front"))
        var currentDictionaries = listOf(
            AnkiTermDictionary("国語辞典", DictionaryCategory.Monolingual),
        )
        val backend = RecordingBackend(
            decks = listOf(deck),
            noteTypes = listOf(noteType),
            onFetchDecks = {
                currentDictionaries = listOf(
                    AnkiTermDictionary("JMdict", DictionaryCategory.Bilingual),
                )
            },
        )
        val repository = repository(
            backend = backend,
            settingsRepository = InMemoryAnkiSettingsRepository(
                AnkiSettings(
                    selectedDeckId = deck.id,
                    selectedDeckName = deck.name,
                    selectedNoteTypeId = noteType.id,
                    selectedNoteTypeName = noteType.name,
                    fieldMappings = mapOf("Front" to "{monolingual-definition}"),
                ),
            ),
            loadTermDictionaries = { currentDictionaries },
        )

        assertTrue(
            repository.mineEntry(
                rawPayload = """{"expression":"言葉","singleGlossaries":"{\"国語辞典\":\"definition\",\"JMdict\":\"translation\"}"}""",
                context = AnkiMiningContext(sentence = "言葉を調べる。"),
                decks = emptyList(),
                noteTypes = emptyList(),
            ),
        )

        assertEquals(mapOf("Front" to "definition"), backend.lastFields)
    }

    @Test
    fun mineEntryForceSyncsAnkiDroidAfterSuccessfulAddWhenEnabled() = runBlocking {
        val deck = AnkiDeck(10L, "Mining")
        val noteType = AnkiNoteType(20L, "Lapis", listOf("Expression"))
        val ankiDroid = RecordingBackend(decks = listOf(deck), noteTypes = listOf(noteType))
        val repository = repository(
            backend = ankiDroid,
            settingsRepository = InMemoryAnkiSettingsRepository(
                AnkiSettings(
                    backendKind = AnkiBackendKind.AnkiDroid,
                    ankiDroidForceSync = true,
                    selectedDeckId = deck.id,
                    selectedDeckName = deck.name,
                    selectedNoteTypeId = noteType.id,
                    selectedNoteTypeName = noteType.name,
                    availableDecks = listOf(deck),
                    availableNoteTypes = listOf(noteType),
                    fieldMappings = mapOf("Expression" to "{expression}"),
                ),
            ),
        )

        assertTrue(
            repository.mineEntry(
                rawPayload = """{"expression":"食べる"}""",
                context = AnkiMiningContext(sentence = "パンを食べる。"),
                decks = emptyList(),
                noteTypes = emptyList(),
            ),
        )

        assertTrue(ankiDroid.addNoteCalled)
        assertEquals(1, ankiDroid.syncCalls)
    }

    @Test
    fun mineEntryDoesNotForceSyncAnkiDroidWhenDisabled() = runBlocking {
        val deck = AnkiDeck(10L, "Mining")
        val noteType = AnkiNoteType(20L, "Lapis", listOf("Expression"))
        val ankiDroid = RecordingBackend(decks = listOf(deck), noteTypes = listOf(noteType))
        val repository = repository(
            backend = ankiDroid,
            settingsRepository = InMemoryAnkiSettingsRepository(
                AnkiSettings(
                    backendKind = AnkiBackendKind.AnkiDroid,
                    ankiDroidForceSync = false,
                    selectedDeckId = deck.id,
                    selectedDeckName = deck.name,
                    selectedNoteTypeId = noteType.id,
                    selectedNoteTypeName = noteType.name,
                    availableDecks = listOf(deck),
                    availableNoteTypes = listOf(noteType),
                    fieldMappings = mapOf("Expression" to "{expression}"),
                ),
            ),
        )

        assertTrue(
            repository.mineEntry(
                rawPayload = """{"expression":"食べる"}""",
                context = AnkiMiningContext(sentence = "パンを食べる。"),
                decks = emptyList(),
                noteTypes = emptyList(),
            ),
        )

        assertTrue(ankiDroid.addNoteCalled)
        assertEquals(0, ankiDroid.syncCalls)
    }

    @Test
    fun mineEntryDoesNotForceSyncAnkiDroidAfterFailedAdd() = runBlocking {
        val deck = AnkiDeck(10L, "Mining")
        val noteType = AnkiNoteType(20L, "Lapis", listOf("Expression"))
        val ankiDroid = RecordingBackend(
            decks = listOf(deck),
            noteTypes = listOf(noteType),
            addNoteResult = false,
        )
        val repository = repository(
            backend = ankiDroid,
            settingsRepository = InMemoryAnkiSettingsRepository(
                AnkiSettings(
                    backendKind = AnkiBackendKind.AnkiDroid,
                    ankiDroidForceSync = true,
                    selectedDeckId = deck.id,
                    selectedDeckName = deck.name,
                    selectedNoteTypeId = noteType.id,
                    selectedNoteTypeName = noteType.name,
                    availableDecks = listOf(deck),
                    availableNoteTypes = listOf(noteType),
                    fieldMappings = mapOf("Expression" to "{expression}"),
                ),
            ),
        )

        assertFalse(
            repository.mineEntry(
                rawPayload = """{"expression":"食べる"}""",
                context = AnkiMiningContext(sentence = "パンを食べる。"),
                decks = emptyList(),
                noteTypes = emptyList(),
            ),
        )

        assertTrue(ankiDroid.addNoteCalled)
        assertEquals(0, ankiDroid.syncCalls)
    }

    @Test
    fun duplicateCheckUsesActiveAnkiConnectBackend() = runBlocking {
        val deck = AnkiDeck(10L, "Mining")
        val noteType = AnkiNoteType(20L, "Lapis", listOf("Expression"))
        val ankiDroid = RecordingBackend(decks = listOf(deck), noteTypes = listOf(noteType))
        val ankiConnect = RecordingBackend(
            decks = listOf(deck),
            noteTypes = listOf(noteType),
            duplicate = true,
        )
        val repository = repository(
            backend = ankiDroid,
            settingsRepository = InMemoryAnkiSettingsRepository(
                AnkiSettings(
                    backendKind = AnkiBackendKind.AnkiConnect,
                    ankiConnectUrl = "https://anki.example.com",
                    selectedDeckId = deck.id,
                    selectedDeckName = deck.name,
                    selectedNoteTypeId = noteType.id,
                    selectedNoteTypeName = noteType.name,
                    availableDecks = listOf(deck),
                    availableNoteTypes = listOf(noteType),
                ),
            ),
            ankiConnectBackendFactory = { _, _ -> ankiConnect },
        )

        assertTrue(repository.isDuplicate("食べる", decks = emptyList(), noteTypes = emptyList()))

        assertEquals(0, ankiDroid.duplicateCalls)
        assertEquals(1, ankiConnect.duplicateCalls)
    }

    @Test
    fun mineEntryUsesOnlyTheRequestedCardFormatAndRejectsDeletedIds() = runBlocking {
        val wordDeck = AnkiDeck(10L, "Words")
        val sentenceDeck = AnkiDeck(11L, "Sentences")
        val noteType = AnkiNoteType(20L, "Basic", listOf("Front", "Back"))
        val backend = RecordingBackend(
            decks = listOf(wordDeck, sentenceDeck),
            noteTypes = listOf(noteType),
        )
        val repository = repository(
            backend = backend,
            settingsRepository = InMemoryAnkiSettingsRepository(
                AnkiSettings(
                    availableDecks = listOf(wordDeck, sentenceDeck),
                    availableNoteTypes = listOf(noteType),
                    cardFormats = listOf(
                        AnkiCardFormat(
                            id = "word",
                            name = "Word",
                            selectedDeckId = wordDeck.id,
                            selectedNoteTypeId = noteType.id,
                            fieldMappings = mapOf("Front" to "{expression}"),
                            tags = "word-tag",
                        ),
                        AnkiCardFormat(
                            id = "sentence",
                            name = "Sentence",
                            selectedDeckId = sentenceDeck.id,
                            selectedNoteTypeId = noteType.id,
                            fieldMappings = mapOf("Front" to "{sentence}", "Back" to "{expression}"),
                            tags = "sentence-tag extra",
                        ),
                    ),
                ),
            ),
        )

        assertTrue(
            repository.mineEntry(
                formatId = "sentence",
                rawPayload = """{"expression":"食べる","matched":"食べる"}""",
                context = AnkiMiningContext(sentence = "パンを食べる。", sentenceOffset = 3),
                decks = emptyList(),
                noteTypes = emptyList(),
            ),
        )
        assertEquals(sentenceDeck, backend.lastDeck)
        assertEquals(setOf("sentence-tag", "extra"), backend.lastTags)
        assertEquals("パンを<b>食べる</b>。", backend.lastFields["Front"])
        assertEquals("食べる", backend.lastFields["Back"])

        assertFalse(
            repository.mineEntry(
                formatId = "deleted",
                rawPayload = """{"expression":"食べる"}""",
                context = AnkiMiningContext(sentence = "食べる"),
                decks = emptyList(),
                noteTypes = emptyList(),
            ),
        )
    }

    @Test
    fun duplicateStatesResolveEachFormatsFirstFieldHandlebar() = runBlocking {
        val deck = AnkiDeck(10L, "Mining")
        val noteType = AnkiNoteType(20L, "Basic", listOf("Front"))
        val backend = RecordingBackend(
            decks = listOf(deck),
            noteTypes = listOf(noteType),
            duplicateKeys = setOf("たべる"),
        )
        val repository = repository(
            backend = backend,
            settingsRepository = InMemoryAnkiSettingsRepository(
                AnkiSettings(
                    availableDecks = listOf(deck),
                    availableNoteTypes = listOf(noteType),
                    cardFormats = listOf(
                        AnkiCardFormat(
                            id = "expression",
                            name = "Expression",
                            selectedDeckId = deck.id,
                            selectedNoteTypeId = noteType.id,
                            fieldMappings = mapOf("Front" to "{expression}"),
                        ),
                        AnkiCardFormat(
                            id = "reading",
                            name = "Reading",
                            selectedDeckId = deck.id,
                            selectedNoteTypeId = noteType.id,
                            fieldMappings = mapOf("Front" to "{reading}"),
                        ),
                        AnkiCardFormat(
                            id = "invalid",
                            name = "Invalid",
                            selectedDeckId = deck.id,
                            selectedNoteTypeId = noteType.id,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            mapOf("expression" to false, "reading" to true, "invalid" to false),
            repository.duplicateStates(
                valuesByHandlebar = mapOf("{expression}" to "食べる", "{reading}" to "たべる"),
                decks = emptyList(),
                noteTypes = emptyList(),
            ),
        )
    }

    @Test
    fun unavailableBackendReturnsNoDuplicateStatesAndNeverEnablesAnotherFormat() = runBlocking {
        val repository = repository(
            backend = RecordingBackend(available = false),
            settingsRepository = InMemoryAnkiSettingsRepository(
                AnkiSettings(cardFormats = listOf(AnkiCardFormat(id = "format", name = "Default"))),
            ),
        )

        assertEquals(
            emptyMap<String, Boolean>(),
            repository.duplicateStates(mapOf("{expression}" to "猫"), emptyList(), emptyList()),
        )
    }

    @Test
    fun showNotesUsesRequestedFormatsFirstFieldAndRejectsDeletedIds() = runBlocking {
        val deck = AnkiDeck(10L, "Mining")
        val noteType = AnkiNoteType(20L, "Basic", listOf("Front"))
        val backend = RecordingBackend(decks = listOf(deck), noteTypes = listOf(noteType))
        val repository = repository(
            backend = backend,
            settingsRepository = InMemoryAnkiSettingsRepository(
                AnkiSettings(
                    availableDecks = listOf(deck),
                    availableNoteTypes = listOf(noteType),
                    cardFormats = listOf(
                        AnkiCardFormat(
                            id = "reading",
                            name = "Reading",
                            selectedDeckId = deck.id,
                            selectedNoteTypeId = noteType.id,
                            fieldMappings = mapOf("Front" to "{reading}"),
                        ),
                    ),
                ),
            ),
        )

        assertTrue(
            repository.showNotes(
                formatId = "reading",
                valuesByHandlebar = mapOf("{expression}" to "食べる", "{reading}" to "たべる"),
                decks = emptyList(),
                noteTypes = emptyList(),
            ),
        )
        assertEquals("たべる", backend.lastOpenNotesKey)
        assertFalse(
            repository.showNotes(
                formatId = "deleted",
                valuesByHandlebar = mapOf("{reading}" to "たべる"),
                decks = emptyList(),
                noteTypes = emptyList(),
            ),
        )
    }

    @Test
    fun mineEntryStoresLocalMediaThroughActiveAnkiConnectBackend() = runBlocking {
        val deck = AnkiDeck(10L, "Mining")
        val noteType = AnkiNoteType(20L, "Lapis", listOf("Expression", "Cover"))
        val ankiConnect = RecordingBackend(decks = listOf(deck), noteTypes = listOf(noteType))
        val cover = Files.createTempFile("hoshi-cover", ".png").also { Files.write(it, byteArrayOf(1, 2, 3)) }
        val repository = repository(
            settingsRepository = InMemoryAnkiSettingsRepository(
                AnkiSettings(
                    backendKind = AnkiBackendKind.AnkiConnect,
                    ankiConnectUrl = "https://anki.example.com",
                    selectedDeckId = deck.id,
                    selectedDeckName = deck.name,
                    selectedNoteTypeId = noteType.id,
                    selectedNoteTypeName = noteType.name,
                    availableDecks = listOf(deck),
                    availableNoteTypes = listOf(noteType),
                    fieldMappings = mapOf(
                        "Expression" to "{expression}",
                        "Cover" to "{book-cover}",
                    ),
                ),
            ),
            ankiConnectBackendFactory = { _, _ -> ankiConnect },
        )

        assertTrue(
            repository.mineEntry(
                rawPayload = """{"expression":"食べる"}""",
                context = AnkiMiningContext(
                    sentence = "パンを食べる。",
                    coverPath = cover.toString(),
                ),
                decks = emptyList(),
                noteTypes = emptyList(),
            ),
        )

        assertEquals(1, ankiConnect.addMediaFromBytesCalls)
        assertEquals(byteArrayOf(1, 2, 3).toList(), ankiConnect.lastMediaBytes.toList())
        assertEquals("<img src=\"hoshi_cover_7037807198c22a7d2b0807371d763779a84fdfcf.png\">", ankiConnect.lastFields["Cover"])
    }

    @Test
    fun mineEntrySubmitsOnlySelectedNoteTypeFields() = runBlocking {
        val deck = AnkiDeck(10L, "Mining")
        val noteType = AnkiNoteType(20L, "Basic", listOf("Front", "Back"))
        val ankiConnect = RecordingBackend(decks = listOf(deck), noteTypes = listOf(noteType))
        val repository = repository(
            settingsRepository = InMemoryAnkiSettingsRepository(
                AnkiSettings(
                    backendKind = AnkiBackendKind.AnkiConnect,
                    ankiConnectUrl = "https://anki.example.com",
                    selectedDeckId = deck.id,
                    selectedDeckName = deck.name,
                    selectedNoteTypeId = noteType.id,
                    selectedNoteTypeName = noteType.name,
                    availableDecks = listOf(deck),
                    availableNoteTypes = listOf(noteType),
                    fieldMappings = mapOf(
                        "Expression" to "{expression}",
                        "Front" to "{expression}",
                        "Back" to "{glossary-first}",
                    ),
                ),
            ),
            ankiConnectBackendFactory = { _, _ -> ankiConnect },
        )

        assertTrue(
            repository.mineEntry(
                rawPayload = """{"expression":"食べる","glossaryFirst":"eat"}""",
                context = AnkiMiningContext(sentence = "パンを食べる。"),
                decks = emptyList(),
                noteTypes = emptyList(),
            ),
        )

        assertEquals(mapOf("Front" to "食べる", "Back" to "eat"), ankiConnect.lastFields)
    }

    @Test
    fun mineEntryIgnoresStaleMediaMappingsOutsideSelectedNoteType() = runBlocking {
        val deck = AnkiDeck(10L, "Mining")
        val noteType = AnkiNoteType(20L, "Basic", listOf("Front"))
        val ankiConnect = RecordingBackend(decks = listOf(deck), noteTypes = listOf(noteType))
        val cover = Files.createTempFile("hoshi-cover", ".png").also { Files.write(it, byteArrayOf(1, 2, 3)) }
        val repository = repository(
            settingsRepository = InMemoryAnkiSettingsRepository(
                AnkiSettings(
                    backendKind = AnkiBackendKind.AnkiConnect,
                    ankiConnectUrl = "https://anki.example.com",
                    selectedDeckId = deck.id,
                    selectedDeckName = deck.name,
                    selectedNoteTypeId = noteType.id,
                    selectedNoteTypeName = noteType.name,
                    availableDecks = listOf(deck),
                    availableNoteTypes = listOf(noteType),
                    fieldMappings = mapOf(
                        "Front" to "{expression}",
                        "Picture" to "{book-cover}",
                    ),
                ),
            ),
            ankiConnectBackendFactory = { _, _ -> ankiConnect },
        )

        assertTrue(
            repository.mineEntry(
                rawPayload = """{"expression":"食べる"}""",
                context = AnkiMiningContext(
                    sentence = "パンを食べる。",
                    coverPath = cover.toString(),
                ),
                decks = emptyList(),
                noteTypes = emptyList(),
            ),
        )

        assertEquals(0, ankiConnect.addMediaFromBytesCalls)
        assertEquals(mapOf("Front" to "食べる"), ankiConnect.lastFields)
    }

    @Test
    fun mineEntryDoesNotStoreUnreferencedHandlebarMedia() = runBlocking {
        val deck = AnkiDeck(10L, "Mining")
        val noteType = AnkiNoteType(20L, "Basic", listOf("Expression"))
        val ankiConnect = RecordingBackend(decks = listOf(deck), noteTypes = listOf(noteType))
        val cover = Files.createTempFile("hoshi-cover", ".png").also { Files.write(it, byteArrayOf(1)) }
        val sasayaki = Files.createTempFile("hoshi-sasayaki", ".aac").also { Files.write(it, byteArrayOf(2)) }
        val wordAudio = Files.createTempFile("hoshi-word", ".mp3").also { Files.write(it, byteArrayOf(3)) }
        val repository = repository(
            settingsRepository = InMemoryAnkiSettingsRepository(
                AnkiSettings(
                    backendKind = AnkiBackendKind.AnkiConnect,
                    ankiConnectUrl = "https://anki.example.com",
                    selectedDeckId = deck.id,
                    selectedDeckName = deck.name,
                    selectedNoteTypeId = noteType.id,
                    selectedNoteTypeName = noteType.name,
                    availableDecks = listOf(deck),
                    availableNoteTypes = listOf(noteType),
                    fieldMappings = mapOf("Expression" to "{expression}"),
                ),
            ),
            ankiConnectBackendFactory = { _, _ -> ankiConnect },
        )

        assertTrue(
            repository.mineEntry(
                rawPayload = """{"expression":"食べる","audio":"${wordAudio.toUri()}"}""",
                context = AnkiMiningContext(
                    sentence = "パンを食べる。",
                    coverPath = cover.toString(),
                    sasayakiAudioPath = sasayaki.toString(),
                    sentenceAudioPath = sasayaki.toString(),
                ),
                decks = emptyList(),
                noteTypes = emptyList(),
            ),
        )

        assertEquals(0, ankiConnect.addMediaFromBytesCalls)
        assertEquals(mapOf("Expression" to "食べる"), ankiConnect.lastFields)
    }

    @Test
    fun mineEntryStoresAudioMediaReferencedInsideFieldTemplates() = runBlocking {
        val deck = AnkiDeck(10L, "Mining")
        val noteType = AnkiNoteType(20L, "Basic", listOf("Media"))
        val ankiConnect = RecordingBackend(decks = listOf(deck), noteTypes = listOf(noteType))
        val sasayaki = Files.createTempFile("hoshi-sasayaki", ".aac").also { Files.write(it, byteArrayOf(2)) }
        val wordAudio = Files.createTempFile("hoshi-word", ".mp3").also { Files.write(it, byteArrayOf(3)) }
        val repository = repository(
            settingsRepository = InMemoryAnkiSettingsRepository(
                AnkiSettings(
                    backendKind = AnkiBackendKind.AnkiConnect,
                    ankiConnectUrl = "https://anki.example.com",
                    selectedDeckId = deck.id,
                    selectedDeckName = deck.name,
                    selectedNoteTypeId = noteType.id,
                    selectedNoteTypeName = noteType.name,
                    availableDecks = listOf(deck),
                    availableNoteTypes = listOf(noteType),
                    fieldMappings = mapOf("Media" to "{audio} {sasayaki-audio} {sentence-audio}"),
                ),
            ),
            ankiConnectBackendFactory = { _, _ -> ankiConnect },
        )

        assertTrue(
            repository.mineEntry(
                rawPayload = """{"expression":"食べる","audio":"${wordAudio.toUri()}"}""",
                context = AnkiMiningContext(
                    sentence = "パンを食べる。",
                    sasayakiAudioPath = sasayaki.toString(),
                    sentenceAudioPath = sasayaki.toString(),
                ),
                decks = emptyList(),
                noteTypes = emptyList(),
            ),
        )

        assertEquals(2, ankiConnect.addMediaFromBytesCalls)
        assertTrue(ankiConnect.lastFields.getValue("Media").contains("hoshi_audio_"))
        assertTrue(ankiConnect.lastFields.getValue("Media").contains("hoshi_sasayaki_c4ea21bb365bbeeaf5f2c654883e56d11e43c44e.aac"))
    }

    @Test
    fun mineEntryStoresOpusAudioMediaWithOpusNameAndMimeType() = runBlocking {
        val deck = AnkiDeck(10L, "Mining")
        val noteType = AnkiNoteType(20L, "Basic", listOf("Media"))
        val ankiConnect = RecordingBackend(decks = listOf(deck), noteTypes = listOf(noteType))
        val wordAudio = Files.createTempFile("hoshi-word", ".opus").also { Files.write(it, byteArrayOf(3, 4, 5)) }
        val repository = repository(
            settingsRepository = InMemoryAnkiSettingsRepository(
                AnkiSettings(
                    backendKind = AnkiBackendKind.AnkiConnect,
                    ankiConnectUrl = "https://anki.example.com",
                    selectedDeckId = deck.id,
                    selectedDeckName = deck.name,
                    selectedNoteTypeId = noteType.id,
                    selectedNoteTypeName = noteType.name,
                    availableDecks = listOf(deck),
                    availableNoteTypes = listOf(noteType),
                    fieldMappings = mapOf("Media" to "{audio}"),
                ),
            ),
            ankiConnectBackendFactory = { _, _ -> ankiConnect },
        )

        assertTrue(
            repository.mineEntry(
                rawPayload = """{"expression":"食べる","audio":"${wordAudio.toUri()}"}""",
                context = AnkiMiningContext(sentence = "パンを食べる。"),
                decks = emptyList(),
                noteTypes = emptyList(),
            ),
        )

        assertEquals(1, ankiConnect.addMediaFromBytesCalls)
        assertTrue(ankiConnect.lastMediaName.endsWith(".opus"))
        assertEquals("audio/ogg", ankiConnect.lastMediaMimeType)
        assertTrue(ankiConnect.lastFields.getValue("Media").contains(".opus"))
    }

    private fun repository(
        backend: AnkiBackend = RecordingBackend(),
        settingsRepository: InMemoryAnkiSettingsRepository = InMemoryAnkiSettingsRepository(),
        ankiConnectBackendFactory: (String, String) -> AnkiBackend = { _, _ -> RecordingBackend() },
        loadTermDictionaries: () -> List<AnkiTermDictionary> = { emptyList() },
    ): AnkiRepository {
        val cacheDir = Files.createTempDirectory("hoshi-anki-cache").toFile()
        return AnkiRepository(
            context = object : ContextWrapper(null) {
                override fun getCacheDir() = cacheDir
            },
            backend = backend,
            settingsRepository = settingsRepository,
            localAudioRepository = LocalAudioRepository(Files.createTempDirectory("hoshi-anki-test").toFile()),
            ankiConnectBackendFactory = ankiConnectBackendFactory,
            loadTermDictionaries = loadTermDictionaries,
        )
    }

    private class InMemoryAnkiSettingsRepository(
        initial: AnkiSettings = AnkiSettings(),
    ) : AnkiSettingsRepository {
        private val state = MutableStateFlow(initial)
        val current: AnkiSettings
            get() = state.value

        override val settings: Flow<AnkiSettings> = state

        override suspend fun update(transform: (AnkiSettings) -> AnkiSettings) {
            state.value = transform(state.value)
        }
    }

    private class RecordingBackend(
        private val available: Boolean = true,
        private val decks: List<AnkiDeck> = listOf(AnkiDeck(1L, "Default")),
        private val noteTypes: List<AnkiNoteType> = listOf(AnkiNoteType(2L, "Basic", listOf("Front"))),
        private val duplicate: Boolean = false,
        private val duplicateKeys: Set<String> = emptySet(),
        private val addNoteResult: Boolean = true,
        private val onFetchDecks: () -> Unit = {},
    ) : AnkiBackend {
        var fetchDecksCalls = 0
            private set
        var addNoteCalled = false
            private set
        var duplicateCalls = 0
            private set
        var addMediaFromBytesCalls = 0
            private set
        var syncCalls = 0
            private set
        var lastMediaBytes: ByteArray = byteArrayOf()
            private set
        var lastMediaName: String = ""
            private set
        var lastMediaMimeType: String = ""
            private set
        var lastFields: Map<String, String> = emptyMap()
            private set
        var lastDeck: AnkiDeck? = null
            private set
        var lastTags: Set<String> = emptySet()
            private set
        var lastOpenNotesKey: String = ""
            private set

        override fun isAvailable(): Boolean = available

        override fun fetchDecks(): List<AnkiDeck> {
            fetchDecksCalls += 1
            onFetchDecks()
            return decks
        }

        override fun fetchNoteTypes(): List<AnkiNoteType> = noteTypes

        override fun isDuplicate(
            deck: AnkiDeck,
            noteType: AnkiNoteType,
            key: String,
            duplicateScope: AnkiDuplicateScope,
            checkDuplicatesAcrossAllModels: Boolean,
        ): Boolean {
            duplicateCalls += 1
            return duplicate || key in duplicateKeys
        }

        override fun addNote(
            deck: AnkiDeck,
            noteType: AnkiNoteType,
            fieldsByName: Map<String, String>,
            tags: Set<String>,
            allowDupes: Boolean,
            duplicateScope: AnkiDuplicateScope,
            checkDuplicatesAcrossAllModels: Boolean,
        ): Boolean {
            addNoteCalled = true
            lastDeck = deck
            lastFields = fieldsByName
            lastTags = tags
            return addNoteResult
        }

        override fun addMediaFromUri(uriString: String, preferredName: String, mimeType: String): String? = null

        override fun addMediaFromBytes(bytes: ByteArray, preferredName: String, mimeType: String): String? {
            addMediaFromBytesCalls += 1
            lastMediaBytes = bytes
            lastMediaName = preferredName
            lastMediaMimeType = mimeType
            return if (mimeType.startsWith("image/")) {
                """<img src="$preferredName">"""
            } else {
                preferredName
            }
        }

        override fun sync(): Boolean {
            syncCalls += 1
            return true
        }

        override fun openNotes(
            deck: AnkiDeck,
            noteType: AnkiNoteType,
            key: String,
            duplicateScope: AnkiDuplicateScope,
            checkDuplicatesAcrossAllModels: Boolean,
        ): Boolean {
            lastOpenNotesKey = key
            return true
        }
    }
}
