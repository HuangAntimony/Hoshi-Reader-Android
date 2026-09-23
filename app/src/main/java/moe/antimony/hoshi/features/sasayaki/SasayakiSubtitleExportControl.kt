package moe.antimony.hoshi.features.sasayaki

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.ui.HoshiAlertDialog
import moe.antimony.hoshi.ui.HoshiDropdownMenu
import moe.antimony.hoshi.ui.asString

@Composable
internal fun SasayakiSubtitleExportControl(
    bookTitle: String,
    match: SasayakiMatchData?,
    viewModel: SasayakiSubtitleExportViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(enabled = match?.matches?.isNotEmpty() == true && !state.busy, onClick = { expanded = true }) {
            Text(stringResource(R.string.sasayaki_export_srt))
        }
        HoshiDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for ((action, label) in listOf(SasayakiSubtitleExportAction.Save to R.string.action_save, SasayakiSubtitleExportAction.Share to R.string.action_share)) {
                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                    expanded = false
                    match?.let { viewModel.prepare(bookTitle, it, action) }
                })
            }
        }
    }
}

/** Keep the launcher registered with the Reader even when the sheet closes or is recreated. */
@Composable
internal fun SasayakiSubtitleExportHost(viewModel: SasayakiSubtitleExportViewModel) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(SasayakiSrt.mimeType), viewModel::save)
    LaunchedEffect(state.request) {
        val request = state.request ?: return@LaunchedEffect
        // Consume before launching, so returning/recomposition cannot reopen the chooser.
        viewModel.launched()
        try {
            when (request.action) {
                SasayakiSubtitleExportAction.Save -> save.launch(request.file.name)
                SasayakiSubtitleExportAction.Share -> context.startActivity(Intent.createChooser(sasayakiSubtitleShareIntent(context, request.file), null))
            }
        } catch (_: Exception) { viewModel.launchFailed() }
    }
    state.message?.let { message ->
        HoshiAlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            title = { Text(stringResource(R.string.sasayaki_export_srt)) },
            text = { Text(message.asString()) },
            confirmButton = { TextButton(onClick = viewModel::dismissMessage) { Text(stringResource(R.string.action_ok)) } },
        )
    }
}

internal fun sasayakiSubtitleShareIntent(context: Context, file: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    return Intent(Intent.ACTION_SEND).apply {
        type = SasayakiSrt.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
