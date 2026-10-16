package moe.antimony.hoshi.features.tts

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.awaitCancellation
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TtsProviderConfigurationTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun text(resource: Int) = context.getString(resource)

    @Test fun newAzureConfigurationRequiresExplicitRegionSelection() {
        var requests = 0
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, eInkMode = true, dynamicColor = false) {
                TtsProviderConfiguration(TtsOnlineSource.Azure, TtsOnlineConfig(),
                    { _, _ -> requests++; null }, { _, _ -> requests++; null }, {}, onDelete = { null })
            }
        }
        compose.onNode(hasSetTextAction() and hasText(text(R.string.tts_azure_region))).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.tts_test_connection)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.action_save)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.tts_azure_region_select)).assertExists()
        compose.runOnIdle { assertEquals(0, requests) }
    }

    @Test fun selectingRegionsUpdatesTestAndSaveDraftsAndRestoresTheSavedChoice() {
        val config = mutableStateOf(TtsOnlineConfig())
        val open = mutableStateOf(true)
        var testedRegion: String? = null
        var saves = 0
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                if (open.value) TtsProviderConfiguration(TtsOnlineSource.Azure, config.value,
                    { region, _ -> testedRegion = region; null }, { region, _ ->
                        saves++; config.value = config.value.copy(region = region); open.value = false; null
                    }, { open.value = false }, onDelete = { null })
            }
        }
        compose.onNodeWithText(text(R.string.tts_azure_region)).performClick()
        compose.onNodeWithText("japaneast").performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.tts_test_connection)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("japaneast", testedRegion); assertEquals(0, saves) }
        compose.onNodeWithText(text(R.string.tts_connection_success)).assertExists()
        compose.onNodeWithText(text(R.string.tts_azure_region)).performClick()
        compose.onNodeWithText("westus3").performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.tts_connection_success)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.action_save)).performClick()
        compose.runOnIdle { assertEquals("westus3", config.value.region); assertEquals(1, saves); open.value = true }
        compose.onNodeWithText("westus3").assertExists()
        compose.onNodeWithText(text(R.string.tts_azure_region_select)).assertDoesNotExist()
    }

    @Test fun unsupportedRegionLeavesSelectionEmptyAndKeyDeletionAvailable() {
        val config = mutableStateOf(TtsOnlineConfig("east", "encrypted-fixture"))
        var saves = 0
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, eInkMode = true, dynamicColor = false) {
                TtsProviderConfiguration(TtsOnlineSource.Azure, config.value,
                    { _, _ -> null }, { _, _ -> saves++; null }, {}, onDelete = {
                        config.value = config.value.copy(encryptedKey = ""); null
                    })
            }
        }
        compose.onNodeWithText(text(R.string.tts_azure_region_select)).assertExists()
        compose.onNodeWithText(text(R.string.tts_test_connection)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.action_save)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.tts_delete_key)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.action_delete)).performClick()
        compose.runOnIdle { assertEquals("", config.value.encryptedKey); assertEquals("east", config.value.region); assertEquals(0, saves) }
        compose.onNodeWithText(text(R.string.tts_azure_region)).performScrollTo().performClick()
        compose.onNodeWithText("eastasia").performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.action_save)).assertIsEnabled()
        compose.runOnIdle { assertEquals("east", config.value.region); assertEquals(0, saves) }
    }

    @Test fun savedCloudKeyDeletionRequiresConfirmationAndCancelKeepsTheForm() {
        var saved = false
        var deletions = 0
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                TtsProviderConfiguration(TtsOnlineSource.Azure, TtsOnlineConfig("eastasia", "encrypted-fixture"),
                    { _, _ -> null }, { _, _ -> saved = true; null }, {}, onDelete = { deletions++; null })
            }
        }
        compose.onNodeWithText(text(R.string.tts_delete_key)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.tts_delete_key_title)).assertIsDisplayed()
        compose.onNode(hasText(text(R.string.action_cancel)) and hasAnyAncestor(isDialog() and hasAnyDescendant(hasText(text(R.string.tts_delete_key_title))))).performClick()
        compose.onNodeWithText(text(R.string.tts_delete_key_title)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.tts_delete_key)).assertExists()
        compose.runOnIdle { assertFalse(saved); assertEquals(0, deletions) }
    }

    @Test fun confirmedDeletionClearsTheKeyDraftAndTestFeedbackWithoutSavingTheForm() {
        val config = mutableStateOf(TtsOnlineConfig("eastasia", "encrypted-fixture"))
        var deletions = 0
        var saves = 0
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, eInkMode = true, dynamicColor = false) {
                TtsProviderConfiguration(TtsOnlineSource.Azure, config.value,
                    { _, _ -> null }, { _, _ -> saves++; null }, {}, onDelete = {
                        deletions++
                        config.value = config.value.copy(encryptedKey = "")
                        null
                    })
            }
        }
        compose.onNodeWithText(text(R.string.tts_api_key)).performTextInput("unsaved-draft")
        compose.onNodeWithText(text(R.string.tts_test_connection)).performClick()
        compose.onNodeWithText(text(R.string.tts_connection_success)).assertExists()
        compose.onNodeWithText(text(R.string.tts_delete_key)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.action_delete)).performClick()
        compose.onNodeWithText(text(R.string.tts_delete_key)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.tts_key_device_hint)).assertExists()
        compose.onNodeWithText(text(R.string.tts_connection_success)).assertDoesNotExist()
        compose.onNode(hasSetTextAction() and hasText(text(R.string.tts_api_key))).assertTextEquals(text(R.string.tts_api_key), "")
        compose.runOnIdle { assertEquals(1, deletions); assertEquals(0, saves); assertEquals("eastasia", config.value.region) }
    }

    @Test fun failedDeletionKeepsTheKeyAndDraftAndCanBeRetried() {
        val config = mutableStateOf(TtsOnlineConfig(encryptedKey = "encrypted-fixture"))
        var attempts = 0
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                TtsProviderConfiguration(TtsOnlineSource.GoogleCloud, config.value,
                    { _, _ -> null }, { _, _ -> null }, {}, onDelete = {
                        attempts++
                        if (attempts == 1) TtsFailure.Storage.toError() else {
                            config.value = config.value.copy(encryptedKey = "")
                            null
                        }
                    })
            }
        }
        compose.onNodeWithText(text(R.string.tts_api_key)).performTextInput("unsaved-draft")
        compose.onNodeWithText(text(R.string.tts_delete_key)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.action_delete)).performClick()
        compose.onNodeWithText(context.getString(TtsFailure.Storage.messageRes())).assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText(text(R.string.tts_api_key))).assert(hasText("unsaved-draft"))
        compose.runOnIdle { assertEquals("encrypted-fixture", config.value.encryptedKey) }
        compose.onNodeWithText(text(R.string.tts_delete_key)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.action_delete)).performClick()
        compose.onNodeWithText(text(R.string.tts_delete_key)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, attempts) }
    }

    @Test fun failedConnectionRemainsVisibleAboveTheFixedButtonInEink() {
        val result = kotlinx.coroutines.CompletableDeferred<TtsError?>()
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, eInkMode = true, dynamicColor = false) {
                TtsProviderConfiguration(TtsOnlineSource.Azure, TtsOnlineConfig("eastasia"),
                    { _, _ -> result.await() }, { _, _ -> null }, {}, onDelete = { null })
            }
        }
        val button = compose.onNodeWithText(text(R.string.tts_test_connection)).assertIsDisplayed()
        val position = button.fetchSemanticsNode().boundsInRoot
        button.performClick()
        button.assertIsNotEnabled()
        compose.runOnIdle { result.complete(TtsFailure.Authentication.toError()) }
        compose.onNodeWithText(context.getString(TtsFailure.Authentication.messageRes())).assertIsDisplayed()
        assertEquals(position, button.fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText(text(R.string.action_save)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.tts_api_key)).performTextReplacement("changed")
        compose.onNodeWithText(context.getString(TtsFailure.Authentication.messageRes())).assertDoesNotExist()
    }

    @Test fun savedKeyStaysBlankAndTestDoesNotSaveDraft() {
        var tested: Pair<String, String>? = null
        var saved: Pair<String, String>? = null
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                TtsProviderConfiguration(TtsOnlineSource.Azure, TtsOnlineConfig("eastasia", "encrypted-fixture"),
                    { region, key -> tested = region to key; null }, { region, key -> saved = region to key; null }, {}, onDelete = { null })
            }
        }
        compose.onNodeWithText("encrypted-fixture").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.tts_api_key)).performTextInput("draft-key")
        compose.onNodeWithText(text(R.string.tts_test_connection)).performClick()
        compose.runOnIdle { assertEquals("eastasia" to "draft-key", tested); assertNull(saved) }
        compose.onNodeWithText(text(R.string.tts_connection_success)).assertExists()
        compose.onNodeWithText(text(R.string.action_save)).performClick()
        compose.runOnIdle { assertEquals("eastasia" to "draft-key", saved) }
        compose.onNode(hasSetTextAction() and hasText(text(R.string.tts_api_key))).assertTextEquals(text(R.string.tts_api_key), "")
    }

    @Test fun dismissingDuringTestCancelsItWithoutSaving() {
        val open = mutableStateOf(true)
        var cancelled = false
        var saved = false
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                if (open.value) TtsProviderConfiguration(TtsOnlineSource.GoogleCloud, TtsOnlineConfig(),
                    { _, _ -> try { awaitCancellation() } finally { cancelled = true } },
                    { _, _ -> saved = true; null }, { open.value = false }, onDelete = { null })
            }
        }
        compose.onNodeWithText(text(R.string.tts_test_connection)).performClick()
        compose.onNodeWithText(text(R.string.action_save)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.action_cancel)).performClick()
        compose.runOnIdle { assertTrue(cancelled); assertFalse(saved) }
    }
}
