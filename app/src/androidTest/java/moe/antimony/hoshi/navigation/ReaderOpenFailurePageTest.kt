package moe.antimony.hoshi.navigation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderOpenFailurePageTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun genericFailureMessageAndCloseActionAreShown() {
        var closeCount = 0
        composeRule.setContent {
            MaterialTheme {
                ReaderOpenFailurePage(
                    onClose = { closeCount += 1 },
                )
            }
        }

        composeRule.onNodeWithText("Couldn't open book").assertIsDisplayed()
        composeRule.onNodeWithText("Close").performClick()

        assertEquals(1, closeCount)
    }
}
