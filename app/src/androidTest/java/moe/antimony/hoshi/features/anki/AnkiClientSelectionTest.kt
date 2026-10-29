package moe.antimony.hoshi.features.anki

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production selector with an in-memory selection, leaving saved settings intact. */
@RunWith(AndroidJUnit4::class)
class AnkiClientSelectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectsKumaAndThenOfficialClient() {
        val client = mutableStateOf(AnkiDroidClient.AnkiDroid)
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                AnkiClientSelector(client.value) { client.value = it }
            }
        }
        compose.onNodeWithText("AnkiDroid").performClick()
        compose.onNodeWithText("kuma3 Anki").performClick()
        compose.runOnIdle { assertEquals(AnkiDroidClient.Kuma3, client.value) }
        compose.onNodeWithText("kuma3 Anki").assertIsDisplayed()
        compose.onNodeWithText("kuma3 Anki").performClick()
        compose.onNodeWithText("AnkiDroid").performClick()
        compose.runOnIdle { assertEquals(AnkiDroidClient.AnkiDroid, client.value) }
    }
}
