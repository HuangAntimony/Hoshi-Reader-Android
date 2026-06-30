package moe.antimony.hoshi.features.statistics

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StatisticsOverscrollTest {
    @get:Rule
    val composeRule = createComposeRule()

    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun statisticsOverscrollProviderDisablesOverscrollForDescendants() {
        var overscrollDisabled = false

        composeRule.setContent {
            MaterialTheme {
                StatisticsOverscrollDisabled {
                    overscrollDisabled = LocalOverscrollFactory.current == null
                }
            }
        }

        composeRule.runOnIdle {
            assertTrue(overscrollDisabled)
        }
    }
}
