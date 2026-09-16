package moe.antimony.hoshi.features.dictionary

import moe.antimony.hoshi.content.ContentLanguageProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DictionarySettingsTest {
    @Test
    fun defaultsMatchIosUserConfig() {
        val settings = DictionarySettings()

        assertTrue(settings.autoUpdateDictionaries)
        assertEquals(DictionaryUpdateInterval.Weekly, settings.dictionaryUpdateInterval)
        assertEquals(null, settings.lastDictionaryUpdateEpochMillis)
        assertFalse(settings.dictionaryTabDefault)
        assertTrue(settings.scanNonJapaneseText)
        assertEquals(16, settings.maxResults)
        assertEquals(16, settings.scanLength)
        assertEquals(22, settings.searchTextSize)
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
        val minimums = DictionarySettings(
            maxResults = 200,
            scanLength = 0,
            searchTextSize = 0,
        ).normalized()
        val maximums = DictionarySettings(searchTextSize = 100).normalized()

        assertEquals(50, minimums.maxResults)
        assertEquals(1, minimums.scanLength)
        assertEquals(12, minimums.searchTextSize)
        assertEquals(48, maximums.searchTextSize)
    }

    @Test
    fun updateIntervalsUseIosDurations() {
        assertEquals(24L * 60L * 60L * 1000L, DictionaryUpdateInterval.Daily.intervalMillis)
        assertEquals(7L * 24L * 60L * 60L * 1000L, DictionaryUpdateInterval.Weekly.intervalMillis)
        assertEquals(30L * 24L * 60L * 60L * 1000L, DictionaryUpdateInterval.Monthly.intervalMillis)
        assertEquals(DictionaryUpdateInterval.Weekly, DictionaryUpdateInterval.fromRawValue("Weekly"))
        assertEquals(null, DictionaryUpdateInterval.fromRawValue("Yearly"))
    }

    @Test
    fun scanNonJapaneseTextSettingIsOnlyVisibleForJapaneseProfiles() {
        assertTrue(scanNonJapaneseTextSettingVisible(ContentLanguageProfile.Japanese))
        assertFalse(scanNonJapaneseTextSettingVisible(ContentLanguageProfile.English))
    }
}
