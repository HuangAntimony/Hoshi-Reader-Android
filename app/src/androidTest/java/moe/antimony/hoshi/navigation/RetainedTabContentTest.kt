package moe.antimony.hoshi.navigation

import android.webkit.WebView
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RetainedTabContentTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun inactiveTabKeepsWebViewAttachedButCannotHandleBack() {
        val active = mutableStateOf(true)
        lateinit var view: WebView
        lateinit var lifecycleOwner: LifecycleOwner
        lateinit var dispatcher: OnBackPressedDispatcher
        var created = 0
        var dictionaryBack = 0
        var otherBack = 0
        composeRule.setContent {
            dispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            Box(Modifier.fillMaxSize()) {
                BackHandler(enabled = !active.value) { otherBack++ }
                RetainedTabContent(active.value) {
                    lifecycleOwner = LocalLifecycleOwner.current
                    BackHandler { dictionaryBack++ }
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context -> WebView(context).also { view = it; created++ } },
                        onRelease = { it.destroy() },
                    )
                }
            }
        }
        composeRule.runOnIdle {
            assertTrue(view.isAttachedToWindow)
            active.value = false
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue("Switching tabs must not detach the WebView compositor", view.isAttachedToWindow)
            assertEquals(Lifecycle.State.CREATED, lifecycleOwner.lifecycle.currentState)
            dispatcher.onBackPressed()
            assertEquals(1, otherBack)
            assertEquals(0, dictionaryBack)
            active.value = true
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(1, created)
            assertTrue(view.isAttachedToWindow)
            assertEquals(Lifecycle.State.RESUMED, lifecycleOwner.lifecycle.currentState)
            dispatcher.onBackPressed()
            assertEquals(1, dictionaryBack)
        }
    }
}
