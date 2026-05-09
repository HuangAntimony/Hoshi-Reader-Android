package moe.antimony.hoshi.features.anki

import moe.antimony.hoshi.features.audio.LocalAudioResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiRepositoryTest {
    @Test
    fun dictionaryMediaUsesFilenameInsideExistingGlossaryHtml() {
        assertEquals(
            "hoshi_dict_123.svg",
            ankiInlineMediaReference("""<img src="hoshi_dict_123.svg" />"""),
        )
    }

    @Test
    fun directAudioMediaKeepsSoundFilenameForInlineReplacementFallback() {
        assertEquals(
            "hoshi_sasayaki_123.aac",
            ankiInlineMediaReference("[sound:hoshi_sasayaki_123.aac]"),
        )
    }

    @Test
    fun ankiDroidMediaImportPreservesPreferredFilenameExtension() {
        val source = java.io.File("src/main/java/moe/antimony/hoshi/features/anki/AndroidAnkiContentApi.kt").readText()
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        val fileProvider = java.io.File("src/main/java/moe/antimony/hoshi/features/anki/HoshiFileProvider.kt").readText()

        assertFalse(source.contains("preferredName.substringBeforeLast('.')"))
        assertEquals("hoshi_sasayaki_123", ankiPreferredMediaName("hoshi_sasayaki_123.aac"))
        assertEquals("cover", ankiPreferredMediaName("/tmp/cover.jpg"))
        assertTrue(manifest.contains("moe.antimony.hoshi.features.anki.HoshiFileProvider"))
        assertTrue(fileProvider.contains(""""aac" -> "audio/aac""""))
        assertTrue(fileProvider.contains(""""m4a" -> "audio/mp4""""))
    }

    @Test
    fun ankiAudioReadsLocalAudioUrlsFromDatabaseLoader() {
        val localUrl = LocalAudioResolver.audioUrl("nhk16", "taberu.mp3")
        val localAudio = byteArrayOf(1, 2, 3, 4)
        var remoteReads = 0

        val result = readAnkiAudioBytes(
            url = localUrl,
            readLocalAudio = { file ->
                assertEquals("nhk16", file.source)
                assertEquals("taberu.mp3", file.file)
                localAudio
            },
            readRemoteAudio = {
                remoteReads += 1
                null
            },
        )

        assertTrue(localAudio.contentEquals(result))
        assertEquals(0, remoteReads)
    }

    @Test
    fun exportedGaijiDictionaryImagesStayInlineInAnkiFields() {
        val html = normalizeAnkiDictionaryHtml(
            """<span data-sc-img data-sc-class="gaiji"><span class="gloss-image-container"><img class="gloss-image" src="hoshi_dict_123.svg"></span></span>""",
        )

        assertTrue(html.contains("""[data-sc-img][data-sc-class="gaiji"] .gloss-image-container"""))
        assertTrue(html.contains("width:1em!important"))
        assertTrue(html.contains("position:static!important"))
    }

    @Test
    fun miningPayloadSelectionTextIsNotOverriddenByMiningContext() {
        val source = java.io.File("src/main/java/moe/antimony/hoshi/features/anki/AnkiRepository.kt").readText()

        assertTrue(!source.contains("popupSelectionText = context.popupSelectionText ?: payload.popupSelectionText"))
    }

    @Test
    fun lapisDefaultsAreNotReappliedWhenMining() {
        val source = java.io.File("src/main/java/moe/antimony/hoshi/features/anki/AnkiRepository.kt").readText()
        val mineEntry = source.substringAfter("suspend fun mineEntry(")
            .substringBefore("suspend fun isDuplicate(")

        assertTrue(!mineEntry.contains("LapisPreset.applyDefaults"))
    }

    @Test
    fun firstFetchAppliesLapisDefaultsWhenNoAnkiSettingsExist() {
        val noteType = AnkiNoteType(
            id = 7L,
            name = "Lapis",
            fields = listOf("Expression", "MainDefinition", "Sentence", "Picture"),
        )

        assertEquals(
            mapOf(
                "Expression" to "{expression}",
                "MainDefinition" to "{glossary-first}",
                "Sentence" to "{sentence}",
                "Picture" to "{book-cover}",
            ),
            fieldMappingsAfterFetch(noteType, AnkiSettings()),
        )
    }

    @Test
    fun fetchDoesNotApplyLapisDefaultsWhenUserAlreadyHasAnkiSettings() {
        val noteType = AnkiNoteType(
            id = 7L,
            name = "Lapis",
            fields = listOf("Expression", "MainDefinition", "Sentence", "Picture"),
        )
        val current = AnkiSettings(
            selectedNoteTypeId = 7L,
            selectedNoteTypeName = "Lapis",
            fieldMappings = mapOf("Expression" to "{expression}"),
        )

        assertEquals(
            mapOf("Expression" to "{expression}"),
            fieldMappingsAfterFetch(noteType, current),
        )
    }

    @Test
    fun fetchAppliesLapisDefaultsWhenSwitchingFromNonLapisToLapis() {
        val noteType = AnkiNoteType(
            id = 7L,
            name = "Lapis",
            fields = listOf("Expression", "MainDefinition", "Sentence", "Picture"),
        )
        val current = AnkiSettings(
            selectedNoteTypeId = 5L,
            selectedNoteTypeName = "Basic",
            availableNoteTypes = listOf(AnkiNoteType(5L, "Basic", listOf("Front", "Back"))),
            fieldMappings = mapOf("Front" to "{expression}", "Back" to "{glossary}"),
        )

        assertEquals(
            mapOf(
                "Expression" to "{expression}",
                "MainDefinition" to "{glossary-first}",
                "Sentence" to "{sentence}",
                "Picture" to "{book-cover}",
            ),
            fieldMappingsAfterFetch(noteType, current),
        )
    }

    @Test
    fun fetchSelectionKeepsCurrentDeckAndNoteTypeWhenStillAvailable() {
        val current = AnkiSettings(
            selectedDeckId = 2L,
            selectedDeckName = "Mining",
            selectedNoteTypeId = 7L,
            selectedNoteTypeName = "Custom Lapis",
        )
        val decks = listOf(AnkiDeck(1L, "Default"), AnkiDeck(2L, "Mining"))
        val noteTypes = listOf(
            AnkiNoteType(5L, "Basic", listOf("Front", "Back")),
            AnkiNoteType(7L, "Custom Lapis", listOf("Expression", "MainDefinition", "Sentence")),
        )

        assertEquals(AnkiDeck(2L, "Mining"), selectDeckAfterFetch(decks, current))
        assertEquals(AnkiNoteType(7L, "Custom Lapis", listOf("Expression", "MainDefinition", "Sentence")), selectNoteTypeAfterFetch(noteTypes, current))
    }

    @Test
    fun fetchSelectionFallsBackOnlyWhenCurrentSelectionIsMissing() {
        val current = AnkiSettings(
            selectedDeckId = 99L,
            selectedDeckName = "Old Deck",
            selectedNoteTypeId = 99L,
            selectedNoteTypeName = "Old Model",
        )
        val decks = listOf(AnkiDeck(1L, "Default"), AnkiDeck(2L, "Mining"))
        val noteTypes = listOf(
            AnkiNoteType(5L, "Basic", listOf("Front", "Back")),
            AnkiNoteType(7L, "Lapis", listOf("Expression", "MainDefinition", "Sentence")),
        )

        assertEquals(AnkiDeck(2L, "Mining"), selectDeckAfterFetch(decks, current))
        assertEquals(AnkiNoteType(7L, "Lapis", listOf("Expression", "MainDefinition", "Sentence")), selectNoteTypeAfterFetch(noteTypes, current))
    }
}
