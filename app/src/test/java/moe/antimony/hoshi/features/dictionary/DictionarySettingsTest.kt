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

    @Test
    fun scanNonJapaneseTextSettingMatchesIosDefaultAndUi() {
        val settingsSource = File("src/main/java/moe/antimony/hoshi/features/dictionary/DictionarySettings.kt").readText()
        val viewSource = File("src/main/java/moe/antimony/hoshi/features/dictionary/DictionaryView.kt").readText()

        assertTrue(settingsSource.contains("""val scanNonJapaneseText: Boolean = true"""))
        assertTrue(settingsSource.contains("KEY_SCAN_NON_JAPANESE_TEXT"))
        assertTrue(settingsSource.contains("putBoolean(KEY_SCAN_NON_JAPANESE_TEXT, normalized.scanNonJapaneseText)"))
        assertTrue(viewSource.contains("""ToggleRow("Scan Non-Japanese Text", settings.scanNonJapaneseText)"""))
        assertTrue(viewSource.contains("current.copy(scanNonJapaneseText = it)"))
    }

    @Test
    fun dictionaryCollapseSettingsMatchIosModes() {
        val settingsSource = File("src/main/java/moe/antimony/hoshi/features/dictionary/DictionarySettings.kt").readText()
        val viewSource = File("src/main/java/moe/antimony/hoshi/features/dictionary/DictionaryView.kt").readText()

        assertTrue(settingsSource.contains("enum class DictionaryCollapseMode"))
        assertTrue(settingsSource.contains("""ExpandAll("Expand All")"""))
        assertTrue(settingsSource.contains("""CollapseAll("Collapse All")"""))
        assertTrue(settingsSource.contains("""Custom("Custom")"""))
        assertTrue(settingsSource.contains("""val collapseMode: DictionaryCollapseMode = DictionaryCollapseMode.ExpandAll"""))
        assertTrue(settingsSource.contains("""val expandFirstDictionary: Boolean = false"""))
        assertTrue(settingsSource.contains("""val collapsedDictionaries: Set<String> = emptySet()"""))
        assertTrue(viewSource.contains("""SectionLabel("Collapse Dictionaries")"""))
        assertTrue(viewSource.contains("DictionaryCollapseMode.entries.forEachIndexed"))
        assertTrue(viewSource.contains("""ToggleRow("Expand First Dictionary", settings.expandFirstDictionary)"""))
        assertTrue(viewSource.contains("""Text("Configure")"""))
        assertFalse(viewSource.contains("Auto-collapse Dictionaries"))
    }
}
