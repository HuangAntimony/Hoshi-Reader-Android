package moe.antimony.hoshi.features.dictionary

import de.manhhao.hoshi.FrequencyEntry
import de.manhhao.hoshi.Frequency
import de.manhhao.hoshi.GlossaryEntry
import de.manhhao.hoshi.LookupResult
import de.manhhao.hoshi.PitchEntry
import de.manhhao.hoshi.TermResult
import moe.antimony.hoshi.features.audio.AudioPlaybackMode
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.features.audio.AudioSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LookupPopupHtmlTest {
    @Test
    fun rendersThroughIosPopupJavascriptPipeline() {
        val html = LookupPopupHtml.render(
            listOf(
                lookupResult(
                    expression = "冷や",
                    reading = "ひや",
                    glossary = """
                        [{"content":[{"content":{"content":"cold water","tag":"li"},"tag":"ul"}],"type":"structured-content"}]
                    """.trimIndent(),
                ),
            ),
            assets = LookupPopupAssets(
                popupJs = "window.renderPopup = function() {};",
                popupCss = ".entry-header {}",
                selectionJs = "window.hoshiSelection = { selectText: function() {} };",
            ),
        )

        assertTrue(html.contains("<style>.entry-header {}</style>"))
        assertTrue(html.contains("<script>window.hoshiSelection = { selectText: function() {} };</script>"))
        assertTrue(html.contains("<script>window.renderPopup = function() {};</script>"))
        assertTrue(html.contains("""<div id="entries-container"></div>"""))
        assertTrue(html.contains("window.renderPopup();"))
        assertFalse(html.contains("""<section class="entry">"""))
    }

    @Test
    fun lazyPopupAssetsUseAbsoluteBridgeUrlsSoDictionarySearchCanRender() {
        val html = LookupPopupHtml.render(
            listOf(lookupResult(expression = "test", reading = "test", glossary = "definition")),
            assets = null,
        )

        assertTrue(html.contains("""<link rel="stylesheet" href="https://hoshi.local/popup/popup.css">"""))
        assertTrue(html.contains("""<script src="https://hoshi.local/popup/selection.js"></script>"""))
        assertTrue(html.contains("""<script src="https://hoshi.local/popup/popup.js"></script>"""))
        assertFalse(html.contains("""href="popup.css""""))
        assertFalse(html.contains("""src="selection.js""""))
        assertFalse(html.contains("""src="popup.js""""))
        assertTrue(html.contains("window.lookupEntries = [];"))
        assertTrue(html.contains("window.entryCount = 1;"))
    }

    @Test
    fun serializesLookupEntriesUsingIosPopupShape() {
        val html = LookupPopupHtml.render(
            listOf(
                lookupResult(
                    expression = "冷や",
                    reading = "ひや",
                    glossary = "<b>cold water</b>",
                ),
            ),
            assets = LookupPopupAssets(
                popupJs = "",
                popupCss = "",
            ),
        )

        assertTrue(html.contains("window.lookupEntries = ["))
        assertTrue(html.contains(""""expression":"冷や""""))
        assertTrue(html.contains(""""reading":"ひや""""))
        assertTrue(html.contains(""""matched":"冷や""""))
        assertTrue(html.contains(""""deinflectionTrace":[]"""))
        assertTrue(html.contains(""""glossaries":[{""""))
        assertTrue(html.contains(""""dictionary":"JMdict""""))
        assertTrue(html.contains(""""content":"<b>cold water</b>""""))
        assertTrue(html.contains(""""definitionTags":""""))
        assertTrue(html.contains(""""termTags":""""))
        assertTrue(html.contains(""""frequencies":[]"""))
        assertTrue(html.contains(""""pitches":[]"""))
        assertTrue(html.contains(""""rules":[]"""))
    }

    @Test
    fun serializesFrequencyAndPitchMetadataUsingIosPopupShape() {
        val html = LookupPopupHtml.render(
            listOf(
                lookupResult(
                    expression = "食べる",
                    reading = "たべる",
                    glossary = "eat",
                    frequencies = arrayOf(
                        FrequencyEntry(
                            dictName = "Jiten",
                            frequencies = arrayOf(Frequency(value = 1139, displayValue = "1,139")),
                        ),
                    ),
                    pitches = arrayOf(
                        PitchEntry(
                            dictName = "アクセント辞典",
                            pitchPositions = intArrayOf(2),
                        ),
                    ),
                ),
            ),
            assets = LookupPopupAssets(
                popupJs = "",
                popupCss = "",
            ),
        )

        assertTrue(html.contains(""""frequencies":[{""""))
        assertTrue(html.contains(""""dictionary":"Jiten""""))
        assertTrue(html.contains(""""value":1139"""))
        assertTrue(html.contains(""""displayValue":"1,139""""))
        assertTrue(html.contains(""""pitches":[{""""))
        assertTrue(html.contains(""""dictionary":"アクセント辞典""""))
        assertTrue(html.contains(""""pitchPositions":[2]"""))
    }

    @Test
    fun injectsIosDictionaryDisplaySettingsIntoPopupWebView() {
        val html = LookupPopupHtml.render(
            listOf(lookupResult(expression = "食べる", reading = "たべる", glossary = "eat")),
            assets = LookupPopupAssets(popupJs = "", popupCss = ""),
            settings = DictionarySettings(
                collapseDictionaries = true,
                compactGlossaries = false,
                showExpressionTags = true,
                harmonicFrequency = true,
                deduplicatePitchAccents = true,
                customCSS = ".entry-header { color: red; }",
            ),
        )

        assertTrue(html.contains("window.collapseDictionaries = true;"))
        assertTrue(html.contains("window.compactGlossaries = false;"))
        assertTrue(html.contains("window.showExpressionTags = true;"))
        assertTrue(html.contains("window.harmonicFrequency = true;"))
        assertTrue(html.contains("window.deduplicatePitchAccents = true;"))
        assertTrue(html.contains("""window.customCSS = ".entry-header { color: red; }";"""))
    }

    @Test
    fun disablesSwipeDismissJavascriptWhenSettingIsOff() {
        val html = LookupPopupHtml.render(
            listOf(lookupResult(expression = "食べる", reading = "たべる", glossary = "eat")),
            assets = LookupPopupAssets(popupJs = "", popupCss = ""),
            swipeToDismiss = false,
            swipeThreshold = 40,
        )

        assertTrue(html.contains("window.swipeThreshold = 0;"))
        assertFalse(html.contains("window.swipeThreshold = 40;"))
    }

    @Test
    fun injectsSwipeDismissThresholdWhenSettingIsOn() {
        val html = LookupPopupHtml.render(
            listOf(lookupResult(expression = "食べる", reading = "たべる", glossary = "eat")),
            assets = LookupPopupAssets(popupJs = "", popupCss = ""),
            swipeToDismiss = true,
            swipeThreshold = 55,
        )

        assertTrue(html.contains("window.swipeThreshold = 55;"))
    }

    @Test
    fun swipeDismissUsesHorizontalIntentInsteadOfFixedVerticalLine() {
        val html = LookupPopupHtml.render(
            listOf(lookupResult(expression = "食べる", reading = "たべる", glossary = "eat")),
            assets = LookupPopupAssets(popupJs = "", popupCss = ""),
            swipeToDismiss = true,
            swipeThreshold = 40,
        )

        assertTrue(html.contains("var absDx = Math.abs(dx);"))
        assertTrue(html.contains("var absDy = Math.abs(dy);"))
        assertTrue(html.contains("var isHorizontalDismiss = absDx > window.swipeThreshold && absDx > absDy * 1.75;"))
        assertTrue(html.contains("if (isHorizontalDismiss && !hasSelection)"))
        assertFalse(html.contains("Math.abs(dy) < 20"))
    }

    @Test
    fun canForceIosPopupDarkColorSchemeForAndroidWebView() {
        val html = LookupPopupHtml.render(
            listOf(lookupResult(expression = "食べる", reading = "たべる", glossary = "eat")),
            assets = LookupPopupAssets(popupJs = "", popupCss = ""),
            darkMode = true,
        )

        assertTrue(html.contains("""<html data-hoshi-color-scheme="dark">"""))
        assertTrue(html.contains("html[data-hoshi-color-scheme=\"dark\"],"))
        assertTrue(html.contains("--text-color: #fff;"))
        assertTrue(html.contains("background-color: #000 !important;"))
        assertTrue(html.contains("html[data-hoshi-color-scheme=\"dark\"] .glossary-group > div[data-dictionary]"))
        assertTrue(html.contains("color: var(--text-color) !important;"))
    }

    @Test
    fun fixesPopupBackgroundToPureWhiteInLightMode() {
        val html = LookupPopupHtml.render(
            listOf(lookupResult(expression = "食べる", reading = "たべる", glossary = "eat")),
            assets = LookupPopupAssets(popupJs = "", popupCss = ""),
            darkMode = false,
        )

        assertTrue(html.contains("""<html data-hoshi-color-scheme="light">"""))
        assertTrue(html.contains("background-color: #fff !important;"))
        assertTrue(html.contains("color-scheme: light;"))
    }

    @Test
    fun injectsEnabledAudioSourcesUsingIosPopupVariables() {
        val html = LookupPopupHtml.render(
            listOf(lookupResult(expression = "食べる", reading = "たべる", glossary = "eat")),
            assets = LookupPopupAssets(popupJs = "", popupCss = ""),
            audioSettings = AudioSettings(
                audioSources = listOf(
                    AudioSettings.LocalAudioSource,
                    AudioSource(name = "Disabled", url = "https://disabled.test/?term={term}", isEnabled = false),
                    AudioSource(name = "AnimeCards", url = "https://audio.test/list?term={term}&reading={reading}"),
                ),
                enableLocalAudio = true,
                enableAutoplay = true,
                playbackMode = AudioPlaybackMode.Duck,
            ),
        )

        assertTrue(html.contains("""window.audioSources = ["${AudioSettings.LocalAudioSource.url}","https://audio.test/list?term={term}&reading={reading}"];"""))
        assertTrue(html.contains("""window.audioRequestEndpoint = "https://hoshi.local/audio";"""))
        assertTrue(html.contains("window.audioEnableAutoplay = true;"))
        assertTrue(html.contains("""window.audioPlaybackMode = "duck";"""))
    }

    private fun lookupResult(
        expression: String,
        reading: String,
        glossary: String,
        frequencies: Array<FrequencyEntry> = emptyArray(),
        pitches: Array<PitchEntry> = emptyArray(),
    ): LookupResult = LookupResult(
        expression,
        expression,
        emptyArray(),
        TermResult(
            expression = expression,
            reading = reading,
            rules = "",
            glossaries = arrayOf(
                GlossaryEntry(
                    dictName = "JMdict",
                    glossary = glossary,
                    definitionTags = "",
                    termTags = "",
                ),
            ),
            frequencies = frequencies,
            pitches = pitches,
        ),
        0,
    )
}
