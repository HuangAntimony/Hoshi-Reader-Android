package moe.antimony.hoshi.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import moe.antimony.hoshi.content.ContentLanguageProfile
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.flow.distinctUntilChanged

fun hoshiContentKeyboardOptions(
    contentLanguageProfile: ContentLanguageProfile = ContentLanguageProfile.Default,
    imeAction: ImeAction = ImeAction.Default,
): KeyboardOptions = KeyboardOptions(
    keyboardType = if (contentLanguageProfile.dictionaryLanguageId == ContentLanguageProfile.EnglishLanguageId) {
        KeyboardType.Ascii
    } else {
        KeyboardType.Unspecified
    },
    imeAction = imeAction,
    showKeyboardOnFocus = true,
    hintLocales = LocaleList(Locale(contentLanguageProfile.inputLocaleTag)),
)

internal fun hoshiTextFieldCursorColor(textColor: Color): Color = textColor

@Composable
fun hoshiTextFieldCursorBrush(
    textColor: Color = MaterialTheme.colorScheme.onSurface,
): Brush = SolidColor(hoshiTextFieldCursorColor(textColor))

@Composable
fun hoshiTextSelectionColors(
    textColor: Color = MaterialTheme.colorScheme.onSurface,
): TextSelectionColors = TextSelectionColors(
    handleColor = hoshiTextFieldCursorColor(textColor),
    backgroundColor = hoshiTextFieldCursorColor(textColor).copy(alpha = 0.32f),
)

@Composable
fun hoshiOutlinedTextFieldColors(
    cursorColor: Color = MaterialTheme.colorScheme.onSurface,
    errorCursorColor: Color = MaterialTheme.colorScheme.error,
): TextFieldColors = OutlinedTextFieldDefaults.colors(
    cursorColor = hoshiTextFieldCursorColor(cursorColor),
    errorCursorColor = errorCursorColor,
    selectionColors = hoshiTextSelectionColors(cursorColor),
)

internal fun hoshiSingleLineTextFieldLineLimits(): TextFieldLineLimits =
    TextFieldLineLimits.SingleLine

internal fun TextFieldState.replaceTextAndClampSelection(value: String) {
    edit {
        replace(0, length, value)
        val clamped = selection.start.coerceIn(0, value.length)
        selection = TextRange(clamped)
    }
}

internal fun TextFieldState.replaceTextAndSelectStart(value: String) {
    edit {
        replace(0, length, value)
        selection = TextRange.Zero
    }
}

@Composable
fun rememberSyncedTextFieldState(
    value: String,
    onValueChange: (String) -> Unit,
    scrollState: ScrollState = rememberScrollState(),
): TextFieldState {
    val state = rememberTextFieldState(value)
    val latestValue by rememberUpdatedState(value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)

    LaunchedEffect(value, state) {
        if (state.text.toString() != value) {
            state.replaceTextAndClampSelection(value)
            scrollState.scrollTo(0)
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }
            .distinctUntilChanged()
            .collect { text ->
                if (text != latestValue) {
                    latestOnValueChange(text)
                }
            }
    }

    return state
}
