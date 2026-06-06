package moe.antimony.hoshi.features.dictionary

import de.manhhao.hoshi.FrequencyEntry
import de.manhhao.hoshi.GlossaryEntry
import de.manhhao.hoshi.LookupResult
import de.manhhao.hoshi.PitchEntry
import de.manhhao.hoshi.TermResult
import moe.antimony.hoshi.features.reader.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionarySearchContentTest {
    @Test
    fun blankQueryClearsSearchContentLikeIos() {
        val state = DictionarySearchContent.runLookup(
            query = "   ",
            lookup = { error("lookup should not run for blank query") },
        )

        assertEquals("", state.lastQuery)
        assertEquals(emptyList<LookupResult>(), state.results)
        assertFalse(state.hasResults)
    }

    @Test
    fun nonBlankQueryPublishesLookupResultsForIframeRootPayload() {
        val state = DictionarySearchContent.runLookup(
            query = " 猫 ",
            lookup = {
                listOf(
                    LookupResult(
                        matched = "猫",
                        deinflected = "猫",
                        process = emptyArray(),
                        term = TermResult(
                            expression = "猫",
                            reading = "ねこ",
                            rules = "",
                            glossaries = arrayOf(
                                GlossaryEntry(
                                    dictName = "JMdict",
                                    glossary = "cat",
                                    definitionTags = "",
                                    termTags = "",
                                ),
                            ),
                            frequencies = emptyArray<FrequencyEntry>(),
                            pitches = emptyArray<PitchEntry>(),
                        ),
                        preprocessorSteps = 0,
                    ),
                )
            },
            dictionaryStyles = mapOf("JMdict" to ".entry {}"),
        )

        assertEquals("猫", state.lastQuery)
        assertTrue(state.hasResults)
        assertEquals("猫", state.results.single().matched)
        assertEquals(mapOf("JMdict" to ".entry {}"), state.dictionaryStyles)
    }

    @Test
    fun dictionaryPopupOptionsUseAppearancePopupSettingsLikeIos() {
        val options = dictionarySearchPopupOptions(
            readerSettings = ReaderSettings(
                eInkMode = true,
                popupWidth = 480,
                popupHeight = 360,
                popupFullWidth = true,
                popupSwipeToDismiss = true,
                popupSwipeThreshold = 65,
            ),
            dictionarySettings = DictionarySettings(maxResults = 7),
            darkMode = true,
            audioSettings = moe.antimony.hoshi.features.audio.AudioSettings(enableAutoplay = true),
        )

        assertFalse(options.isVertical)
        assertFalse(options.isFullWidth)
        assertEquals(480, options.width)
        assertEquals(360, options.height)
        assertTrue(options.swipeToDismiss)
        assertEquals(65, options.swipeThreshold)
        assertEquals(7, options.dictionarySettings.maxResults)
        assertTrue(options.darkMode)
        assertTrue(options.eInkMode)
        assertTrue(options.audioSettings.enableAutoplay)
    }
}
