package moe.antimony.hoshi.features.dictionary

import de.manhhao.hoshi.FrequencyEntry
import de.manhhao.hoshi.GlossaryEntry
import de.manhhao.hoshi.LookupResult
import de.manhhao.hoshi.PitchEntry
import de.manhhao.hoshi.Pitch
import de.manhhao.hoshi.KanjiEntry
import de.manhhao.hoshi.KanjiResult
import de.manhhao.hoshi.TermResult
import de.manhhao.hoshi.TraceCandidate
import de.manhhao.hoshi.TraceSource
import de.manhhao.hoshi.TransformGroup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.antimony.hoshi.content.ContentLanguageProfile
import moe.antimony.hoshi.features.anki.AnkiPopupSettings
import moe.antimony.hoshi.features.anki.AnkiPopupFormat
import moe.antimony.hoshi.features.anki.AnkiFormatIcon
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
        assertScriptOrder(
            html,
            "language-ja.js",
            "selection-ja.js",
            "selection.js",
            """window.hoshiSelection?.configure?.({ language: "ja" });""",
            "popup.js",
        )
        assertTrue(html.contains("window.scanLength = 24;"))
        assertTrue(html.contains("html { zoom: 1.15; }"))
        assertTrue(html.contains("""data-hoshi-color-scheme="dark""""))
        assertTrue(html.contains("""data-hoshi-eink-mode="true""""))
        assertTrue(html.contains("""<html lang="ja""""))
        assertTrue(html.contains("""window.lookupEntries = [];"""))
        assertTrue(html.contains("""window.entryCount = 0;"""))
        assertFalse(html.contains("""<section class="entry">"""))
    }

    @Test
    fun iframePopupShellAllowsTwoPointZeroPopupScale() {
        val html = LookupPopupHtml.renderIframeDocument(
            popupScale = 2.0,
        )

        assertTrue(html.contains("html { zoom: 2.0; }"))
    }

    @Test
    fun iframePopupShellCanInlineAssetsForTestsAndResourceSnapshots() {
        val html = LookupPopupHtml.renderIframeDocument(
            assets = LookupPopupAssets(
                popupJs = "window.renderPopup = function() {};",
                popupCss = ".entry-header {}",
                selectionJs = "window.hoshiSelection = { selectText: function() {} };",
                popupGesturesJs = "window.hoshiPopupGesturesLoaded = true;",
            ),
        )

        assertTrue(html.contains("<style>.entry-header {}</style>"))
        assertTrue(html.contains("<script>window.hoshiSelection = { selectText: function() {} };</script>"))
        assertTrue(html.contains("<script>window.renderPopup = function() {};</script>"))
        assertTrue(html.contains("<script>window.hoshiPopupGesturesLoaded = true;</script>"))
    }

    @Test
    fun iframePopupShellUsesEnglishSelectionPolicyForEnglishProfiles() {
        val html = LookupPopupHtml.renderIframeDocument(
            assets = null,
            contentLanguageProfile = ContentLanguageProfile.English,
        )

        assertTrue(html.contains("""<html lang="en""""))
        assertFalse(html.contains("""<script src="https://appassets.androidplatform.net/popup/selection-ja.js"></script>"""))
        assertScriptOrder(
            html,
            "language-ja.js",
            "selection-en.js",
            "selection.js",
            """window.hoshiSelection?.configure?.({ language: "en" });""",
            "popup.js",
        )
    }

    @Test
    fun iframePopupShellInstallsSwipeDismissGestureAndDisablesOverscrollStretch() {
        val html = LookupPopupHtml.renderIframeDocument(
            swipeToDismiss = true,
            swipeThreshold = 35,
        )

        assertTrue(html.contains("window.swipeThreshold = 35;"))
        assertTrue(html.contains("""<script src="https://appassets.androidplatform.net/popup/popup-gestures.js"></script>"""))
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
    fun iframePopupShellAppliesFixedJapaneseContentFontProfile() {
        val html = LookupPopupHtml.renderIframeDocument()

        assertTrue(html.contains("""<html lang="ja""""))
        assertTrue(html.contains("""--hoshi-content-font-family:"""))
        assertTrue(html.contains("Noto Sans CJK JP"))
        assertFalse(html.contains("Hira" + "gino"))
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
                formats = listOf(AnkiPopupFormat("format-a", AnkiFormatIcon.CircleSmall, true)),
                isBackendAvailable = true,
                useAnkiConnect = true,
                disableShowNotes = true,
            ),
            audioSettings = AudioSettings(
                audioSources = listOf(AudioSettings.LocalAudioSource, ankiconnectAndroidSource),
                enableLocalAudio = true,
            ),
        )

        assertTrue(html.contains("window.useAnkiConnect = true;"))
        assertTrue(html.contains("window.ankiFormats = [{\"id\":\"format-a\",\"icon\":\"circle-small\",\"isValid\":true}];"))
        assertTrue(html.contains("window.disableShowNotes = true;"))
        assertTrue(html.contains("showNotes: { postMessage:"))
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
    fun iframePopupShellForwardsTermNavigationMessages() {
        val html = LookupPopupHtml.renderIframeDocument()

        assertTrue(html.contains("message.type === 'navigateTerm'"))
        assertTrue(html.contains("window.navigatePopupTerm?.(message.direction)"))
    }

    @Test
    fun eInkPopupCssTargetsPopupControlsAndStructuredRows() {
        val html = LookupPopupHtml.renderIframeDocument(eInkMode = true)

        assertTrue(html.contains("""html[data-hoshi-eink-mode="true"] .button-slot"""))
        assertTrue(html.contains("""html[data-hoshi-eink-mode="true"] .frequency-group"""))
        assertTrue(html.contains("""html[data-hoshi-eink-mode="true"] .overlay"""))
    }

    @Test
    fun deinflectionTraceRowsAreCarriedInEntryJsonForIframePopup() {
        val entryJson = LookupPopupHtml.entryJsonString(
            lookupResult(
                expression = "食べる",
                reading = "たべる",
                glossary = "to eat",
                traceCandidates = arrayOf(
                    TraceCandidate(
                        deinflected = "食べる",
                        preprocessorSteps = 0,
                        source = TraceSource.ALGORITHM,
                        trace = arrayOf(
                            TransformGroup(
                                name = "polite",
                                description = "Polite conjugation of verbs and adjectives.\nUsage: example text.",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val html = LookupPopupHtml.renderIframeDocument()
        val rows = Json.parseToJsonElement(entryJson).jsonObject.getValue("deinflectionTraceRows").jsonArray

        assertTrue(rows.single().jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content == "polite")
        assertTrue(
            rows.single().jsonArray.single().jsonObject.getValue("description").jsonPrimitive.content ==
                "Polite conjugation of verbs and adjectives.\nUsage: example text.",
        )
        assertTrue(html.contains("""<div class="overlay-close" onclick="closeOverlay()">×</div>"""))
    }

    @Test
    fun deinflectionTraceRowsKeepOneEntrySortBySourceAndHideDictionaryOnlyRows() {
        val entryJson = LookupPopupHtml.entryJsonString(
            lookupResult(
                expression = "食べる",
                reading = "たべる",
                glossary = "to eat",
                traceCandidates = arrayOf(
                    TraceCandidate(
                        deinflected = "食べる",
                        preprocessorSteps = 0,
                        source = TraceSource.DICTIONARY,
                        trace = arrayOf(TransformGroup(name = "redirect", description = "Dictionary redirect")),
                    ),
                    TraceCandidate(
                        deinflected = "食べる",
                        preprocessorSteps = 0,
                        source = TraceSource.BOTH,
                        trace = arrayOf(TransformGroup(name = "both", description = "Algorithm and redirect")),
                    ),
                    TraceCandidate(
                        deinflected = "食べる",
                        preprocessorSteps = 0,
                        source = TraceSource.ALGORITHM,
                        trace = arrayOf(
                            TransformGroup(name = "past", description = "Past tense"),
                            TransformGroup(name = "polite", description = "Polite form"),
                        ),
                    ),
                ),
            ),
        )

        val json = Json.parseToJsonElement(entryJson).jsonObject
        val rows = json.getValue("deinflectionTraceRows").jsonArray

        assertTrue(entryJson.contains(""""expression":"食べる""""))
        assertTrue(entryJson.contains(""""glossaries":[{"""))
        assertFalse(json.containsKey("deinflectionTrace"))
        assertTrue(rows.size == 2)
        assertTrue(rows[0].jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content } == listOf("polite", "past"))
        assertTrue(rows[1].jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content == "both")
        assertFalse(
            rows.flatMap { row -> row.jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content } }
                .contains("redirect"),
        )
    }

    @Test
    fun transcriptionDataIsCarriedSeparatelyFromJapanesePitchPositions() {
        val entryJson = LookupPopupHtml.entryJsonString(
            lookupResult(
                expression = "read",
                reading = "read",
                glossary = "look at and comprehend",
                pitches = arrayOf(
                    PitchEntry(
                        dictName = "English",
                        pitches = emptyArray(),
                        transcriptions = arrayOf("/riːd/", "/rɛd/"),
                    ),
                ),
            ),
        )

        assertTrue(entryJson.contains(""""transcriptions":["/riːd/","/rɛd/"]"""))
        assertTrue(entryJson.contains(""""pitches":[]"""))
    }

    @Test
    fun completePitchSchemaUsesPatternWhenPresentAndKeepsMoraFeatures() {
        val entryJson = LookupPopupHtml.entryJsonString(
            lookupResult(
                expression = "猫",
                reading = "ねこ",
                glossary = "cat",
                pitches = arrayOf(
                    PitchEntry(
                        dictName = "アクセント",
                        pitches = arrayOf(
                            Pitch(position = 1, pattern = "", nasal = intArrayOf(1), devoice = intArrayOf(2)),
                            Pitch(position = 9, pattern = "LHL", nasal = intArrayOf(2), devoice = intArrayOf()),
                            Pitch(position = 7, pattern = "LHL", nasal = intArrayOf(), devoice = intArrayOf()),
                        ),
                        transcriptions = emptyArray(),
                    ),
                ),
            ),
        )

        assertTrue(entryJson.contains(""""position":1,"nasal":[1],"devoice":[2]"""))
        assertTrue(entryJson.contains(""""position":"LHL","nasal":[2],"devoice":[]"""))
        assertFalse(entryJson.contains(""""position":9"""))
        assertFalse(entryJson.contains(""""position":7"""))
    }

    @Test
    fun kanjiResultJsonUsesPopupMeaningsSchema() {
        val json = LookupPopupHtml.kanjiJsonString(
            KanjiResult(
                character = "星",
                entries = arrayOf(
                    KanjiEntry(
                        dictName = "KANJIDIC",
                        onyomi = "セイ, ショウ",
                        kunyomi = "ほし",
                        tags = "jouyou",
                        definitions = arrayOf("star", "spot"),
                        stats = emptyArray(),
                    ),
                ),
            ),
        )

        assertTrue(json.contains(""""character":"星"""))
        assertTrue(json.contains(""""dictName":"KANJIDIC"""))
        assertTrue(json.contains(""""meanings":["star","spot"]"""))
    }

    private fun lookupResult(
        expression: String,
        reading: String,
        glossary: String,
        traceCandidates: Array<TraceCandidate> = emptyArray(),
        frequencies: Array<FrequencyEntry> = emptyArray(),
        pitches: Array<PitchEntry> = emptyArray(),
    ): LookupResult = LookupResult(
        matched = expression,
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
        traceCandidates = traceCandidates,
    )

    private fun assertScriptOrder(html: String, vararg snippets: String) {
        var previous = -1
        snippets.forEach { snippet ->
            val index = html.indexOf(snippet)
            assertTrue("$snippet should be present", index >= 0)
            assertTrue("$snippet should appear after previous script", index > previous)
            previous = index
        }
    }
}
