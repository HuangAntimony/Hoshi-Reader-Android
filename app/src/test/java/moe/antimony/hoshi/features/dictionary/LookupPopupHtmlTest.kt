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
    fun iframePopupShellUsesDomButtonsAndAbsoluteAssets() {
        val html = LookupPopupHtml.renderIframeDocument(
            assets = null,
            settings = DictionarySettings(scanLength = 24),
            darkMode = true,
            eInkMode = true,
            popupScale = 1.15,
        )

        assertTrue(html.contains("""<link rel="stylesheet" href="https://hoshi.local/popup/popup.css">"""))
        assertTrue(html.contains("""<script src="https://hoshi.local/popup/selection.js"></script>"""))
        assertTrue(html.contains("""<script src="https://hoshi.local/popup/popup.js"></script>"""))
        assertTrue(html.contains("window.nativePopupButtons = false;"))
        assertTrue(html.contains("window.scanLength = 24;"))
        assertTrue(html.contains("html { zoom: 1.15; }"))
        assertTrue(html.contains("""data-hoshi-color-scheme="dark""""))
        assertTrue(html.contains("""data-hoshi-eink-mode="true""""))
        assertTrue(html.contains("""window.lookupEntries = [];"""))
        assertTrue(html.contains("""window.entryCount = 0;"""))
        assertFalse(html.contains("""<section class="entry">"""))
    }

    @Test
    fun iframePopupShellInstallsSwipeDismissGesture() {
        val html = LookupPopupHtml.renderIframeDocument(
            swipeToDismiss = true,
            swipeThreshold = 35,
        )

        assertTrue(html.contains("window.swipeThreshold = 35;"))
        assertTrue(html.contains("document.addEventListener('touchstart', function(e)"))
        assertTrue(html.contains("document.addEventListener('touchend', function(e)"))
        assertTrue(html.contains("webkit.messageHandlers.swipeDismiss.postMessage(null);"))
    }

    @Test
    fun iframePopupShellDisablesOverscrollStretch() {
        val html = LookupPopupHtml.renderIframeDocument()

        assertTrue(html.contains("overscroll-behavior: none;"))
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
    fun popupHtmlInstallsCustomCssInHeadBeforeRenderingAndPrewarmsFonts() {
        val html = LookupPopupHtml.render(
            listOf(lookupResult(expression = "食べる", reading = "たべる", glossary = "to eat")),
            assets = LookupPopupAssets(
                popupJs = "window.renderPopup = function() {};",
                popupCss = ".entry-header {}",
                selectionJs = "window.hoshiSelection = { selectText: function() {} };",
            ),
            settings = DictionarySettings(
                customCSS = """
                    @font-face {
                        font-family: "Slow Popup Font";
                        src: url("https://hoshi.local/fonts/SlowPopupFont.ttf");
                    }
                    .glossary-content { font-family: "Slow Popup Font"; }
                """.trimIndent(),
            ),
        )

        val customCssIndex = html.indexOf("""<style id="popup-custom-css">""")
        assertTrue(customCssIndex >= 0)
        assertTrue(customCssIndex < html.indexOf("window.renderPopup();"))
        assertTrue(html.contains("""font-family: "Slow Popup Font";"""))
        assertTrue(html.contains("window.hoshiPopupPrewarmFonts = function()"))
        assertTrue(html.contains("window.hoshiPopupPrewarmFonts();"))
    }

    @Test
    fun iframePopupShellInstallsCustomCssInHeadAndPrewarmsFontsDuringIdleLoad() {
        val html = LookupPopupHtml.renderIframeDocument(
            settings = DictionarySettings(
                customCSS = """
                    @font-face {
                        font-family: "Slow Iframe Font";
                        src: url("https://hoshi.local/fonts/SlowIframeFont.ttf");
                    }
                    .entry { font-family: "Slow Iframe Font"; }
                """.trimIndent(),
            ),
        )

        val customCssIndex = html.indexOf("""<style id="popup-custom-css">""")
        assertTrue(customCssIndex >= 0)
        assertTrue(customCssIndex < html.indexOf("""<script src="https://hoshi.local/popup/popup.js"></script>"""))
        assertTrue(html.contains("""font-family: "Slow Iframe Font";"""))
        assertTrue(html.contains("window.hoshiPopupPrewarmFonts = function()"))
        assertTrue(html.contains("window.hoshiPopupPrewarmFonts();"))
    }

    @Test
    fun popupHtmlExposesConfiguredScanLengthToRecursiveSelectionJavascript() {
        val html = LookupPopupHtml.render(
            listOf(lookupResult(expression = "食べる", reading = "たべる", glossary = "to eat")),
            assets = LookupPopupAssets(
                popupJs = "window.renderPopup = function() {};",
                popupCss = ".entry-header {}",
                selectionJs = "window.hoshiSelection = { selectText: function() {} };",
            ),
            settings = DictionarySettings(scanLength = 33),
        )

        assertTrue(html.contains("window.scanLength = 33;"))
    }

    @Test
    fun popupHtmlExposesActiveAnkiConnectBackendToPopupJavascript() {
        val html = LookupPopupHtml.render(
            listOf(lookupResult(expression = "食べる", reading = "たべる", glossary = "to eat")),
            assets = LookupPopupAssets(
                popupJs = "window.renderPopup = function() {};",
                popupCss = ".entry-header {}",
                selectionJs = "window.hoshiSelection = { selectText: function() {} };",
            ),
            ankiSettings = AnkiPopupSettings(
                isConfigured = true,
                useAnkiConnect = true,
            ),
        )

        assertTrue(html.contains("window.useAnkiConnect = true;"))
    }

    @Test
    fun popupHtmlRewritesOnlyBuiltInLocalAudioSourceToInternalEndpoint() {
        val ankiconnectAndroidSource = AudioSource(
            name = "Ankiconnect Android",
            url = AudioSettings.LocalAudioUrl,
        )
        val html = LookupPopupHtml.render(
            listOf(lookupResult(expression = "食べる", reading = "たべる", glossary = "to eat")),
            assets = LookupPopupAssets(
                popupJs = "window.renderPopup = function() {};",
                popupCss = ".entry-header {}",
                selectionJs = "window.hoshiSelection = { selectText: function() {} };",
            ),
            audioSettings = AudioSettings(
                audioSources = listOf(AudioSettings.LocalAudioSource, ankiconnectAndroidSource),
                enableLocalAudio = true,
            ),
        )

        assertTrue(html.contains("hoshi-local-audio-source://get/?term={term}&reading={reading}"))
        assertTrue(html.contains(AudioSettings.LocalAudioUrl))
    }

    @Test
    fun popupHtmlKeepsAnkiconnectAndroidLocalAudioSourceExternalWhenBuiltInLocalAudioIsOff() {
        val html = LookupPopupHtml.render(
            listOf(lookupResult(expression = "食べる", reading = "たべる", glossary = "to eat")),
            assets = LookupPopupAssets(
                popupJs = "window.renderPopup = function() {};",
                popupCss = ".entry-header {}",
                selectionJs = "window.hoshiSelection = { selectText: function() {} };",
            ),
            audioSettings = AudioSettings().addSource(
                AudioSource(
                    name = "Ankiconnect Android",
                    url = AudioSettings.LocalAudioUrl,
                ),
            ),
        )

        assertTrue(html.contains(AudioSettings.LocalAudioUrl))
        assertFalse(html.contains("hoshi-local-audio-source://get/?term={term}&reading={reading}"))
    }

    @Test
    fun popupHtmlExposesNativeButtonFrameBridge() {
        val html = LookupPopupHtml.render(
            listOf(lookupResult(expression = "食べる", reading = "たべる", glossary = "to eat")),
            assets = LookupPopupAssets(
                popupJs = "window.renderPopup = function() {};",
                popupCss = ".button-slot {}",
                selectionJs = "window.hoshiSelection = { selectText: function() {} };",
            ),
        )

        assertTrue(html.contains("buttonFrames: { postMessage: function(frames) { window.HoshiAndroidPopup.postMessage('buttonFrames', frames); } }"))
    }

    @Test
    fun popupHtmlMinesEntriesThroughAsyncRequestBridgeLikeIos() {
        val html = LookupPopupHtml.render(
            listOf(lookupResult(expression = "食べる", reading = "たべる", glossary = "to eat")),
            assets = LookupPopupAssets(
                popupJs = "window.renderPopup = function() {};",
                popupCss = ".button-slot {}",
                selectionJs = "window.hoshiSelection = { selectText: function() {} };",
            ),
        )

        assertTrue(html.contains("mineEntry: { postMessage: function(content) { return window.HoshiAndroidPopup.requestMessage('mineEntry', content); } }"))
        assertFalse(html.contains("window.HoshiPopup.mineEntry(JSON.stringify(content))"))
    }

    @Test
    fun eInkPopupCssTargetsNativeButtonSlots() {
        val html = LookupPopupHtml.render(
            listOf(lookupResult(expression = "食べる", reading = "たべる", glossary = "to eat")),
            assets = LookupPopupAssets(
                popupJs = "window.renderPopup = function() {};",
                popupCss = ".button-slot {}",
                selectionJs = "window.hoshiSelection = { selectText: function() {} };",
            ),
            eInkMode = true,
        )

        assertTrue(html.contains("""html[data-hoshi-eink-mode="true"] .button-slot"""))
        assertFalse(html.contains("""html[data-hoshi-eink-mode="true"] .audio-button"""))
        assertFalse(html.contains("""html[data-hoshi-eink-mode="true"] .mine-button"""))
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
