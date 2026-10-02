package moe.antimony.hoshi.features.tts

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.unit.dp
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Stateless UI fixtures: no device preferences, credentials, audio or network. */
@RunWith(AndroidJUnit4::class)
class TtsVoiceSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun previewRemainsVisibleWhileExpandedParametersScroll() {
        var starts = 0
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                TtsVoiceSettings(TtsSettings(engine = TtsOnlineSource.Azure.id), null, emptyList(), false,
                    TtsPreviewState.Idle, null, {}, { starts++ }, {}, {}, {}, {}, Modifier.height(360.dp))
            }
        }
        val preview = compose.onNodeWithText("Preview voice").assertIsDisplayed()
        val position = preview.fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("Audio format").performScrollTo().assertIsDisplayed()
        preview.assertIsDisplayed().performClick()
        assertEquals(position, preview.fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("Source").performScrollTo().assertIsDisplayed()
        preview.assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(2, starts) }
    }

    @Test fun customFormatSelectionPreservesOtherServersAndVoice() {
        val first = TtsCustomSource("custom:first", "Local", TtsCustomProtocol.Vits, "http://127.0.0.1:8080", voice = "VITS:3", format = TtsAudioFormat.Wav)
        val other = first.copy(id = "custom:other", name = "Other")
        val settings = mutableStateOf(TtsSettings(engine = first.id, voice = first.voice, customSources = listOf(first, other)))
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                TtsVoiceSettings(settings.value, null, emptyList(), false, TtsPreviewState.Idle, null,
                    { settings.value = it }, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("Audio format").performScrollTo().performClick()
        compose.onNodeWithText("MP3").performClick()
        compose.runOnIdle {
            assertEquals(TtsAudioFormat.Mp3, settings.value.customSource()?.format)
            assertEquals(other, settings.value.customSources.last())
            assertEquals("VITS:3", settings.value.voice)
        }
    }

    @Test fun einkCatalogLoadingUsesReadableStatusInThePanelAndVoicePicker() {
        val picker = mutableStateOf(false)
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, eInkMode = true, dynamicColor = false) {
                if (picker.value) TtsVoicePicker("Fixture", emptyList(), "", true, null, {}, {}, {})
                else TtsVoiceSettings(TtsSettings(engine = TtsOnlineSource.Edge.id), null, emptyList(), true,
                    TtsPreviewState.Idle, null, {}, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("Loading voices…").assertIsDisplayed()
        compose.runOnIdle { picker.value = true }
        compose.onNodeWithText("Loading voices…").assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo,
            androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate)).assertCountEquals(0)
    }

    @Test fun playbackSpeedRemainsIndependentOfTwoStateEngineRate() {
        val settings = mutableStateOf(TtsSettings(engine = TtsOnlineSource.GoogleTranslate.id, rate = .5f))
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                TtsVoiceSettings(settings.value, null, emptyList(), false, TtsPreviewState.Idle, null,
                    { settings.value = it }, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("Engine speech rate").assertExists()
        compose.onNodeWithText("Hoshi playback speed").assertExists()
        val slider = compose.onNode(SemanticsMatcher("Hoshi playback speed range") {
            it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)?.range == ttsPlaybackRateRange
        })
        slider.performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(3f) }
        compose.runOnIdle { assertEquals(3f, settings.value.playbackRate); assertEquals(.5f, settings.value.rate) }
        slider.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(.5f) }
        compose.runOnIdle { assertEquals(.5f, settings.value.playbackRate); assertEquals(.5f, settings.value.rate) }
    }

    @Test fun providerPickerGroupsInstalledThirdPartyAndOnlineSourcesAndPreservesSelectionIds() {
        val settings = mutableStateOf(TtsSettings(engine = TtsOnlineSource.Edge.id, voice = "ja-JP-NanamiNeural"))
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                TtsVoiceSettings(settings.value, TtsCatalog(emptyList(), settings.value.engine, listOf(TtsVoiceInfo("ja-JP-NanamiNeural", true))),
                    listOf(TtsEngineInfo("third.party", "Third party engine")), false, TtsPreviewState.Idle, null,
                    { settings.value = it }, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("Source").assertExists()
        compose.onNodeWithText("Voice · Online").assertExists()
        compose.onNodeWithText("ja-JP-NanamiNeural").assertExists()
        compose.onNodeWithText("Edge TTS").performClick()
        compose.onNodeWithText("Choose source").assertExists()
        compose.onNodeWithText("Device engines").assertExists()
        compose.onNodeWithText("Third party engine").assertExists()
        compose.onNodeWithText("Microsoft Translator").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(TtsOnlineSource.MicrosoftTranslator.id, settings.value.engine); assertEquals("", settings.value.voice) }
    }

    @Test fun largeVoiceListSelectsRawIdentifierAndRetainsUnknownSavedVoice() {
        var selected = ""
        val voices = (1..400).map { TtsVoiceInfo("custom-voice-$it", true) }
        compose.setContent {
            HoshiReaderTheme(darkTheme = true, dynamicColor = false) {
                TtsVoicePicker("Fixture source", voices, "saved-voice-not-in-catalog", false, null,
                    onSelected = { selected = it }, onRetry = {}, onDismiss = {})
            }
        }
        compose.onNodeWithText("saved-voice-not-in-catalog").assertExists()
        compose.onNodeWithText("Automatic").assertExists()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("custom-voice-400"))
        compose.onNodeWithText("custom-voice-400").performClick()
        compose.runOnIdle { assertEquals("custom-voice-400", selected) }
    }

    @Test fun installedVoiceSelectionUpdatesItsNetworkRequirement() {
        val settings = mutableStateOf(TtsSettings(engine = "fixture.engine", voice = "offline-voice"))
        val voices = listOf(TtsVoiceInfo("offline-voice", false), TtsVoiceInfo("online-voice", true))
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                TtsVoiceSettings(settings.value, TtsCatalog(emptyList(), "fixture.engine", voices),
                    listOf(TtsEngineInfo("fixture.engine", "Fixture engine")), false, TtsPreviewState.Idle, null,
                    { settings.value = it }, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("Source").assertExists()
        compose.onNodeWithText("Voice · Offline").assertExists()
        compose.onNodeWithText("offline-voice").performClick()
        compose.onNodeWithText("online-voice").performClick()
        compose.onNodeWithText("Voice · Online").assertExists()
        compose.runOnIdle { assertEquals("online-voice", settings.value.voice) }
        compose.onNodeWithText("online-voice").performClick()
        compose.onNodeWithText("Automatic").performClick()
        compose.onNodeWithText("Voice · Offline").assertExists()
        compose.runOnIdle { settings.value = settings.value.copy(voice = "unknown-saved-voice") }
        compose.onNodeWithText("Voice").assertExists()
        compose.onNodeWithText("unknown-saved-voice").assertExists()
    }

    @Test fun previewButtonCanCancelPreparationAndStopPlayback() {
        val preview = mutableStateOf(TtsPreviewState.Preparing)
        var stops = 0
        var starts = 0
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, eInkMode = true, dynamicColor = false) {
                TtsVoiceSettings(TtsSettings(), null, emptyList(), preview.value == TtsPreviewState.Preparing, preview.value, null,
                    {}, { starts++ }, { stops++ }, {}, {}, {})
            }
        }
        compose.onNodeWithText("Stop preview").assertIsEnabled().performClick()
        compose.runOnIdle { preview.value = TtsPreviewState.Playing }
        compose.onNodeWithText("Stop preview").performClick()
        compose.runOnIdle { preview.value = TtsPreviewState.Idle }
        compose.onNodeWithText("Preview voice").performClick()
        compose.runOnIdle { assertEquals(2, stops); assertEquals(1, starts) }
    }

    @Test fun cloudConfigurationActionDoesNotSelectOrChangeThatSource() {
        var selected: String? = null
        var configured: TtsOnlineSource? = null
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                TtsProviderPicker(listOf(TtsSourceChoice(TtsOnlineSource.Azure.id, "Azure TTS", TtsSourceGroup.Cloud)),
                    TtsOnlineSource.Edge.id, { selected = it }, { configured = it }, {}, {})
            }
        }
        compose.onNode(hasClickAction() and hasAnyAncestor(hasText("Azure TTS"))).performClick()
        compose.runOnIdle { assertEquals(TtsOnlineSource.Azure, configured); assertNull(selected) }
    }
}
