package moe.antimony.hoshi.features.bookshelf

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import moe.antimony.hoshi.ui.HoshiAlertDialog as AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.BookShelf
import moe.antimony.hoshi.ui.asString
import moe.antimony.hoshi.ui.hoshiOutlinedTextFieldColors
import moe.antimony.hoshi.ui.hoshiSingleLineTextFieldLineLimits
import moe.antimony.hoshi.ui.rememberSyncedTextFieldState

internal enum class NewShelfNameValidation {
    Blank,
    Duplicate,
    Valid,
}

internal fun validateNewShelfName(
    name: String,
    shelves: List<BookShelf>,
): NewShelfNameValidation {
    val trimmedName = name.trim()
    return when {
        trimmedName.isEmpty() -> NewShelfNameValidation.Blank
        shelves.any { it.name == trimmedName } -> NewShelfNameValidation.Duplicate
        else -> NewShelfNameValidation.Valid
    }
}

@Composable
internal fun NewShelfMoveDialog(
    shelves: List<BookShelf>,
    name: String,
    status: ShelfCreationMoveStatus,
    onNameChanged: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val nameScrollState = rememberScrollState()
    val nameState = rememberSyncedTextFieldState(
        value = name,
        onValueChange = onNameChanged,
        scrollState = nameScrollState,
    )
    val validation = validateNewShelfName(name, shelves)
    val isSubmitting = status is ShelfCreationMoveStatus.Submitting
    val errorText = when {
        validation == NewShelfNameValidation.Duplicate -> stringResource(R.string.bookshelf_shelf_name_exists)
        status is ShelfCreationMoveStatus.Failed -> status.message.asString()
        else -> null
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookshelf_create_shelf)) },
        text = {
            OutlinedTextField(
                state = nameState,
                label = { Text(stringResource(R.string.bookshelf_shelf_name)) },
                lineLimits = hoshiSingleLineTextFieldLineLimits(),
                scrollState = nameScrollState,
                colors = hoshiOutlinedTextFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                isError = errorText != null,
                supportingText = errorText?.let { message ->
                    { Text(message) }
                },
                enabled = !isSubmitting,
                modifier = Modifier.focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = validation == NewShelfNameValidation.Valid && !isSubmitting,
            ) {
                Text(stringResource(R.string.bookshelf_create_and_move))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
