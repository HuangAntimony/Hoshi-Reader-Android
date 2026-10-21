package moe.antimony.hoshi.features.update

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.ErrorDetailsText
import moe.antimony.hoshi.ui.HoshiAlertDialog
import moe.antimony.hoshi.ui.HoshiButton
import moe.antimony.hoshi.ui.asString
import moe.antimony.hoshi.ui.withErrorDetails

/** Page-owned effects must stay alive when the updates card scrolls offscreen. */
@Composable
internal fun PreviewUpdateEffects(model: PreviewUpdateViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val installFailure = stringResource(R.string.preview_download_failed)
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> model.setForeground(true)
                Lifecycle.Event.ON_PAUSE -> model.setForeground(false)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        model.setForeground(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            owner.lifecycle.removeObserver(observer)
            model.setForeground(false)
        }
    }
    LaunchedEffect(state.installRequest) {
        model.takeInstallRequest()?.let { file ->
            try {
                openDownloadedUpdate(context, file)?.let(model::reportInstallMessage)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                model.reportInstallMessage(installFailure.withErrorDetails(error))
            }
        }
    }
    if (state.pendingBuild != null) {
        HoshiAlertDialog(
            onDismissRequest = model::cancelConfirmation,
            title = { Text(stringResource(R.string.preview_install_title)) },
            text = { Text(stringResource(R.string.preview_warning)) },
            confirmButton = {
                HoshiButton(onClick = model::confirmDownload) { Text(stringResource(R.string.preview_download_install)) }
            },
            dismissButton = { TextButton(onClick = model::cancelConfirmation) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    state.error?.let { error ->
        HoshiAlertDialog(
            onDismissRequest = model::dismissError,
            title = { Text(stringResource(R.string.dialog_error_title)) },
            text = { ErrorDetailsText(error.asString()) },
            confirmButton = { TextButton(onClick = model::dismissError) { Text(stringResource(R.string.action_ok)) } },
        )
    }
}

@Composable
internal fun PreviewUpdateControls(model: PreviewUpdateViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val downloading = state.download?.status == UpdateDownloadRecordStatus.Downloading
    val downloaded = state.download?.status == UpdateDownloadRecordStatus.Downloaded
    AboutRow(
        title = stringResource(if (state.loaded) R.string.preview_get_latest else R.string.settings_retry),
        detail = listOfNotNull(
            state.message?.asString(),
            when {
                downloading -> stringResource(R.string.preview_downloading)
                downloaded -> stringResource(R.string.preview_downloaded)
                else -> null
            },
        ).joinToString("\n").ifEmpty { null },
        busy = state.loading || state.busy || state.preparingInstall,
        enabled = !downloading,
        onClick = model::check,
    )
    if (downloaded) AboutRow(stringResource(R.string.preview_install_title),
        enabled = !state.busy && !state.preparingInstall, onClick = model::installDownloaded)
}
