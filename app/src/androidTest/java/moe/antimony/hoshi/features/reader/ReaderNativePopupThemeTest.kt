package moe.antimony.hoshi.features.reader

import android.graphics.drawable.GradientDrawable
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.LinearLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import moe.antimony.hoshi.ui.theme.hoshiSurfaces
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderNativePopupThemeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nativeToolbarAndPickerCloseOnThemeChangeAndPickerUsesOverlayRole() {
        val dark = mutableStateOf(false)
        lateinit var web: HoshiReaderWebView
        var expectedBackground = 0
        compose.setContent {
            HoshiReaderTheme(darkTheme = dark.value, dynamicColor = false) {
                val surfaces = hoshiSurfaces
                expectedBackground = surfaces.overlay.toArgb()
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { HoshiReaderWebView(it).also { web = it } },
                    update = { it.updateNativePopupTheme(dark.value, false, surfaces.overlay.toArgb(), surfaces.outline.toArgb()) })
            }
        }
        compose.waitForIdle()
        for (isDark in listOf(false, true)) {
            compose.runOnIdle { dark.value = isDark }
            compose.waitForIdle()
            compose.runOnIdle { web.showHighlightColorPicker() }
            assertTrue(web.isNativeSelectionActive())
            onView(isAssignableFrom(LinearLayout::class.java)).inRoot(isPlatformPopup()).check { view, error ->
                if (error != null) throw error
                assertEquals(expectedBackground, (view.background as GradientDrawable).color!!.defaultColor)
            }
            compose.runOnIdle { dark.value = !isDark }
            compose.waitForIdle()
            assertFalse(web.isNativeSelectionActive())
        }
        var toolbarDestroyed = false
        compose.runOnIdle {
            assertNotNull(web.startActionMode(object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean { menu.add(android.R.string.copy); return true }
                override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = true
                override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
                override fun onDestroyActionMode(mode: ActionMode) { toolbarDestroyed = true }
            }, ActionMode.TYPE_FLOATING))
            dark.value = !dark.value
        }
        compose.waitForIdle()
        assertTrue(toolbarDestroyed)
    }
}
