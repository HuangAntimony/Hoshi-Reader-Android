package moe.antimony.hoshi.features.update

import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.BuildConfig
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold
import moe.antimony.hoshi.features.settings.SettingsLoadState
import moe.antimony.hoshi.features.settings.collectAsSettingsLoadState
import moe.antimony.hoshi.features.storage.StorageCleanupReport
import java.io.File
import java.util.Locale

private const val GitHubRepositoryUrl = "https://github.com/HuangAntimony/Hoshi-Reader-Android"

@Composable
fun AboutScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContainer = LocalHoshiAppContainer.current
    val scope = rememberCoroutineScope()
    val recordLoadState = appContainer.updateDownloadStore.record.collectAsSettingsLoadState()
    val record = (recordLoadState as? SettingsLoadState.Loaded)?.value
    val actionableRecord = record?.takeIf { it.shouldSurfaceInAbout(BuildConfig.VERSION_NAME) }
    var checkState by remember { mutableStateOf<AboutUpdateCheckState>(AboutUpdateCheckState.Idle) }
    var cleanupState by remember { mutableStateOf<StorageCleanupUiState>(StorageCleanupUiState.Idle) }
    var pendingCleanupConfirmation by remember { mutableStateOf<StorageCleanupReport?>(null) }
    var pendingAvailableUpdate by remember { mutableStateOf<AvailableUpdate?>(null) }
    var updatePromptMessage by remember { mutableStateOf<String?>(null) }

    pendingCleanupConfirmation?.let { report ->
        AlertDialog(
            onDismissRequest = { pendingCleanupConfirmation = null },
            title = { Text("Clean Up Storage") },
            text = {
                Text(
                    "Delete ${formatStorageSize(report.totalSizeBytes)} of cache and leftover files? " +
                        "This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingCleanupConfirmation = null
                        scope.launch {
                            cleanupState = StorageCleanupUiState.Cleaning(report)
                            cleanupState = runCatching {
                                withContext(Dispatchers.IO) {
                                    appContainer.storageCleanupRepository.clean(report)
                                }
                            }.fold(
                                onSuccess = { StorageCleanupUiState.Ready(it) },
                                onFailure = { error ->
                                    StorageCleanupUiState.Error(
                                        error.localizedMessage ?: "Unable to clean up storage.",
                                    )
                                },
                            )
                        }
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingCleanupConfirmation = null }) {
                    Text("Cancel")
                }
            },
        )
    }
    pendingAvailableUpdate?.let { update ->
        AvailableUpdatePromptDialog(
            versionName = update.versionName,
            message = updatePromptMessage,
            onLater = {
                pendingAvailableUpdate = null
                updatePromptMessage = null
            },
            onSkip = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        appContainer.updateDownloadStore.skip(update)
                    }
                    pendingAvailableUpdate = null
                    updatePromptMessage = null
                }
            },
            onDownload = {
                scope.launch {
                    val outcome = runCatching {
                        withContext(Dispatchers.IO) {
                            appContainer.updateCheckService.download(update)
                        }
                    }.fold(
                        onSuccess = { AboutUpdateCheckState.Result(it) },
                        onFailure = { error ->
                            AboutUpdateCheckState.Error(error.localizedMessage ?: "Failed to download update.")
                        },
                    )
                    checkState = outcome
                    if (outcome is AboutUpdateCheckState.Error) {
                        updatePromptMessage = outcome.message
                    } else {
                        pendingAvailableUpdate = null
                        updatePromptMessage = null
                    }
                }
            },
        )
    }

    SettingsDetailScaffold(
        title = "About",
        onClose = onClose,
        modifier = modifier,
    ) { innerPadding ->
        if (recordLoadState !is SettingsLoadState.Loaded) return@SettingsDetailScaffold
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                AboutCard {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                        headlineContent = { Text("Hoshi Reader") },
                        supportingContent = {
                            Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        },
                    )
                }
            }
            item {
                AboutCard {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = "GitHub",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "If you like this app, consider starring the project on GitHub.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(GitHubRepositoryUrl)),
                                )
                            },
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text("GitHub")
                        }
                    }
                }
            }
            item {
                AboutCard {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = "Storage Cleanup",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = storageCleanupStatusText(cleanupState),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val report = cleanupState.reportOrNull()
                        if (report != null && report.categories.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            report.categories.forEach { category ->
                                Text(
                                    text = "${category.title}: ${formatStorageSize(category.sizeBytes)} (${formatItemCount(category.itemCount)})",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        cleanupState = StorageCleanupUiState.Scanning
                                        cleanupState = runCatching {
                                            withContext(Dispatchers.IO) {
                                                appContainer.storageCleanupRepository.scan()
                                            }
                                        }.fold(
                                            onSuccess = { StorageCleanupUiState.Ready(it) },
                                            onFailure = { error ->
                                                StorageCleanupUiState.Error(
                                                    error.localizedMessage ?: "Unable to scan storage.",
                                                )
                                            },
                                        )
                                    }
                                },
                                enabled = !cleanupState.isBusy,
                            ) {
                                if (cleanupState == StorageCleanupUiState.Scanning) {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .padding(end = 8.dp)
                                            .size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.CleaningServices,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 8.dp),
                                    )
                                }
                                Text("Scan")
                            }
                            if (report?.hasCleanableItems == true) {
                                OutlinedButton(
                                    onClick = { pendingCleanupConfirmation = report },
                                    enabled = !cleanupState.isBusy,
                                ) {
                                    Text("Clean Up")
                                }
                            }
                        }
                    }
                }
            }
            item {
                AboutCard {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = "Updates",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = updateStatusText(checkState, actionableRecord),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        checkState = AboutUpdateCheckState.Checking
                                        checkState = runCatching {
                                            withContext(Dispatchers.IO) {
                                                appContainer.updateCheckService.check(ignoreSkipped = true)
                                            }
                                        }.fold(
                                            onSuccess = { outcome ->
                                                if (outcome is UpdateCheckOutcome.Available) {
                                                    pendingAvailableUpdate = outcome.update
                                                }
                                                AboutUpdateCheckState.Result(outcome)
                                            },
                                            onFailure = { error ->
                                                AboutUpdateCheckState.Error(
                                                    error.localizedMessage ?: "Failed to check for updates.",
                                                )
                                            },
                                        )
                                    }
                                },
                                enabled = checkState !is AboutUpdateCheckState.Checking,
                            ) {
                                if (checkState is AboutUpdateCheckState.Checking) {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .padding(end = 8.dp)
                                            .size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                                Text("Check for Updates")
                            }
                            val downloadedFile = actionableRecord
                                ?.takeIf { it.status == UpdateDownloadRecordStatus.Downloaded }
                                ?.let { appContainer.updateDownloadManager.updateFile(it.fileName) }
                                ?.takeIf(File::isFile)
                            val availableUpdate = actionableRecord
                                ?.takeIf {
                                    it.status == UpdateDownloadRecordStatus.Available ||
                                        it.status == UpdateDownloadRecordStatus.Skipped ||
                                        it.status == UpdateDownloadRecordStatus.Failed
                                }
                                ?.toAvailableUpdate()
                            if (availableUpdate != null) {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            checkState = runCatching {
                                                withContext(Dispatchers.IO) {
                                                    appContainer.updateCheckService.download(availableUpdate)
                                                }
                                            }.fold(
                                                onSuccess = { AboutUpdateCheckState.Result(it) },
                                                onFailure = { error ->
                                                    AboutUpdateCheckState.Error(
                                                        error.localizedMessage ?: "Failed to download update.",
                                                    )
                                                },
                                            )
                                        }
                                    },
                                    enabled = checkState !is AboutUpdateCheckState.Checking,
                                ) {
                                    Text("Download")
                                }
                            }
                            if (downloadedFile != null) {
                                OutlinedButton(
                                    onClick = {
                                        openDownloadedUpdate(context, downloadedFile)?.let { message ->
                                            checkState = AboutUpdateCheckState.Error(message)
                                        }
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 8.dp),
                                    )
                                    Text("Install")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
    ) {
        content()
    }
}

