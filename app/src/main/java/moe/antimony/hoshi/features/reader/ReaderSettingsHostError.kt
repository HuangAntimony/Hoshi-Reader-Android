package moe.antimony.hoshi.features.reader

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.HoshiAlertDialog
import moe.antimony.hoshi.ui.asString

@Composable
internal fun ReaderSettingsHostError(state: ReaderSettingsHostState, viewModel: ReaderSettingsHostViewModel) {
    state.error?.let { error ->
        HoshiAlertDialog(
            onDismissRequest = { if (state.settings != null) viewModel.dismissError() },
            title = { Text(stringResource(R.string.dialog_error_title)) },
            text = { Text(error.asString()) },
            confirmButton = {
                TextButton(onClick = { if (state.settings == null) viewModel.retry() else viewModel.dismissError() }) {
                    Text(stringResource(if (state.settings == null) R.string.reader_appearance_font_retry else R.string.action_ok))
                }
            },
        )
    }
}
