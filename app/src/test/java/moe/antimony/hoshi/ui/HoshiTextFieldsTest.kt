package moe.antimony.hoshi.ui

import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import moe.antimony.hoshi.content.ContentLanguageProfile
import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Test

class HoshiTextFieldsTest {
    @Test
    fun contentKeyboardHintsJapaneseInputAndShowsOnFocus() {
        val options = hoshiContentKeyboardOptions()

        assertEquals("ja-JP", options.hintLocales?.single()?.toLanguageTag())
        assertEquals(KeyboardType.Unspecified, options.keyboardType)
        assertEquals(true, options.showKeyboardOnFocus)
    }

    @Test
    fun contentKeyboardRequestsAsciiInputForEnglishProfiles() {
        val options = hoshiContentKeyboardOptions(ContentLanguageProfile.English)

        assertEquals("en-US", options.hintLocales?.single()?.toLanguageTag())
        assertEquals(KeyboardType.Ascii, options.keyboardType)
        assertEquals(true, options.showKeyboardOnFocus)
    }

    @Test
    fun contentKeyboardPreservesCallerImeAction() {
        assertEquals(ImeAction.Default, hoshiContentKeyboardOptions().imeAction)
        assertEquals(ImeAction.Search, hoshiContentKeyboardOptions(imeAction = ImeAction.Search).imeAction)
    }

    @Test
    fun cursorColorUsesCurrentTextColor() {
        val darkSurfaceText = Color(0xFFECE6F0)

        assertEquals(darkSurfaceText, hoshiTextFieldCursorColor(darkSurfaceText))
    }

    @Test
    fun syncedReplacementClampsSelectionToNewTextLength() {
        val state = TextFieldState("Previous long title", TextRange(19))

        state.replaceTextAndClampSelection("Short")

        assertEquals("Short", state.text.toString())
        assertEquals(TextRange(5), state.selection)
    }

    @Test
    fun replacementCanSelectStartForDialogsThatOpenAtBeginning() {
        val state = TextFieldState("Previous long title", TextRange(19))

        state.replaceTextAndSelectStart("New long title")

        assertEquals("New long title", state.text.toString())
        assertEquals(TextRange.Zero, state.selection)
    }

    @Test
    fun sharedSingleLineLimitUsesHorizontalTextFieldMode() {
        assertEquals(TextFieldLineLimits.SingleLine, hoshiSingleLineTextFieldLineLimits())
    }
}
