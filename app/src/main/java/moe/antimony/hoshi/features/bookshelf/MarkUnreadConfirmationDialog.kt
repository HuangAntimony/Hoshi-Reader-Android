package moe.antimony.hoshi.features.bookshelf

import androidx.compose.runtime.Composable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.BookEntry

@Composable
internal fun MarkUnreadConfirmationDialog(entry: BookEntry, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookshelf_mark_unread_title_format, entry.displayTitle)) },
        text = { Text(stringResource(R.string.bookshelf_mark_unread_confirmation)) },
        confirmButton = {
            TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(R.string.bookshelf_mark_unread))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
