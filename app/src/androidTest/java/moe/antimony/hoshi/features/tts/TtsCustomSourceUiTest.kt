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
class TtsCustomSourceUiTest {
    @get:Rule val compose = createComposeRule()

    private fun loadModels() {
        compose.onNodeWithText("Model ID").performScrollTo()
        compose.onNode(hasClickAction() and hasAnyAncestor(hasText("Model ID"))).performClick()
    }

    @Test fun singleModelFillsTheEditableFieldAndSavesWithoutAnotherSelection() {
        var saved: TtsCustomSource? = null
        val source = TtsCustomSource("custom:fixture", "Local", baseUrl = "http://127.0.0.1:8088/v1")
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                TtsCustomSourceEditor(source, false, { _, _, _ -> null }, { draft, _, _ -> saved = draft; null },
                    { null }, { _, _, _ -> listOf("irodori-tts") }, {})
            }
        }
        loadModels()
        compose.onNode(hasSetTextAction() and hasText("irodori-tts")).assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals("irodori-tts", saved?.model) }
    }

    @Test fun choosingFromSeveralModelsAndTypingBothUpdateTheSameField() {
        var saved: TtsCustomSource? = null
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, eInkMode = true, dynamicColor = false) {
                TtsCustomSourceEditor(TtsCustomSource("custom:fixture"), false, { _, _, _ -> null },
                    { draft, _, _ -> saved = draft; null }, { null }, { _, _, _ -> listOf("first", "second") }, {})
            }
        }
        loadModels()
        compose.onNodeWithText("second").performClick()
        compose.onNode(hasSetTextAction() and hasText("second")).performTextReplacement("manual-model")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals("manual-model", saved?.model) }
    }

    @Test fun failedModelDiscoveryKeepsManualInputAvailable() {
        var saved: TtsCustomSource? = null
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                TtsCustomSourceEditor(TtsCustomSource("custom:fixture", model = "existing"), false,
                    { _, _, _ -> null }, { draft, _, _ -> saved = draft; null }, { null },
                    { _, _, _ -> throw java.io.IOException("Fixture unavailable") }, {})
            }
        }
        loadModels()
        compose.onNode(hasSetTextAction() and hasText("existing")).assertIsEnabled().performTextReplacement("manual")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals("manual", saved?.model) }
    }

    @Test fun cancelingEditorCancelsModelDiscovery() {
        val open = mutableStateOf(true)
        var cancelled = false
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                if (open.value) TtsCustomSourceEditor(TtsCustomSource("custom:fixture"), false,
                    { _, _, _ -> null }, { _, _, _ -> null }, { null },
                    { _, _, _ -> try { awaitCancellation() } finally { cancelled = true } }, { open.value = false })
            }
        }
        loadModels()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(cancelled) }
    }
    @Test fun savedCredentialsStayBlankAndTestingDraftDoesNotSave() {
        var tested: TtsCustomSource? = null
        var saved: TtsCustomSource? = null
        val source = TtsCustomSource("custom:fixture", "Local", baseUrl = "http://192.168.1.10:8088/v1", model = "irodori-tts", voice = "sample", encryptedKey = "fixture-ciphertext")
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                TtsCustomSourceEditor(source, true, { draft, key, _ -> tested = draft; assertEquals("draft-key", key); null },
                    { draft, _, _ -> saved = draft; null }, { null }, { _, _, _ -> emptyList() }, {})
            }
        }
        compose.onNodeWithText("fixture-ciphertext").assertDoesNotExist()
        compose.onNodeWithText("Service name").performTextReplacement("Changed")
        compose.onNodeWithText("Service API key").performScrollTo().performTextInput("draft-key")
        compose.onNodeWithText("Test connection").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Changed", tested?.name); assertNull(saved) }
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals("Changed", saved?.name) }
    }
    @Test fun sourcePickerShowsNamedServersAndAddEntryInEink() {
        var selected: String? = null
        var added = false
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, eInkMode = true, dynamicColor = false) {
                TtsProviderPicker(listOf(
                    TtsSourceChoice("custom:one", "Local VITS", TtsSourceGroup.Custom, TtsCustomProtocol.Vits),
                    TtsSourceChoice("custom:two", "Irodori", TtsSourceGroup.Custom, TtsCustomProtocol.OpenAi)),
                    "custom:one", { selected = it }, {}, {}, {}, onCustom = { added = it == null })
            }
        }
        compose.onNodeWithText("Self-hosted services").assertIsDisplayed()
        compose.onNodeWithText("VITS Simple API").assertIsDisplayed()
        compose.onNodeWithText("OpenAI Compatible").assertIsDisplayed()
        compose.onNodeWithText("Irodori").performClick()
        compose.runOnIdle { assertEquals("custom:two", selected) }
        compose.onNodeWithText("Add service").performClick()
        compose.runOnIdle { assertTrue(added) }
    }

    @Test fun closingDraftCancelsInFlightTest() {
        val open = mutableStateOf(true)
        var cancelled = false
        var saved = false
        compose.setContent {
            HoshiReaderTheme(darkTheme = true, eInkMode = true, dynamicColor = false) {
                if (open.value) TtsCustomSourceEditor(TtsCustomSource("custom:fixture"), false,
                    { _, _, _ -> try { awaitCancellation() } finally { cancelled = true } },
                    { _, _, _ -> saved = true; null }, { null }, { _, _, _ -> emptyList() }, { open.value = false })
            }
        }
        compose.onNodeWithText("Test connection").performScrollTo().performClick()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(cancelled); assertFalse(saved) }
    }

    @Test fun editingAvailableVoiceIdsKeepsTheSelectedVoice() {
        var saved: TtsCustomSource? = null
        val source = TtsCustomSource("custom:fixture", "Local", baseUrl = "http://127.0.0.1:8088/v1",
            model = "irodori-tts", voice = "selected", voiceIds = listOf("selected"))
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                TtsCustomSourceEditor(source, true, { _, _, _ -> null },
                    { draft, _, _ -> saved = draft; null }, { null }, { _, _, _ -> emptyList() }, {})
            }
        }
        compose.onNodeWithText("Available voice IDs").performScrollTo().performTextReplacement("neutral\nbright")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals(listOf("neutral", "bright"), saved?.voiceIds)
            assertEquals("selected", saved?.voice)
        }
    }
}
