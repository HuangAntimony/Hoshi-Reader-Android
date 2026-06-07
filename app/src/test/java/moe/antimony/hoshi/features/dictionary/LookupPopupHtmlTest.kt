package moe.antimony.hoshi.features.dictionary

import de.manhhao.hoshi.FrequencyEntry
import de.manhhao.hoshi.GlossaryEntry
import de.manhhao.hoshi.LookupResult
import de.manhhao.hoshi.PitchEntry
import de.manhhao.hoshi.TermResult
import de.manhhao.hoshi.TransformGroup
import moe.antimony.hoshi.features.anki.AnkiPopupSettings
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.features.audio.AudioSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LookupPopupHtmlTest {
    @Test
    fun iframePopupShellUsesDomButtonsAndAbsoluteAssets() {
        val html = LookupPopupHtml.renderIframeDocument(
            assets = null,
            settings = DictionarySettings(scanLength = 24),
            darkMode = true,
            eInkMode = true,
            popupScale = 1.15,
        )

        assertTrue(html.contains("""<link rel="stylesheet" href="https://appassets.androidplatform.net/popup/popup.css">"""))
        assertTrue(html.contains("""<script src="https://appassets.androidplatform.net/popup/selection.js"></script>"""))
        assertTrue(html.contains("""<script src="https://appassets.androidplatform.net/popup/popup.js"></script>"""))
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
    fun iframePopupShellCanInlineAssetsForTestsAndResourceSnapshots() {
        val html = LookupPopupHtml.renderIframeDocument(
            assets = LookupPopupAssets(
                popupJs = "window.renderPopup = function() {};",
                popupCss = ".entry-header {}",
                selectionJs = "window.hoshiSelection = { selectText: function() {} };",
            ),
        )

        assertTrue(html.contains("<style>.entry-header {}</style>"))
        assertTrue(html.contains("<script>window.hoshiSelection = { selectText: function() {} };</script>"))
        assertTrue(html.contains("<script>window.renderPopup = function() {};</script>"))
    }

    @Test
    fun iframePopupShellInstallsSwipeDismissGestureAndDisablesOverscrollStretch() {
        val html = LookupPopupHtml.renderIframeDocument(
            swipeToDismiss = true,
            swipeThreshold = 35,
        )

        assertTrue(html.contains("window.swipeThreshold = 35;"))
        assertTrue(html.contains("document.addEventListener('touchstart', function(e)"))
        assertTrue(html.contains("document.addEventListener('touchend', function(e)"))
        assertTrue(html.contains("webkit.messageHandlers.swipeDismiss.postMessage(null);"))
        assertTrue(html.contains("overscroll-behavior: none;"))
    }

    @Test
    fun iframePopupShellInjectsFontFacesCustomCssAndPrewarmsFonts() {
        val html = LookupPopupHtml.renderIframeDocument(
            settings = DictionarySettings(
                customCSS = """
                    @font-face {
                        font-family: "Slow Iframe Font";
                        src: url("https://appassets.androidplatform.net/fonts/SlowIframeFont.ttf");
                    }
                    .entry { font-family: "Slow Iframe Font"; }
                """.trimIndent(),
            ),
            fontFaceCss = """
                @font-face {
                    font-family: "Klee One";
                    src: url("https://appassets.androidplatform.net/fonts/Klee%20One.ttf");
                }
            """.trimIndent(),
            popupScale = 1.25,
        )

        val customCssIndex = html.indexOf("""<style id="popup-custom-css">""")
        assertTrue(customCssIndex >= 0)
        assertTrue(customCssIndex < html.indexOf("""<script src="https://appassets.androidplatform.net/popup/popup.js"></script>"""))
        assertTrue(html.contains("""font-family: "Slow Iframe Font";"""))
        assertTrue(html.contains("""font-family: "Klee One";"""))
        assertTrue(html.contains("""src: url("https://appassets.androidplatform.net/fonts/Klee%20One.ttf");"""))
        assertTrue(html.contains("html { zoom: 1.25; }"))
        assertTrue(html.contains("window.hoshiPopupPrewarmFonts = function()"))
        assertTrue(html.contains("window.hoshiPopupPrewarmFonts();"))
    }

    @Test
    fun iframePopupShellExposesAnkiAndAudioSettingsToPopupJavascript() {
        val ankiconnectAndroidSource = AudioSource(
            name = "Ankiconnect Android",
            url = AudioSettings.LocalAudioUrl,
        )
        val html = LookupPopupHtml.renderIframeDocument(
            ankiSettings = AnkiPopupSettings(
                isConfigured = true,
                useAnkiConnect = true,
            ),
            audioSettings = AudioSettings(
                audioSources = listOf(AudioSettings.LocalAudioSource, ankiconnectAndroidSource),
                enableLocalAudio = true,
            ),
        )

        assertTrue(html.contains("window.useAnkiConnect = true;"))
        assertTrue(html.contains("hoshi-local-audio-source://get/?term={term}&reading={reading}"))
        assertTrue(html.contains(AudioSettings.LocalAudioUrl))
    }

    @Test
    fun iframePopupShellKeepsExternalLocalAudioSourceWhenBuiltInLocalAudioIsOff() {
        val html = LookupPopupHtml.renderIframeDocument(
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
    fun iframePopupShellReportsScrollStateForDictionaryPullBridge() {
        val html = LookupPopupHtml.renderIframeDocument()

        assertTrue(html.contains("window.hoshiPostPopupScrollState = function()"))
        assertTrue(html.contains("window.HoshiAndroidPopup.postMessage('scrollState'"))
        assertTrue(html.contains("window.addEventListener('scroll', function()"))
    }

    @Test
    fun eInkPopupCssTargetsPopupControlsAndStructuredRows() {
        val html = LookupPopupHtml.renderIframeDocument(eInkMode = true)

        assertTrue(html.contains("""html[data-hoshi-eink-mode="true"] .button-slot"""))
        assertTrue(html.contains("""html[data-hoshi-eink-mode="true"] .frequency-group"""))
        assertTrue(html.contains("""html[data-hoshi-eink-mode="true"] .overlay"""))
    }

    @Test
    fun deinflectionTraceIsCarriedInEntryJsonForIframePopup() {
        val entryJson = LookupPopupHtml.entryJsonString(
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
        )
        val html = LookupPopupHtml.renderIframeDocument()

        assertTrue(entryJson.contains(""""name":"polite""""))
        assertTrue(entryJson.contains(""""description":"Polite conjugation of verbs and adjectives.\nUsage: example text.""""))
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
