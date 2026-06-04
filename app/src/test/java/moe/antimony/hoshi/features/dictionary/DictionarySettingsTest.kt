package moe.antimony.hoshi.features.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DictionarySettingsTest {
    @Test
    fun defaultsMatchIosUserConfig() {
        val settings = DictionarySettings()

        assertEquals(DictionaryLanguage.Japanese, settings.lookupLanguage)
        assertFalse(settings.dictionaryTabDefault)
        assertTrue(settings.scanNonJapaneseText)
        assertEquals(16, settings.maxResults)
        assertEquals(16, settings.scanLength)
        assertEquals(DictionaryCollapseMode.ExpandAll, settings.collapseMode)
        assertFalse(settings.expandFirstDictionary)
        assertEquals(emptySet<String>(), settings.collapsedDictionaries)
        assertTrue(settings.compactGlossaries)
        assertFalse(settings.showExpressionTags)
        assertFalse(settings.harmonicFrequency)
        assertFalse(settings.deduplicatePitchAccents)
        assertTrue(settings.compactPitchAccents)
        assertEquals("", settings.customCSS)
    }

    @Test
    fun lookupSettingsAreClampedToIosStepperRanges() {
        val settings = DictionarySettings(maxResults = 200, scanLength = 0).normalized()

        assertEquals(50, settings.maxResults)
        assertEquals(1, settings.scanLength)
    }

}
