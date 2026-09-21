package moe.antimony.hoshi.features.anki

import org.junit.Assert.assertEquals
import org.junit.Test

class AnkiAdvancedLayoutTest {
    @Test
    fun advancedSettingsKeepTheSectionOrder() {
        val sections = ankiAdvancedSections()

        assertEquals(
            listOf(
                AnkiAdvancedSection.General::class,
                AnkiAdvancedSection.SelectedGlossaryFallback::class,
                AnkiAdvancedSection.DictionaryCategories::class,
            ),
            sections.map { it::class },
        )
        assertEquals(
            AnkiHandlebarOptions.selectedGlossaryFallbackOptions,
            (sections[1] as AnkiAdvancedSection.SelectedGlossaryFallback).options,
        )
    }

}
