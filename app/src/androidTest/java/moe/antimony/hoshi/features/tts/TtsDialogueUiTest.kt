package moe.antimony.hoshi.features.tts

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TtsDialogueUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun profileLanguageChangesTheLabelAndRestoresItsOwnVoice() {
        val settings = mutableStateOf(TtsSettings(engine = TtsOnlineSource.Edge.id).withVoice("nanami")
            .copy(languageId = "en").withVoice("british"))
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                TtsVoiceSettings(settings.value, TtsCatalog(settings.value.engine, listOf(
                    TtsVoiceInfo(if (settings.value.languageId == "en") "british" else "nanami", true))), emptyList(), false,
                    TtsPreviewState.Idle, null, { update -> settings.value = update(settings.value) }, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText(context.getString(R.string.tts_profile_language, context.getString(R.string.profile_language_english))).assertIsDisplayed()
        compose.onNodeWithText("british").assertIsDisplayed()
        compose.runOnIdle { settings.value = settings.value.copy(languageId = "ja") }
        compose.onNodeWithText(context.getString(R.string.tts_profile_language, context.getString(R.string.profile_language_japanese))).assertIsDisplayed()
        compose.onNodeWithText("nanami").assertIsDisplayed()
        compose.onNodeWithText("british").assertDoesNotExist()
    }

    private fun voiceLabel(resource: Int) = context.getString(R.string.tts_voice_status, context.getString(resource), context.getString(R.string.tts_online))

    private fun verify(eink: Boolean) {
        val settings = mutableStateOf(TtsSettings(engine = TtsOnlineSource.Azure.id))
        var refreshes = 0
        val voices = listOf(TtsVoiceInfo("n", true, listOf("sad", "cheerful")), TtsVoiceInfo("d", true, listOf("sad")))
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, eInkMode = eink, dynamicColor = false) {
                TtsVoiceSettings(settings.value, TtsCatalog(settings.value.engine, voices), emptyList(), false,
                    TtsPreviewState.Idle, null, { update -> settings.value = update(settings.value) }, {}, {}, { refreshes++ }, {}, {}, Modifier)
            }
        }
        compose.onNodeWithText(voiceLabel(R.string.tts_dialogue_voice)).assertDoesNotExist()
        compose.onNode(hasText(context.getString(R.string.tts_voice_status, context.getString(R.string.tts_voice), context.getString(R.string.tts_online))) and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("n").performClick()
        compose.onAllNodes(isToggleable()).onFirst().assertIsOff().performClick()
        compose.runOnIdle { assertEquals("n", settings.value.routing().dialogue) }
        compose.onNodeWithText(voiceLabel(R.string.tts_narration_voice)).assertExists()
        compose.onNodeWithText(voiceLabel(R.string.tts_dialogue_voice)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.tts_refresh_voices)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, refreshes) }
        compose.onNodeWithText("d").performClick()
        compose.runOnIdle {
            assertEquals("n", settings.value.voice)
            assertEquals("d", settings.value.routing().dialogue)
            assertEquals(listOf("sad"), settings.value.sharedVoice(voices)?.styles)
        }
        compose.onNodeWithText(context.getString(R.string.tts_preview_voice)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.tts_dialogue_routing)).performScrollTo()
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        compose.onNodeWithText(voiceLabel(R.string.tts_dialogue_voice)).assertDoesNotExist()
        compose.runOnIdle { assertEquals("d", settings.value.routing().dialogue) }
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        compose.runOnIdle { assertEquals("d", settings.value.routing().dialogue) }
    }

    @Test fun separateVoicesUseTheExistingPickerAndKeepPreviewVisible() = verify(false)
    @Test fun separateVoicesWorkWithEinkSheetComponents() = verify(true)
}
