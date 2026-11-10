package moe.antimony.hoshi.features.dictionary

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isEditable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import moe.antimony.hoshi.features.reader.ReaderFontManager
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class DictionaryCustomCssViewTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun keyboardLeavesTheWholeEditorAboveIt() {
        showEditor("body { color: red; }")
        val field = compose.onNode(isEditable())
        field.performTouchInput { click(topLeft + Offset(12f, 12f)) }

        compose.waitUntil(5_000) { keyboardTopInWindow() != null }
        compose.waitUntil(5_000) {
            val keyboardTop = keyboardTopInWindow() ?: return@waitUntil false
            field.fetchSemanticsNode().boundsInWindow.bottom <= keyboardTop
        }
    }

    @Test
    fun firstTapKeepsTheSelectedCssLineVisible() {
        val css = (0..99).joinToString("\n") { ".item-$it { color: red; }" }
        showEditor(css)
        val field = compose.onNode(isEditable())

        field.performTouchInput { click(topLeft + Offset(12f, 12f)) }
        compose.waitUntil(5_000) { keyboardTopInWindow() != null }
        val selection = field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertTrue("Expected the caret on the first line, got $selection", selection.end < css.indexOf('\n'))

        field.performTouchInput {
            advanceEventTime(600)
            click(topLeft + Offset(12f, 12f))
        }
        assertEquals(selection, field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
    }

    private fun showEditor(css: String) {
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        val settings = DictionarySettings(customCSS = css)
        val fontManager = ReaderFontManager(compose.activity.cacheDir)
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                DictionaryCustomCssView(
                    settings = settings,
                    termDictionaries = emptyList(),
                    fontManager = fontManager,
                    onSettingsChange = {},
                    onClose = {},
                )
            }
        }
    }

    private fun keyboardTopInWindow(): Int? = compose.runOnIdle {
        val decor = compose.activity.window.decorView
        val ime = WindowInsetsCompat.Type.ime()
        val insets = ViewCompat.getRootWindowInsets(decor) ?: return@runOnIdle null
        if (insets.isVisible(ime)) decor.height - insets.getInsets(ime).bottom else null
    }
}
