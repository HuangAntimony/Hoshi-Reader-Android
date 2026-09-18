package moe.antimony.hoshi.features.reader

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import moe.antimony.hoshi.ui.theme.hoshiColorScheme
import moe.antimony.hoshi.ui.theme.hoshiSurfaceRoles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderAppearanceSelectionTest {
    @get:Rule val composeRule = createComposeRule()
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun lightSelectionsStayInsideVisibleTrack() = checkSelections(dark = false, eInk = false)
    @Test fun darkSelectionsStayInsideVisibleTrack() = checkSelections(dark = true, eInk = false)
    @Test fun lightEInkSelectionsUseInverseFill() = checkSelections(dark = false, eInk = true)
    @Test fun darkEInkSelectionsUseInverseFill() = checkSelections(dark = true, eInk = true)

    private fun checkSelections(dark: Boolean, eInk: Boolean) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val fontManager = ReaderFontManager(temporaryFolder.root, scope, Dispatchers.IO)
        val model = ReaderAppearanceViewModel(
            fontManager,
            ReaderFontDownloaderFactory(HttpReaderFontRemoteDataSource(), Dispatchers.IO),
            Dispatchers.IO,
        )
        val modelStore = ViewModelStore().apply { put("appearance", model) }
        val settings = mutableStateOf(ReaderSettings(
            verticalWriting = true,
            furiganaMode = FuriganaMode.Toggle,
            viewMode = ReaderViewMode.VisualNovel,
            visualNovelScreenMode = VisualNovelScreenMode.Block,
            showProgress = true,
            alwaysShowProgress = false,
            showProgressTop = true,
        ))
        val scheme = hoshiColorScheme(dark, eInk, accentSeed = 0xFF2E7D32)
        val surfaces = hoshiSurfaceRoles(scheme, dark, eInk)
        val selectedFill = surfaces.selected
        val selectedText = surfaces.onSelected
        val track = surfaces.nested
        try {
            composeRule.setContent {
                HoshiReaderTheme(darkTheme = dark, eInkMode = eInk, accentSeed = 0xFF2E7D32) {
                    ReaderAppearanceScreen(
                        settings = settings.value,
                        profileName = "Selection test",
                        onSettingsChange = { settings.value = it(settings.value) },
                        sasayakiSettings = SasayakiSettings(),
                        onSasayakiSettingsChange = {},
                        fontManager = fontManager,
                        onClose = {},
                        viewModel = model,
                    )
                }
            }
            val context = ApplicationProvider.getApplicationContext<Context>()
            val vertical = context.getString(R.string.reader_appearance_vertical)
            val horizontal = context.getString(R.string.reader_appearance_horizontal)
            assertSegmentColors(vertical, selectedFill, selectedText, track)
            // Exercise selection without a transient touch ripple in the pixel-color assertion.
            composeRule.onNodeWithText(horizontal).performSemanticsAction(SemanticsActions.OnClick) { it() }
            composeRule.runOnIdle { assertFalse(settings.value.verticalWriting) }
            assertSegmentColors(
                vertical,
                if (eInk) scheme.surface else scheme.surfaceContainer,
                scheme.onSurface,
            )
            val selectedLabels = listOf(
                R.string.reader_appearance_horizontal,
                FuriganaMode.Toggle.labelResId,
                R.string.reader_appearance_visual_novel,
                R.string.reader_visual_novel_screen_mode_block,
                R.string.reader_appearance_progress_top,
            ).map(context::getString)
            selectedLabels.forEach { assertSegmentColors(it, selectedFill, selectedText, track) }
        } finally {
            composeRule.runOnIdle { modelStore.clear() }
            scope.cancel()
        }
    }

    private fun assertSegmentColors(label: String, background: Color, text: Color, track: Color? = null) {
        val pixels = composeRule.onNodeWithText(label).performScrollTo().captureToImage().toPixelMap()
        val counts = mutableMapOf<Int, Int>()
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y].toArgb()
            counts[color] = (counts[color] ?: 0) + 1
        }
        assertEquals("$label fill", background.toArgb(), counts.maxBy { it.value }.key)
        assertTrue("$label text must use its matching content color", counts.getOrDefault(text.toArgb(), 0) > 0)
        track?.let {
            assertEquals(
                "$label selection must leave the track visible above it",
                it.toArgb(),
                pixels[pixels.width / 2, (pixels.height / 32).coerceAtLeast(1)].toArgb(),
            )
        }
    }
}
