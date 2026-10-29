package moe.antimony.hoshi.features.anki

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AnkiDroidClientTest {
    @Test fun oldSettingsDefaultToOfficialClientAndKumaRoundTrips() {
        val old = decodeAnkiSettings("""{"schemaVersion":2,"cardFormats":[{"id":"one","name":"One"}]}""") { "new" }
        assertEquals(AnkiDroidClient.AnkiDroid, old.settings.ankiDroidClient)
        assertFalse(old.didMigrate)
        val kuma = old.settings.copy(ankiDroidClient = AnkiDroidClient.Kuma3)
        assertEquals(kuma, decodeAnkiSettings(Json { encodeDefaults = true }.encodeToString(kuma)) { "new" }.settings)
        assertEquals(2, kuma.schemaVersion)
    }

    @Test fun switchingClearsBindingsButKeepsFormatMetadataAndOtherPreferences() {
        val format = AnkiCardFormat("one", "Mining", AnkiFormatIcon.Circle, 1, "Deck", 2, "Basic", mapOf("Front" to "{expression}"), "custom")
        val original = AnkiSettings(
            cardFormats = listOf(format), selectedDeckId = 1, selectedDeckName = "Deck",
            selectedNoteTypeId = 2, selectedNoteTypeName = "Basic",
            availableDecks = listOf(AnkiDeck(1, "Deck")),
            availableNoteTypes = listOf(AnkiNoteType(2, "Basic", listOf("Front"))),
            allowDupes = true, ankiConnectUrl = "http://localhost:8765",
        )
        val switched = original.withAnkiDroidClient(AnkiDroidClient.Kuma3)
        assertEquals(AnkiDroidClient.Kuma3, switched.ankiDroidClient)
        assertEquals(format.copy(selectedDeckId = null, selectedDeckName = null, selectedNoteTypeId = null, selectedNoteTypeName = null), switched.cardFormats.single())
        assertTrue(switched.availableDecks.isEmpty())
        assertTrue(switched.availableNoteTypes.isEmpty())
        assertNull(switched.selectedDeckId)
        assertNull(switched.selectedDeckName)
        assertNull(switched.selectedNoteTypeId)
        assertNull(switched.selectedNoteTypeName)
        assertTrue(switched.allowDupes)
        assertEquals(original.ankiConnectUrl, switched.ankiConnectUrl)
        assertEquals(original, original.withAnkiDroidClient(AnkiDroidClient.AnkiDroid))
        assertNotEquals(original.connectionIdentity, switched.withAnkiDroidClient(AnkiDroidClient.AnkiDroid).connectionIdentity)
    }

    @Test fun clientsUseSeparatePermissionsProvidersAndExplicitIntentTargets() {
        for (client in AnkiDroidClient.entries) {
            assertEquals("${client.packageName}.flashcards", client.authority)
            assertEquals("${client.packageName}.permission.READ_WRITE_DATABASE", client.readWritePermission)
            assertEquals(client.packageName, ankiDroidSyncIntentSpec(client.packageName).packageName)
            assertEquals("com.ichi2.anki.DO_SYNC", ankiDroidSyncIntentSpec(client.packageName).action)
            assertEquals(client.packageName, ankiDroidBrowserIntentSpec("猫", client.packageName).packageName)
        }
        assertEquals("io.github.pedrubik2000.kuma3", AnkiDroidClient.Kuma3.packageName)
    }
}
