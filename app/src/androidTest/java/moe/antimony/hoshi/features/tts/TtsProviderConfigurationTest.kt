package moe.antimony.hoshi.features.tts

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.awaitCancellation
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TtsProviderConfigurationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun savedKeyStaysBlankAndTestDoesNotSaveDraft() {
        var tested: Pair<String, String>? = null
        var saved: Pair<String, String>? = null
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                TtsProviderConfiguration(TtsOnlineSource.Azure, TtsOnlineConfig("eastasia", "encrypted-fixture"),
                    { region, key -> tested = region to key; null }, { region, key -> saved = region to key; null }, {})
            }
        }
        compose.onNodeWithText("encrypted-fixture").assertDoesNotExist()
        compose.onNodeWithText("Service API key").performTextInput("draft-key")
        compose.onNodeWithText("Test connection").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("eastasia" to "draft-key", tested); assertNull(saved) }
        compose.onNodeWithText("Connection verified").assertExists()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals("eastasia" to "draft-key", saved) }
        compose.onNode(hasSetTextAction() and hasText("Service API key")).assertTextEquals("Service API key", "")
    }

    @Test fun dismissingDuringTestCancelsItWithoutSaving() {
        val open = mutableStateOf(true)
        var cancelled = false
        var saved = false
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                if (open.value) TtsProviderConfiguration(TtsOnlineSource.GoogleCloud, TtsOnlineConfig(),
                    { _, _ -> try { awaitCancellation() } finally { cancelled = true } },
                    { _, _ -> saved = true; null }, { open.value = false })
            }
        }
        compose.onNodeWithText("Test connection").performClick()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(cancelled); assertFalse(saved) }
    }
}