private sealed interface AboutUpdateCheckState {
    data object Idle : AboutUpdateCheckState
    data object Checking : AboutUpdateCheckState
    data class Result(val outcome: UpdateCheckOutcome) : AboutUpdateCheckState
    data class Error(val message: String) : AboutUpdateCheckState
}

private sealed interface StorageCleanupUiState {
    data object Idle : StorageCleanupUiState
    data object Scanning : StorageCleanupUiState
    data class Ready(val report: StorageCleanupReport) : StorageCleanupUiState
    data class Cleaning(val report: StorageCleanupReport) : StorageCleanupUiState
    data class Error(val message: String) : StorageCleanupUiState
}

private val StorageCleanupUiState.isBusy: Boolean
    get() = this == StorageCleanupUiState.Scanning || this is StorageCleanupUiState.Cleaning

private fun StorageCleanupUiState.reportOrNull(): StorageCleanupReport? =
    when (this) {
        is StorageCleanupUiState.Ready -> report
        is StorageCleanupUiState.Cleaning -> report
        else -> null
    }

private fun storageCleanupStatusText(state: StorageCleanupUiState): String =
    when (state) {
        StorageCleanupUiState.Idle -> "Scan for interrupted imports, restore leftovers, orphaned audio, and media cache."
        StorageCleanupUiState.Scanning -> "Scanning app storage..."
        is StorageCleanupUiState.Cleaning -> "Cleaning selected files..."
        is StorageCleanupUiState.Error -> state.message
        is StorageCleanupUiState.Ready -> {
            if (state.report.hasCleanableItems) {
                "${formatStorageSize(state.report.totalSizeBytes)} can be cleaned across ${state.report.categories.size} categories."
            } else {
                "No cleanable app storage leftovers were found."
            }
        }
    }

