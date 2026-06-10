package moe.antimony.hoshi.features.anki

import android.content.ContextWrapper
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.features.audio.LocalAudioRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiRepositoryBackendSelectionTest {
    @Test
    fun fetchConfigurationUsesAnkiConnectBackendWhenSelected() = runBlocking {
        val settingsRepository = InMemoryAnkiSettingsRepository(
            AnkiSettings(
                backendKind = AnkiBackendKind.AnkiConnect,
                ankiConnectUrl = "https://anki.example.com",
            ),
        )
        val ankiDroid = RecordingBackend(available = false)
        val ankiConnect = RecordingBackend(
            decks = listOf(AnkiDeck(10L, "Mining")),
            noteTypes = listOf(AnkiNoteType(20L, "Lapis", listOf("Expression"))),
        )
        val repository = repository(
            backend = ankiDroid,
            settingsRepository = settingsRepository,
            ankiConnectBackendFactory = { endpoint ->
                assertEquals("https://anki.example.com", endpoint)
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
        assertEquals(10L, settingsRepository.current.selectedDeckId)
        assertEquals(20L, settingsRepository.current.selectedNoteTypeId)
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
            ankiConnectBackendFactory = { ankiConnect },
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
            ankiConnectBackendFactory = { ankiConnect },
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
            ankiConnectBackendFactory = { ankiConnect },
        )

        assertTrue(repository.isDuplicate("食べる", decks = emptyList(), noteTypes = emptyList()))

        assertEquals(0, ankiDroid.duplicateCalls)
        assertEquals(1, ankiConnect.duplicateCalls)
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
            ankiConnectBackendFactory = { ankiConnect },
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
        assertEquals("<img src=\"hoshi_cover_${cover.fileName}\">", ankiConnect.lastFields["Cover"])
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
            ankiConnectBackendFactory = { ankiConnect },
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
            ankiConnectBackendFactory = { ankiConnect },
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
        val sasayaki = Files.createTempFile("hoshi-sasayaki", ".m4a").also { Files.write(it, byteArrayOf(2)) }
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
            ankiConnectBackendFactory = { ankiConnect },
        )

        assertTrue(
            repository.mineEntry(
                rawPayload = """{"expression":"食べる","audio":"${wordAudio.toUri()}"}""",
                context = AnkiMiningContext(
                    sentence = "パンを食べる。",
                    coverPath = cover.toString(),
                    sasayakiAudioPath = sasayaki.toString(),
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
        val sasayaki = Files.createTempFile("hoshi-sasayaki", ".m4a").also { Files.write(it, byteArrayOf(2)) }
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
                    fieldMappings = mapOf("Media" to "{audio} {sasayaki-audio}"),
                ),
            ),
            ankiConnectBackendFactory = { ankiConnect },
        )

        assertTrue(
            repository.mineEntry(
                rawPayload = """{"expression":"食べる","audio":"${wordAudio.toUri()}"}""",
                context = AnkiMiningContext(
                    sentence = "パンを食べる。",
                    sasayakiAudioPath = sasayaki.toString(),
                ),
                decks = emptyList(),
                noteTypes = emptyList(),
            ),
        )

        assertEquals(2, ankiConnect.addMediaFromBytesCalls)
        assertTrue(ankiConnect.lastFields.getValue("Media").contains("hoshi_audio_"))
        assertTrue(ankiConnect.lastFields.getValue("Media").contains(sasayaki.fileName.toString()))
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
            ankiConnectBackendFactory = { ankiConnect },
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
        ankiConnectBackendFactory: (String) -> AnkiBackend = { RecordingBackend() },
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
        private val addNoteResult: Boolean = true,
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

        override fun isAvailable(): Boolean = available

        override fun fetchDecks(): List<AnkiDeck> {
            fetchDecksCalls += 1
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
            return duplicate
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
            lastFields = fieldsByName
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
    }
}
