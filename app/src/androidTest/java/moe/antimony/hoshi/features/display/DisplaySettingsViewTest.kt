package moe.antimony.hoshi.features.display

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
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
import moe.antimony.hoshi.ui.theme.hoshiColorScheme
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
    fun automaticModeSelectsOnePerGroupAndManualModeSelectsOneAcrossBoth() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val confirmed = MutableStateFlow(AppDisplaySettings())
        val model = DisplaySettingsViewModel(
            settings = confirmed,
            updateSettings = { confirmed.value = it(confirmed.value) },
            setAutoSwitchValue = { enabled, dark -> confirmed.value = confirmed.value.withAutoSwitch(enabled, dark) },
            selectPresetValue = { slot, preset -> confirmed.value = confirmed.value.withSelectedPreset(slot, preset) },
            coroutineScope = scope,
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        try {
            composeRule.setContent {
                val settings by confirmed.collectAsState()
                HoshiReaderTheme(darkTheme = resolveDisplaySettings(settings, false).isDark, dynamicColor = false) {
                    DisplaySettingsScreen(onClose = {}, viewModel = model)
                }
            }
            assertPaletteSelections(confirmed.value)
            composeRule.runOnIdle { model.setAutoSwitch(false, false) }
            composeRule.waitUntil { !model.uiState.value.isSaving }
            assertPaletteSelections(confirmed.value)
            composeRule.onNodeWithText(context.getString(R.string.reader_appearance_theme_dark))
                .performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
            composeRule.waitUntil { confirmed.value.manualPaletteSlot == DisplayPaletteSlot.Dark }
            assertPaletteSelections(confirmed.value)
            assertTrue(resolveDisplaySettings(confirmed.value, false).isDark)
            composeRule.runOnIdle { model.setAutoSwitch(true, false) }
            composeRule.waitUntil { !model.uiState.value.isSaving }
            assertPaletteSelections(confirmed.value)
        } finally {
            scope.cancel()
        }
    }

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

    private fun assertPaletteSelections(settings: AppDisplaySettings) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val colors = hoshiColorScheme(resolveDisplaySettings(settings, false).isDark, false)
        val choices = listOf(
            Triple(DisplayPaletteSlot.Light, DisplayPalettePreset.Light, R.string.reader_appearance_theme_light),
            Triple(DisplayPaletteSlot.Light, DisplayPalettePreset.Sepia, R.string.reader_appearance_theme_sepia),
            Triple(DisplayPaletteSlot.Light, DisplayPalettePreset.Custom, R.string.display_settings_custom_light_palette),
            Triple(DisplayPaletteSlot.Dark, DisplayPalettePreset.Dark, R.string.reader_appearance_theme_dark),
            Triple(DisplayPaletteSlot.Dark, DisplayPalettePreset.DarkSepia, R.string.display_settings_palette_dark_sepia),
            Triple(DisplayPaletteSlot.Dark, DisplayPalettePreset.Custom, R.string.display_settings_custom_dark_palette),
        )
        choices.forEach { (slot, preset, label) ->
            val selected = (settings.autoSwitch || settings.manualPaletteSlot == slot) && settings.selection(slot).preset == preset
            val node = composeRule.onNodeWithText(context.getString(label)).performScrollTo().assertIsDisplayed()
            val pixels = node.captureToImage().toPixelMap()
            val radioColors = mutableSetOf<Int>()
            for (y in 0 until pixels.height) for (x in pixels.width * 4 / 5 until pixels.width) {
                radioColors += pixels[x, y].toArgb()
            }
            val expected = if (selected) colors.primary else colors.onSurfaceVariant
            assertTrue("${context.getString(label)} selected=$selected", expected.toArgb() in radioColors)
            if (!selected) assertTrue("Inactive palette must not have a filled radio", colors.primary.toArgb() !in radioColors)
        }
    }

}
