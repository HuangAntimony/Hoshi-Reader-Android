package moe.antimony.hoshi.features.anki

import androidx.annotation.StringRes
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.ErrorDetailsText
import moe.antimony.hoshi.ui.HoshiAlertDialog
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.asString
import moe.antimony.hoshi.ui.withErrorDetails

@Composable
internal fun AnkiOperationErrorDialog(
    error: Throwable?,
    @StringRes messageRes: Int = R.string.anki_operation_failed,
    onDismiss: () -> Unit,
) {
    if (error == null) return
    HoshiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_error_title)) },
        text = { ErrorDetailsText(UiText.Resource(messageRes).withErrorDetails(error).asString()) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
    )
}
