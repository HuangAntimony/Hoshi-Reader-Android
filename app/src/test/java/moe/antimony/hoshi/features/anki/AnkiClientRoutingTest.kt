package moe.antimony.hoshi.features.anki

import android.content.ContextWrapper
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.features.audio.LocalAudioRepository
import org.junit.Assert.*
import org.junit.Test

class AnkiClientRoutingTest {
    @Test fun fetchingUsesSelectedClientAndNeverFallsBack() = runBlocking {
        val settings = Settings(AnkiSettings(ankiDroidClient = AnkiDroidClient.Kuma3))
        val official = Backend()
        val kuma = Backend()
        val repository = repository(settings, official, kuma)
        assertTrue(repository.fetchConfiguration() is AnkiFetchResult.Success)
        assertEquals(0, official.fetches)
        assertEquals(1, kuma.fetches)
        kuma.available = false
        val error = repository.fetchConfiguration() as AnkiFetchResult.Error
        assertEquals(AnkiFetchFailure.ApiUnavailable, error.failure)
        assertEquals(0, official.fetches)
    }

    @Test fun lateFetchCannotRestoreBindingsAfterSwitchingAwayAndBack() = runBlocking {
        val settings = Settings(AnkiSettings())
        val official = Backend {
            settings.state.value = settings.state.value.withAnkiDroidClient(AnkiDroidClient.Kuma3)
                .withAnkiDroidClient(AnkiDroidClient.AnkiDroid)
        }
        val repository = repository(settings, official, Backend())
        assertTrue(runCatching { repository.fetchConfiguration() }.exceptionOrNull() is CancellationException)
        assertTrue(settings.state.value.availableDecks.isEmpty())
        assertTrue(settings.state.value.cardFormats.isEmpty())
    }

    @Test fun oneMiningOperationKeepsItsClientEvenWhenSettingsChange() = runBlocking {
        val noteType = AnkiNoteType(8, "Basic", listOf("Front"))
        val settings = Settings(AnkiSettings(
            ankiDroidClient = AnkiDroidClient.Kuma3, ankiDroidForceSync = true,
            cardFormats = listOf(AnkiCardFormat("one", "One", selectedDeckId = 7,
                selectedNoteTypeId = 8, fieldMappings = mapOf("Front" to "{expression}"))),
        ))
        val official = Backend()
        val kuma = Backend { settings.state.value = settings.state.value.withAnkiDroidClient(AnkiDroidClient.AnkiDroid) }
        val repository = repository(settings, official, kuma)
        assertTrue(repository.mineEntry("""{"expression":"猫"}""", AnkiMiningContext(sentence = ""), emptyList(), listOf(noteType)))
        assertEquals(1, kuma.added)
        assertEquals(1, kuma.syncs)
        assertEquals(0, official.added)
        assertEquals(0, official.syncs)
    }

    private fun repository(settings: Settings, official: Backend, kuma: Backend): AnkiRepository = AnkiRepository(
        context = ContextWrapper(null), backend = official, settingsRepository = settings,
        localAudioRepository = LocalAudioRepository(Files.createTempDirectory("anki-client").toFile()),
        ankiDroidBackendFactory = { if (it == AnkiDroidClient.Kuma3) kuma else official },
    )

    private class Settings(initial: AnkiSettings) : AnkiSettingsRepository {
        val state = MutableStateFlow(initial)
        override val settings = state
        override suspend fun update(transform: (AnkiSettings) -> AnkiSettings) { state.value = transform(state.value) }
    }

    private class Backend(val onFetch: () -> Unit = {}) : AnkiBackend {
        var available = true
        var fetches = 0
        var added = 0
        var syncs = 0
        override fun isAvailable() = available
        override fun fetchDecks(): List<AnkiDeck> { fetches++; onFetch(); return listOf(AnkiDeck(7, "Mining")) }
        override fun fetchNoteTypes() = listOf(AnkiNoteType(8, "Basic", listOf("Front")))
        override fun isDuplicate(deck: AnkiDeck, noteType: AnkiNoteType, key: String, duplicateScope: AnkiDuplicateScope, checkDuplicatesAcrossAllModels: Boolean) = false
        override fun addNote(deck: AnkiDeck, noteType: AnkiNoteType, fieldsByName: Map<String, String>, tags: Set<String>, allowDupes: Boolean, duplicateScope: AnkiDuplicateScope, checkDuplicatesAcrossAllModels: Boolean): Boolean { added++; return true }
        override fun addMediaFromUri(uriString: String, preferredName: String, mimeType: String): String? = null
        override fun sync(): Boolean { syncs++; return true }
    }
}
