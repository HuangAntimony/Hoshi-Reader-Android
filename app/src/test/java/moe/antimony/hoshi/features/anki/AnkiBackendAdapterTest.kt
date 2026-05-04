package moe.antimony.hoshi.features.anki

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiBackendAdapterTest {
    @Test
    fun adapterBuildsFieldsInNoteTypeOrderAndBlocksDuplicatesByDefault() {
        val api = FakeAnkiContentApi(
            duplicateKeys = setOf("食べる"),
        )
        val backend = AnkiDroidBackendAdapter(api)
        val noteType = AnkiNoteType(
            id = 7L,
            name = "Lapis",
            fields = listOf("Expression", "Sentence", "Frequency"),
        )

        val added = backend.addNote(
            deck = AnkiDeck(id = 3L, name = "Mining"),
            noteType = noteType,
            fieldsByName = mapOf(
                "Frequency" to "1139",
                "Expression" to "食べる",
                "Sentence" to "パンを<b>食べる</b>。",
            ),
            tags = setOf("hoshi", "reader"),
            allowDupes = false,
        )

        assertFalse(added)
        assertNull(api.addedFields)
    }

    @Test
    fun adapterAllowsDuplicatesWhenConfigured() {
        val api = FakeAnkiContentApi(
            duplicateKeys = setOf("食べる"),
        )
        val backend = AnkiDroidBackendAdapter(api)
        val noteType = AnkiNoteType(
            id = 7L,
            name = "Lapis",
            fields = listOf("Expression", "Sentence", "Frequency"),
        )

        val added = backend.addNote(
            deck = AnkiDeck(id = 3L, name = "Mining"),
            noteType = noteType,
            fieldsByName = mapOf(
                "Frequency" to "1139",
                "Expression" to "食べる",
                "Sentence" to "パンを<b>食べる</b>。",
            ),
            tags = setOf("hoshi", "reader"),
            allowDupes = true,
        )

        assertTrue(added)
        assertArrayEquals(arrayOf("食べる", "パンを<b>食べる</b>。", "1139"), api.addedFields)
        assertEquals(setOf("hoshi", "reader"), api.addedTags)
    }

    @Test
    fun adapterExposesDecksModelsAndDuplicateChecks() {
        val api = FakeAnkiContentApi(
            decks = mapOf(1L to "Default", 2L to "Mining"),
            models = mapOf(5L to "Basic", 7L to "Lapis"),
            fields = mapOf(7L to listOf("Expression", "Sentence")),
            duplicateKeys = setOf("読む"),
        )
        val backend = AnkiDroidBackendAdapter(api)

        assertEquals(listOf(AnkiDeck(1L, "Default"), AnkiDeck(2L, "Mining")), backend.fetchDecks())
        assertEquals(
            listOf(
                AnkiNoteType(5L, "Basic", emptyList()),
                AnkiNoteType(7L, "Lapis", listOf("Expression", "Sentence")),
            ),
            backend.fetchNoteTypes(),
        )
        assertTrue(backend.isDuplicate(7L, "読む"))
        assertFalse(backend.isDuplicate(7L, "書く"))
    }

    private class FakeAnkiContentApi(
        private val decks: Map<Long, String> = mapOf(1L to "Default"),
        private val models: Map<Long, String> = mapOf(7L to "Lapis"),
        private val fields: Map<Long, List<String>> = mapOf(7L to listOf("Expression")),
        private val duplicateKeys: Set<String> = emptySet(),
    ) : AnkiContentApi {
        var addedFields: Array<String>? = null
            private set
        var addedTags: Set<String>? = null
            private set

        override fun deckList(): Map<Long, String> = decks

        override fun modelList(): Map<Long, String> = models

        override fun fieldList(modelId: Long): List<String> = fields[modelId].orEmpty()

        override fun findDuplicateNotes(modelId: Long, key: String): Boolean = key in duplicateKeys

        override fun addNote(
            modelId: Long,
            deckId: Long,
            fields: Array<String>,
            tags: Set<String>,
        ): Long? {
            addedFields = fields
            addedTags = tags
            return 123L
        }
    }
}
