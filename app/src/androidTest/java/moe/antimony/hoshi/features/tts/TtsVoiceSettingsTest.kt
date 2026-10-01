package moe.antimony.hoshi.features.tts

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Stateless UI fixtures: no device preferences, credentials, audio or network. */
@RunWith(AndroidJUnit4::class)
class TtsVoiceSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun providerPickerGroupsInstalledThirdPartyAndOnlineSourcesAndPreservesSelectionIds() {
        val settings = mutableStateOf(TtsSettings(engine = TtsOnlineSource.Edge.id, voice = "ja-JP-NanamiNeural"))
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                TtsVoiceSettings(settings.value, TtsCatalog(emptyList(), settings.value.engine, listOf(TtsVoiceInfo("ja-JP-NanamiNeural", true))),
                    listOf(TtsEngineInfo("third.party", "Third party engine")), false, TtsPreviewState.Idle, null,
                    { settings.value = it }, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("Source · Online").assertExists()
        compose.onNodeWithText("Voice").assertExists()
        compose.onNodeWithText("Nanami").assertExists()
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
                TtsVoicePicker("Fixture source", voices, "saved-voice-not-in-catalog", TtsOnlineSource.Azure, false, null,
                    onSelected = { selected = it }, onRetry = {}, onDismiss = {})
            }
        }
        compose.onNodeWithText("saved-voice-not-in-catalog").assertExists()
        compose.onNodeWithText("Automatic").assertExists()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("custom-voice-400"))
        compose.onNodeWithText("custom-voice-400").performClick()
        compose.runOnIdle { assertEquals("custom-voice-400", selected) }
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
        compose.onNodeWithText("Stop preview").performScrollTo().assertIsEnabled().performClick()
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
