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

        assertFalse(settings.dictionaryTabDefault)
        assertEquals(16, settings.maxResults)
        assertEquals(16, settings.scanLength)
        assertFalse(settings.collapseDictionaries)
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

    @Test
    fun dictionaryImportProgressDoesNotDimEInkSurfaces() {
        val source = File("src/main/java/moe/antimony/hoshi/features/dictionary/DictionaryView.kt").readText()
        val importingBlock = source.substringAfter("if (uiState.isImporting) {")
            .substringBefore("private enum class DictionaryDestination")

        assertFalse(source.contains("colorScheme.scrim"))
        assertFalse(source.contains(".background(colorScheme.scrim"))
        assertTrue(source.contains("enabled = !uiState.isImporting"))
        assertTrue(importingBlock.contains("CircularProgressIndicator"))
    }

    @Test
    fun compactPitchAccentsSettingIsPersistedAndExposed() {
        val settingsSource = File("src/main/java/moe/antimony/hoshi/features/dictionary/DictionarySettings.kt").readText()
        val viewSource = File("src/main/java/moe/antimony/hoshi/features/dictionary/DictionaryView.kt").readText()

        assertTrue(settingsSource.contains("""val compactPitchAccents: Boolean = true"""))
        assertTrue(settingsSource.contains("KEY_COMPACT_PITCH_ACCENTS"))
        assertTrue(settingsSource.contains("putBoolean(KEY_COMPACT_PITCH_ACCENTS, normalized.compactPitchAccents)"))
        assertTrue(viewSource.contains("""ToggleRow("Compact Pitch Accents", settings.compactPitchAccents)"""))
        assertTrue(viewSource.contains("current.copy(compactPitchAccents = it)"))
    }
}
