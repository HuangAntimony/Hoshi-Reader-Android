package moe.antimony.hoshi.features.display

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DisplaySettingsViewTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun savingAccentKeepsPreviewAndRowPositionsUntilConfirmed() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val confirmed = MutableStateFlow(AppDisplaySettings(autoSwitch = false))
        val finishSaving = CompletableDeferred<Unit>()
        val model = DisplaySettingsViewModel(
            settings = confirmed,
            updateSettings = { transform ->
                finishSaving.await()
                confirmed.value = transform(confirmed.value)
            },
            setAutoSwitchValue = { _, _ -> error("Unexpected automatic-mode edit") },
            selectPresetValue = { _, _ -> error("Unexpected palette edit") },
            coroutineScope = scope,
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preview = composeRule.onNodeWithText(context.getString(R.string.display_settings_accent_preview))
        val blue = composeRule.onNodeWithText(context.getString(R.string.display_settings_accent_blue))
        try {
            composeRule.setContent {
                val settings by confirmed.collectAsState()
                HoshiReaderTheme(
                    accentSeed = settings.accentSeed.takeIf { settings.accentSource == DisplayAccentSource.Custom },
                ) {
                    DisplaySettingsScreen(onClose = {}, viewModel = model)
                }
            }
            preview.performScrollTo().assertIsDisplayed()
            val previewBounds = preview.getUnclippedBoundsInRoot()
            val blueBounds = blue.getUnclippedBoundsInRoot()

            blue.performClick()

            composeRule.runOnIdle { assertTrue(model.uiState.value.isSaving) }
            preview.assertIsDisplayed()
            assertEquals(previewBounds, preview.getUnclippedBoundsInRoot())
            assertEquals(blueBounds, blue.getUnclippedBoundsInRoot())

            composeRule.runOnIdle { finishSaving.complete(Unit) }
            composeRule.waitUntil { !model.uiState.value.isSaving }
            assertEquals(DisplayAccentSource.Custom, confirmed.value.accentSource)
            assertEquals(previewBounds, preview.getUnclippedBoundsInRoot())
            assertEquals(blueBounds, blue.getUnclippedBoundsInRoot())
        } finally {
            scope.cancel()
        }
    }
}
