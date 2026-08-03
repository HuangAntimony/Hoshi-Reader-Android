package moe.antimony.hoshi.ui.theme

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HoshiReaderThemeOverscrollTest {
    @get:Rule
    val composeRule = createComposeRule()

    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun themeDisablesOverscrollForEveryComposeDescendant() {
        var overscrollDisabled = false

        composeRule.setContent {
            HoshiReaderTheme(dynamicColor = false) {
                overscrollDisabled = LocalOverscrollFactory.current == null
            }
        }

        composeRule.runOnIdle {
            assertTrue(overscrollDisabled)
        }
    }
}
