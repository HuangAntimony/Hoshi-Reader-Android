package moe.antimony.hoshi.features.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiAdvancedLayoutTest {
    @Test
    fun ankiDroidUsesTheIosAdvancedSectionOrder() {
        val sections = ankiAdvancedSections(AnkiBackendKind.AnkiDroid)

        assertEquals(
            listOf(
                AnkiAdvancedSection.General::class,
                AnkiAdvancedSection.SelectedGlossaryFallback::class,
                AnkiAdvancedSection.DictionaryCategories::class,
            ),
            sections.map { it::class },
        )
        assertTrue((sections[0] as AnkiAdvancedSection.General).showEmbedMedia)
        assertEquals(
            AnkiHandlebarOptions.selectedGlossaryFallbackOptions,
            (sections[1] as AnkiAdvancedSection.SelectedGlossaryFallback).options,
        )
    }

    @Test
    fun ankiConnectKeepsTheSameSectionsButHidesEmbedMedia() {
        val sections = ankiAdvancedSections(AnkiBackendKind.AnkiConnect)

        assertEquals(3, sections.size)
        assertFalse((sections[0] as AnkiAdvancedSection.General).showEmbedMedia)
    }
}
