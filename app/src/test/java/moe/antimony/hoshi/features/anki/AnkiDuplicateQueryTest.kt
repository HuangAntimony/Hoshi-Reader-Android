package moe.antimony.hoshi.features.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiDuplicateQueryTest {
    @Test
    fun checksumStripsHtmlMediaAndUsesAnkiFirstFieldShaPrefix() {
        assertEquals(ankiFirstFieldChecksum("食べる"), ankiFirstFieldChecksum("<b>食べる</b>"))
        assertEquals(ankiFirstFieldChecksum(" image.png "), ankiFirstFieldChecksum("""<img src="image.png">"""))
        assertEquals(567984260L, ankiFirstFieldChecksum("食べる"))
    }

    @Test
    fun duplicateSelectionIncludesModelUnlessCheckingAllModels() {
        val scoped = ankiDuplicateNoteSelection(modelId = 7L, checksum = 1234L, checkAllModels = false)
        val allModels = ankiDuplicateNoteSelection(modelId = 7L, checksum = 1234L, checkAllModels = true)

        assertTrue(scoped.contains("mid=7"))
        assertTrue(scoped.contains("csum in (1234)"))
        assertFalse(allModels.contains("mid=7"))
        assertEquals("csum in (1234)", allModels)
    }

    @Test
    fun deckRootScopeIncludesSelectedDeckAndChildDecks() {
        val deckIds = ankiDuplicateScopeDeckIds(
            decksById = mapOf(
                1L to "Default",
                2L to "Mining",
                3L to "Mining::Light Novel",
                4L to "Other::Mining",
            ),
            selectedDeck = AnkiDeck(2L, "Mining"),
            duplicateScope = AnkiDuplicateScope.DeckRoot,
        )

        assertEquals(setOf(2L, 3L), deckIds)
    }

    @Test
    fun deckRootScopeUsesTopLevelDeckWhenSelectedDeckIsNested() {
        val deckIds = ankiDuplicateScopeDeckIds(
            decksById = mapOf(
                1L to "Default",
                2L to "Mining",
                3L to "Mining::Light Novel",
                4L to "Mining::Grammar",
                5L to "Mining::Light Novel::Volume 1",
                6L to "Other::Mining",
            ),
            selectedDeck = AnkiDeck(3L, "Mining::Light Novel"),
            duplicateScope = AnkiDuplicateScope.DeckRoot,
        )

        assertEquals(setOf(2L, 3L, 4L, 5L), deckIds)
    }

    @Test
    fun deckScopeIncludesOnlySelectedDeck() {
        val deckIds = ankiDuplicateScopeDeckIds(
            decksById = mapOf(
                2L to "Mining",
                3L to "Mining::Light Novel",
            ),
            selectedDeck = AnkiDeck(2L, "Mining"),
            duplicateScope = AnkiDuplicateScope.Deck,
        )

        assertEquals(setOf(2L), deckIds)
    }

    @Test
    fun noteBrowserQueryHonorsDuplicateScopeAndModelSetting() {
        val noteType = AnkiNoteType(7L, "Lapis", listOf("Expression"))
        val nestedDeck = AnkiDeck(3L, "Mining::Light Novel")

        assertEquals(
            "\"Expression:食べる\" \"note:Lapis\"",
            ankiNoteBrowserQuery(
                deck = nestedDeck,
                noteType = noteType,
                key = "食べる",
                duplicateScope = AnkiDuplicateScope.Collection,
                checkAllModels = false,
            ),
        )
        assertEquals(
            "\"Expression:食べる\" \"deck:Mining::Light Novel\"",
            ankiNoteBrowserQuery(
                deck = nestedDeck,
                noteType = noteType,
                key = "食べる",
                duplicateScope = AnkiDuplicateScope.Deck,
                checkAllModels = true,
            ),
        )
        assertEquals(
            "\"Expression:食べる\" \"note:Lapis\" \"deck:Mining\"",
            ankiNoteBrowserQuery(
                deck = nestedDeck,
                noteType = noteType,
                key = "食べ\"る",
                duplicateScope = AnkiDuplicateScope.DeckRoot,
                checkAllModels = false,
            ),
        )
    }

    @Test
    fun ankiDroidBrowserIntentTargetsOfficialDeepLink() {
        val spec = ankiDroidBrowserIntentSpec("\"Expression:食べる\"")

        assertEquals("android.intent.action.VIEW", spec.action)
        assertEquals("com.ichi2.anki", spec.packageName)
        assertEquals(
            "anki://x-callback-url/browser?search=%22Expression%3A%E9%A3%9F%E3%81%B9%E3%82%8B%22",
            spec.uri,
        )
        assertTrue(spec.newTask)
    }
}
