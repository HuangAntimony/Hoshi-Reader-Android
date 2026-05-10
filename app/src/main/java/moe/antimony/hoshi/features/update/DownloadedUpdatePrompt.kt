package moe.antimony.hoshi.features.update

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import moe.antimony.hoshi.BuildConfig
import moe.antimony.hoshi.LocalHoshiAppContainer

@Composable
internal fun DownloadedUpdatePrompt(
    currentVersionName: String = BuildConfig.VERSION_NAME,
) {
    val context = LocalContext.current
    val appContainer = LocalHoshiAppContainer.current
    val record by appContainer.updateDownloadStore.record.collectAsState(initial = null)
    var dismissedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var installerMessage by rememberSaveable { mutableStateOf<String?>(null) }

    val promptRecord = record
        ?.takeIf { it.shouldPromptForInstall(currentVersionName) }
        ?.takeIf { appContainer.updateDownloadManager.updateFile(it.fileName).isFile }
        ?.takeIf { it.promptKey() != dismissedKey }

    if (promptRecord != null) {
        AlertDialog(
            onDismissRequest = {
                dismissedKey = promptRecord.promptKey()
                installerMessage = null
            },
            title = { Text("Update Downloaded") },
            text = {
                Text(
                    installerMessage
                        ?: "Update ${promptRecord.versionName} has been downloaded. Install it now?",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val file = appContainer.updateDownloadManager.updateFile(promptRecord.fileName)
                        val message = openDownloadedUpdate(context, file)
                        if (message == null) {
                            dismissedKey = promptRecord.promptKey()
                            installerMessage = null
                        } else {
                            installerMessage = message
                        }
                    },
                ) {
                    Text("Install")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        dismissedKey = promptRecord.promptKey()
                        installerMessage = null
                    },
                ) {
                    Text("Later")
                }
            },
        )
    }
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

private fun UpdateDownloadRecord.promptKey(): String =
    listOf(versionName, assetName, sha256.orEmpty()).joinToString(separator = "|")
