package moe.antimony.hoshi.features.sync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import moe.antimony.hoshi.LocalHoshiUiDependencies
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.HoshiAlertDialog as AlertDialog

@Composable
internal fun SyncAttachPrompt() {
    val manager = LocalHoshiUiDependencies.current.googleDriveSyncManager
    val state by manager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var attaching by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<AttachChoice?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun attach(choice: AttachChoice) {
        confirmation = null
        attaching = true
        error = null
        scope.launch {
            try {
                manager.attach(choice)
            } catch (exception: Exception) {
                error = exception.message
            }
            attaching = false
        }
    }

    if (!state.attachPending || state.attachDismissed || attaching) return
    val choice = confirmation
    if (choice == null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.sync_attach_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.sync_attach_message))
                    error?.let { Text(it, color = colorScheme.error) }
                }
            },
            confirmButton = {
                Column(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { attach(AttachChoice.Merge) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.reader_statistics_sync_mode_merge))
                    }
                    TextButton(
                        onClick = { confirmation = AttachChoice.Cloud },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(contentColor = colorScheme.error),
                    ) {
                        Text(stringResource(R.string.sync_attach_download))
                    }
                    TextButton(
                        onClick = { confirmation = AttachChoice.Device },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(contentColor = colorScheme.error),
                    ) {
                        Text(stringResource(R.string.sync_attach_upload))
                    }
                    TextButton(onClick = { manager.setAttachDismissed(true) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            },
        )
    } else {
        val device = choice == AttachChoice.Device
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(stringResource(if (device) R.string.sync_attach_upload_title else R.string.sync_attach_download_title)) },
            text = { Text(stringResource(if (device) R.string.sync_attach_upload_confirmation else R.string.sync_attach_download_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = { attach(choice) },
                    colors = ButtonDefaults.textButtonColors(contentColor = colorScheme.error),
                ) {
                    Text(stringResource(R.string.reader_statistics_sync_mode_replace))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