private fun formatStorageSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes.toDouble() / 1024.0
    units.forEachIndexed { index, unit ->
        val isLast = index == units.lastIndex
        if (value < 1024.0 || isLast) {
            return if (value < 10.0) {
                "%.1f %s".format(Locale.US, value, unit)
            } else {
                "%.0f %s".format(Locale.US, value, unit)
            }
        }
        value /= 1024.0
    }
    return "$bytes B"
}

private fun formatItemCount(count: Int): String =
    "$count ${if (count == 1) "item" else "items"}"

private fun updateStatusText(
    checkState: AboutUpdateCheckState,
    record: UpdateDownloadRecord?,
): String =
    when (checkState) {
        AboutUpdateCheckState.Idle -> when (record?.status) {
            UpdateDownloadRecordStatus.Available -> "Update ${record.versionName} is available."
            UpdateDownloadRecordStatus.Skipped -> "Update ${record.versionName} has been skipped."
            UpdateDownloadRecordStatus.Downloading -> "An update is downloading."
            UpdateDownloadRecordStatus.Downloaded -> "Update ${record.versionName} has been downloaded."
            UpdateDownloadRecordStatus.Failed -> "The last update download failed."
            null -> "Check GitHub Releases for a newer Hoshi Reader APK."
        }
        AboutUpdateCheckState.Checking -> "Checking GitHub Releases..."
        is AboutUpdateCheckState.Error -> checkState.message
        is AboutUpdateCheckState.Result -> when (val outcome = checkState.outcome) {
            UpdateCheckOutcome.UpToDate -> "You are running the latest version."
            UpdateCheckOutcome.NoInstallableAsset -> "A newer release was found, but it does not include a single matching APK."
            is UpdateCheckOutcome.Skipped -> "Update ${outcome.update.versionName} has been skipped."
            is UpdateCheckOutcome.Available -> "Update ${outcome.update.versionName} is available."
            is UpdateCheckOutcome.DownloadStarted -> "Downloading update ${outcome.update.versionName}."
            is UpdateCheckOutcome.DownloadInProgress -> "Update ${outcome.update.versionName} is already downloading."
            is UpdateCheckOutcome.DownloadAlreadyFinished -> "Update ${outcome.update.versionName} has already been downloaded."
        }
    }
