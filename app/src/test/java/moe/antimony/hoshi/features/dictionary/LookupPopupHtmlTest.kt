package moe.antimony.hoshi.features.dictionary

import de.manhhao.hoshi.FrequencyEntry
import de.manhhao.hoshi.Frequency
import de.manhhao.hoshi.GlossaryEntry
import de.manhhao.hoshi.LookupResult
import de.manhhao.hoshi.PitchEntry
import de.manhhao.hoshi.TermResult
import de.manhhao.hoshi.TransformGroup
import moe.antimony.hoshi.features.audio.AudioPlaybackMode
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.features.audio.AudioSource
import moe.antimony.hoshi.features.anki.AnkiPopupSettings
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
    fun popupHtmlInjectsFontFacesAndInitialScaleLikeIosPopupWebView() {
        val html = LookupPopupHtml.render(
            listOf(lookupResult(expression = "食べる", reading = "たべる", glossary = "to eat")),
            assets = LookupPopupAssets(
                popupJs = "window.renderPopup = function() {};",
                popupCss = ".entry-header {}",
                selectionJs = "window.hoshiSelection = { selectText: function() {} };",
            ),
            fontFaceCss = """
                @font-face {
                    font-family: "Klee One";
                    src: url("https://hoshi.local/fonts/Klee%20One.ttf");
                }
            """.trimIndent(),
            popupScale = 1.25,
        )

        assertTrue(html.contains("""font-family: "Klee One";"""))
        assertTrue(html.contains("""src: url("https://hoshi.local/fonts/Klee%20One.ttf");"""))
        assertTrue(html.contains("html { zoom: 1.25; }"))
    }

    @Test
    fun popupHtmlExposesButtonFrameBridgeForNativeOverlayControls() {
        val html = LookupPopupHtml.render(
            listOf(lookupResult(expression = "食べる", reading = "たべる", glossary = "to eat")),
            assets = LookupPopupAssets(
                popupJs = "window.renderPopup = function() {};",
                popupCss = ".entry-header {}",
                selectionJs = "window.hoshiSelection = { selectText: function() {} };",
            ),
        )

        assertTrue(html.contains("buttonFrames: { postMessage: function(frames)"))
        assertTrue(html.contains("window.HoshiAndroidPopup.postMessage('buttonFrames', frames);"))
    }

    @Test
    fun deinflectionTraceIncludesBridgeDescriptionsForPopupOverlay() {
        val html = LookupPopupHtml.render(
            listOf(
                lookupResult(
                    expression = "食べる",
                    reading = "たべる",
                    glossary = "to eat",
                    process = arrayOf(
                        TransformGroup(
                            name = "polite",
                            description = "Polite conjugation of verbs and adjectives.\nUsage: example text.",
                        ),
                    ),
                ),
            ),
            assets = LookupPopupAssets(
                popupJs = "window.renderPopup = function() {};",
                popupCss = ".entry-header {}",
                selectionJs = "window.hoshiSelection = { selectText: function() {} };",
            ),
        )

        assertTrue(html.contains(""""name":"polite""""))
        assertTrue(html.contains(""""description":"Polite conjugation of verbs and adjectives.\nUsage: example text.""""))
        assertTrue(html.contains("""<div class="overlay-close" onclick="closeOverlay()">×</div>"""))
    }

    private fun lookupResult(
        expression: String,
        reading: String,
        glossary: String,
        process: Array<TransformGroup> = emptyArray(),
        frequencies: Array<FrequencyEntry> = emptyArray(),
        pitches: Array<PitchEntry> = emptyArray(),
    ): LookupResult = LookupResult(
        expression,
        expression,
        process,
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
