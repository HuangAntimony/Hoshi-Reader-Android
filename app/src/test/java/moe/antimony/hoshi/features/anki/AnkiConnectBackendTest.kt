package moe.antimony.hoshi.features.anki

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiConnectBackendTest {
    @Test
    fun pingUsesVersionAction() {
        val transport = FakeAnkiConnectTransport("""{"result":6,"error":null}""")
        val backend = AnkiConnectBackend("https://anki.example.com", transport)

        assertTrue(backend.isAvailable())
        assertEquals(listOf("version"), transport.actions)
    }

    @Test
    fun fetchesDecksAndNoteTypesFromAnkiConnect() {
        val transport = FakeAnkiConnectTransport(
            """{"result":["Default","Mining"],"error":null}""",
            """{"result":["Basic","Lapis"],"error":null}""",
            """{"result":["Front","Back"],"error":null}""",
            """{"result":["Expression","Sentence"],"error":null}""",
        )
        val backend = AnkiConnectBackend("https://anki.example.com", transport)

        assertEquals(listOf("Default", "Mining"), backend.fetchDecks().map { it.name })
        assertEquals(
            listOf(
                AnkiNoteType(
                    id = ankiConnectStableId("model", "Basic"),
                    name = "Basic",
                    fields = listOf("Front", "Back"),
                ),
                AnkiNoteType(
                    id = ankiConnectStableId("model", "Lapis"),
                    name = "Lapis",
                    fields = listOf("Expression", "Sentence"),
                ),
            ),
            backend.fetchNoteTypes(),
        )
        assertEquals(
            listOf("deckNames", "modelNames", "modelFieldNames", "modelFieldNames"),
            transport.actions,
        )
    }

    @Test
    fun duplicateCheckSendsDeckRootAndAllModelsOptions() {
        val transport = FakeAnkiConnectTransport(
            """{"result":[{"canAdd":false,"error":"duplicate"}],"error":null}""",
        )
        val backend = AnkiConnectBackend("https://anki.example.com", transport)

        assertTrue(
            backend.isDuplicate(
                deck = AnkiDeck(id = 1L, name = "Mining::Light Novel"),
                noteType = AnkiNoteType(id = 2L, name = "Lapis", fields = listOf("Expression")),
                key = "食べる",
                duplicateScope = AnkiDuplicateScope.DeckRoot,
                checkDuplicatesAcrossAllModels = true,
            ),
        )

        val note = transport.lastBody()
            .getValue("params")
            .jsonObject
            .getValue("notes")
            .jsonArray[0]
            .jsonObject
        val options = note.getValue("options").jsonObject
        val scopeOptions = options.getValue("duplicateScopeOptions").jsonObject
        assertEquals("deck", options.getValue("duplicateScope").jsonPrimitive.content)
        assertEquals("Mining", scopeOptions.getValue("deckName").jsonPrimitive.content)
        assertTrue(scopeOptions.getValue("checkChildren").jsonPrimitive.content.toBoolean())
        assertTrue(scopeOptions.getValue("checkAllModels").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun addNoteStoresMediaAndCanSync() {
        val transport = FakeAnkiConnectTransport(
            """{"result":null,"error":null}""",
            """{"result":123,"error":null}""",
            """{"result":null,"error":null}""",
        )
        val backend = AnkiConnectBackend("https://anki.example.com", transport)

        assertEquals(
            """<img src="hoshi_dict_image.png">""",
            backend.addMediaFromBytes(
                bytes = byteArrayOf(1, 2, 3),
                preferredName = "hoshi_dict_image.png",
                mimeType = "image/png",
            ),
        )
        assertTrue(
            backend.addNote(
                deck = AnkiDeck(id = 1L, name = "Mining"),
                noteType = AnkiNoteType(id = 2L, name = "Lapis", fields = listOf("Expression", "Picture")),
                fieldsByName = mapOf("Expression" to "食べる", "Picture" to "hoshi_dict_image.png"),
                tags = setOf("hoshi", "reader"),
                allowDupes = false,
                duplicateScope = AnkiDuplicateScope.Collection,
                checkDuplicatesAcrossAllModels = false,
            ),
        )
        assertTrue(backend.sync())

        assertEquals(listOf("storeMediaFile", "addNote", "sync"), transport.actions)
        val mediaParams = transport.bodies.first().getValue("params").jsonObject
        assertEquals("hoshi_dict_image.png", mediaParams.getValue("filename").jsonPrimitive.content)
        assertEquals("AQID", mediaParams.getValue("data").jsonPrimitive.content)
        val addNote = transport.bodies[1].getValue("params").jsonObject.getValue("note").jsonObject
        assertEquals("Mining", addNote.getValue("deckName").jsonPrimitive.content)
        assertEquals("Lapis", addNote.getValue("modelName").jsonPrimitive.content)
        assertFalse(addNote.getValue("options").jsonObject.getValue("allowDuplicate").jsonPrimitive.content.toBoolean())
        assertEquals(listOf("hoshi", "reader"), addNote.getValue("tags").jsonArray.map { tag ->
            tag.jsonPrimitive.content
        })
    }

    @Test
    fun addNoteIgnoresFieldsOutsideSelectedNoteType() {
        val transport = FakeAnkiConnectTransport("""{"result":123,"error":null}""")
        val backend = AnkiConnectBackend("https://anki.example.com", transport)

        assertTrue(
            backend.addNote(
                deck = AnkiDeck(id = 1L, name = "Mining"),
                noteType = AnkiNoteType(id = 2L, name = "Basic", fields = listOf("Front", "Back")),
                fieldsByName = mapOf(
                    "Expression" to "食べる",
                    "Front" to "食べる",
                    "Back" to "eat",
                    "Picture" to "cover.png",
                ),
                tags = emptySet(),
                allowDupes = false,
                duplicateScope = AnkiDuplicateScope.Collection,
                checkDuplicatesAcrossAllModels = false,
            ),
        )

        val fields = transport.lastBody()
            .getValue("params")
            .jsonObject
            .getValue("note")
            .jsonObject
            .getValue("fields")
            .jsonObject
        assertEquals(setOf("Front", "Back"), fields.keys)
        assertEquals("食べる", fields.getValue("Front").jsonPrimitive.content)
        assertEquals("eat", fields.getValue("Back").jsonPrimitive.content)
    }

    @Test
    fun ankiConnectErrorBecomesFetchExceptionMessage() {
        val transport = FakeAnkiConnectTransport("""{"result":null,"error":"permission denied"}""")
        val backend = AnkiConnectBackend("https://anki.example.com", transport)

        val error = try {
            backend.fetchDecks()
            throw AssertionError("Expected fetch to fail.")
        } catch (error: AnkiFetchException) {
            error
        }

        assertEquals("permission denied", error.message)
    }

    private class FakeAnkiConnectTransport(
        vararg responses: String,
    ) : AnkiConnectTransport {
        private val responses = ArrayDeque(responses.toList())
        val bodies = mutableListOf<JsonObject>()
        val actions: List<String>
            get() = bodies.map { it.getValue("action").jsonPrimitive.content }

        override fun post(url: String, body: String, timeoutMillis: Int): String {
            assertEquals("https://anki.example.com", url)
            assertEquals(10_000, timeoutMillis)
            bodies += json.parseToJsonElement(body).jsonObject
            return responses.removeFirst()
        }

        fun lastBody(): JsonObject = bodies.last()

        private companion object {
            val json = Json { ignoreUnknownKeys = true }
        }
    }
}
