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

    private fun verify(eink: Boolean) {
        val settings = mutableStateOf(TtsSettings(engine = TtsOnlineSource.Azure.id))
        var refreshes = 0
        val voices = listOf(TtsVoiceInfo("n", true, listOf("sad", "cheerful")), TtsVoiceInfo("d", true, listOf("sad")))
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, eInkMode = eink, dynamicColor = false) {
                TtsVoiceSettings(settings.value, TtsCatalog(emptyList(), settings.value.engine, voices), emptyList(), false,
                    TtsPreviewState.Idle, null, { update -> settings.value = update(settings.value) }, {}, {}, { refreshes++ }, {}, {}, Modifier)
            }
        }
        compose.onNodeWithText(context.getString(R.string.tts_dialogue_voice)).assertDoesNotExist()
        compose.onNode(hasText(context.getString(R.string.tts_voice), substring = true) and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("n").performClick()
        compose.onAllNodes(isToggleable()).onFirst().assertIsOff().performClick()
        compose.runOnIdle { assertEquals("n", settings.value.routing().dialogue) }
        compose.onNodeWithText(context.getString(R.string.tts_narration_voice)).assertExists()
        compose.onNodeWithText(context.getString(R.string.tts_dialogue_voice)).performScrollTo().performClick()
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
        compose.onNodeWithText(context.getString(R.string.tts_dialogue_voice)).assertDoesNotExist()
        compose.runOnIdle { assertEquals("d", settings.value.routing().dialogue) }
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        compose.runOnIdle { assertEquals("d", settings.value.routing().dialogue) }
    }

    @Test fun separateVoicesUseTheExistingPickerAndKeepPreviewVisible() = verify(false)
    @Test fun separateVoicesWorkWithEinkSheetComponents() = verify(true)
}
