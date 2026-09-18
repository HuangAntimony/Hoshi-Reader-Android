package moe.antimony.hoshi.features.update

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import moe.antimony.hoshi.ui.HoshiAlertDialog as AlertDialog
import moe.antimony.hoshi.ui.HoshiButton as Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.BuildConfig
import moe.antimony.hoshi.LocalHoshiUiDependencies
import moe.antimony.hoshi.R

@Composable
internal fun DownloadedUpdatePrompt(
    currentVersionName: String = BuildConfig.VERSION_NAME,
    initialRecord: UpdateDownloadRecord? = UpdateStartupSnapshot.initialRecord,
) {
    val context = LocalContext.current
    val appContainer = LocalHoshiUiDependencies.current
    val scope = rememberCoroutineScope()
    val record by appContainer.updateDownloadStore.record.collectAsStateWithLifecycle(initialValue = null)
    val liveAvailableKey by appContainer.updatePromptEvents.availablePromptKey.collectAsStateWithLifecycle(
        initialValue = null,
    )
    var dismissedAvailableKey by rememberSaveable { mutableStateOf<String?>(null) }
    var dismissedDownloadedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var promptMessage by rememberSaveable { mutableStateOf<String?>(null) }

    val initialAvailableKey = initialRecord
        ?.takeIf { it.shouldPromptForAvailable(currentVersionName) }
        ?.promptKey()
    val availablePromptKeys = setOfNotNull(initialAvailableKey, liveAvailableKey)
    val availableRecord = record
        ?.takeIf { it.shouldPromptForAvailable(currentVersionName) }
        ?.takeIf { it.promptKey() in availablePromptKeys }
        ?.takeIf { it.promptKey() != dismissedAvailableKey }
    val downloadedRecord = record
        ?.takeIf { it.shouldPromptForInstall(currentVersionName) }
        ?.takeIf { appContainer.updateDownloadManager.updateFile(it.fileName).isFile }
        ?.takeIf { it.promptKey() != dismissedDownloadedKey }

    when {
        availableRecord != null -> {
            AvailableUpdatePromptDialog(
                versionName = availableRecord.versionName,
                message = promptMessage,
                onLater = {
                    val promptKey = availableRecord.promptKey()
                    dismissedAvailableKey = promptKey
                    appContainer.updatePromptEvents.consumeAvailablePrompt(promptKey)
                    promptMessage = null
                },
                onSkip = {
                    val update = availableRecord.toAvailableUpdate() ?: return@AvailableUpdatePromptDialog
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            appContainer.updateDownloadStore.skip(update)
                        }
                        val promptKey = availableRecord.promptKey()
                        dismissedAvailableKey = promptKey
                        appContainer.updatePromptEvents.consumeAvailablePrompt(promptKey)
                        promptMessage = null
                    }
                },
                onDownload = {
                    val update = availableRecord.toAvailableUpdate() ?: return@AvailableUpdatePromptDialog
                    scope.launch {
                        val message = runCatching {
                            withContext(Dispatchers.IO) {
                                appContainer.updateDownloadManager.enqueue(update)
                            }
                        }.exceptionOrNull()?.localizedMessage
                        if (message == null) {
                            val promptKey = availableRecord.promptKey()
                            dismissedAvailableKey = promptKey
                            appContainer.updatePromptEvents.consumeAvailablePrompt(promptKey)
                            promptMessage = null
                        } else {
                            promptMessage = message
                        }
                    }
                },
            )
        }
        downloadedRecord != null -> {
            AlertDialog(
                onDismissRequest = {
                    dismissedDownloadedKey = downloadedRecord.promptKey()
                    promptMessage = null
                },
                title = { Text(stringResource(R.string.update_downloaded_title)) },
                text = {
                    Text(
                        promptMessage
                            ?: stringResource(R.string.update_downloaded_message_format, downloadedRecord.versionName),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val file = appContainer.updateDownloadManager.updateFile(downloadedRecord.fileName)
                            val message = openDownloadedUpdate(context, file)
                            if (message == null) {
                                dismissedDownloadedKey = downloadedRecord.promptKey()
                                promptMessage = null
                            } else {
                                promptMessage = message
                            }
                        },
                    ) {
                        Text(stringResource(R.string.action_install))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            dismissedDownloadedKey = downloadedRecord.promptKey()
                            promptMessage = null
                        },
                    ) {
                        Text(stringResource(R.string.action_later))
                    }
                },
            )
        }
    }
}

@Composable
internal fun AvailableUpdatePromptDialog(
    versionName: String,
    message: String?,
    onLater: () -> Unit,
    onSkip: () -> Unit,
    onDownload: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.update_available_title)) },
        text = {
            Text(message ?: stringResource(R.string.update_available_message_format, versionName))
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onLater) {
                    Text(stringResource(R.string.action_later))
                }
                TextButton(onClick = onSkip) {
                    Text(stringResource(R.string.action_skip))
                }
                Button(onClick = onDownload) {
                    Text(stringResource(R.string.action_download))
                }
            }
        },
    )
}

internal fun UpdateDownloadRecord.shouldPromptForAvailable(currentVersionName: String): Boolean {
    if (status != UpdateDownloadRecordStatus.Available) return false
    return shouldSurfaceInAbout(currentVersionName)
}

internal fun UpdateDownloadRecord.shouldPromptForManualAvailable(currentVersionName: String): Boolean {
    if (status != UpdateDownloadRecordStatus.Available && status != UpdateDownloadRecordStatus.Skipped) return false
    return shouldSurfaceInAbout(currentVersionName)
}

internal fun UpdateDownloadRecord.shouldPromptForInstall(currentVersionName: String): Boolean {
    if (status != UpdateDownloadRecordStatus.Downloaded) return false
    return shouldSurfaceInAbout(currentVersionName)
}

internal fun UpdateDownloadRecord.shouldSurfaceInAbout(currentVersionName: String): Boolean {
    val downloadedVersion = AppVersion.parse(versionName) ?: return false
    val currentVersion = AppVersion.parse(currentVersionName) ?: return false
    return downloadedVersion > currentVersion
}

internal fun UpdateDownloadRecord.promptKey(): String =
    listOf(versionName, assetName, sha256.orEmpty()).joinToString(separator = "|")

internal fun AvailableUpdate.promptKey(): String =
    listOf(versionName, assetName, sha256.orEmpty()).joinToString(separator = "|")

internal object UpdateStartupSnapshot {
    @Volatile
    var initialRecord: UpdateDownloadRecord? = null
}
