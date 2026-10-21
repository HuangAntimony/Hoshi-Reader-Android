package moe.antimony.hoshi.features.update

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import moe.antimony.hoshi.features.settings.GroupDivider
import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import moe.antimony.hoshi.ui.HoshiAlertDialog as AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import moe.antimony.hoshi.ui.withErrorDetails
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.BuildConfig
import moe.antimony.hoshi.BuildCommit
import moe.antimony.hoshi.LocalHoshiUiDependencies
import moe.antimony.hoshi.R
import androidx.compose.foundation.text.selection.SelectionContainer
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold
import moe.antimony.hoshi.features.settings.rememberSettingsLoader
import moe.antimony.hoshi.features.storage.StorageCleanupCategoryId
import moe.antimony.hoshi.features.storage.StorageCleanupReport
import java.io.File
import java.util.Locale

private const val GitHubRepositoryUrl = "https://github.com/HuangAntimony/Hoshi-Reader-Android"

@Composable
fun AboutScreen(
    onOpenDependencies: () -> Unit,
    onOpenAcknowledgements: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actionsModel: AboutActionsViewModel = hiltViewModel()
    val openLink = rememberAboutLinkOpener(actionsModel)
    AboutActionErrors(actionsModel)
    AboutSupportEffects(actionsModel)
    val previewModel: PreviewUpdateViewModel = hiltViewModel()
    PreviewUpdateEffects(previewModel)
    val context = LocalContext.current
    val resources = LocalResources.current
    val appContainer = LocalHoshiUiDependencies.current
    val scope = rememberCoroutineScope()
    val recordLoader = appContainer.updateDownloadStore.record.rememberSettingsLoader()
    var checkState by remember { mutableStateOf<AboutUpdateCheckState>(AboutUpdateCheckState.Idle) }
    var cleanupState by remember { mutableStateOf<StorageCleanupUiState>(StorageCleanupUiState.Idle) }
    var pendingCleanupConfirmation by remember { mutableStateOf<StorageCleanupReport?>(null) }
    var pendingAvailableUpdate by remember { mutableStateOf<AvailableUpdate?>(null) }
    var updatePromptMessage by remember { mutableStateOf<String?>(null) }

    pendingCleanupConfirmation?.let { report ->
        AlertDialog(
            onDismissRequest = { pendingCleanupConfirmation = null },
            title = { Text(stringResource(R.string.about_storage_cleanup_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.about_storage_cleanup_confirm_message_format,
                        formatStorageSize(report.totalSizeBytes),
                    ),
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
                                    if (error is CancellationException) throw error
                                    StorageCleanupUiState.Error(
                                        resources.getString(R.string.about_storage_cleanup_failed).withErrorDetails(error),
                                    )
                                },
                            )
                        }
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingCleanupConfirmation = null }) {
                    Text(stringResource(R.string.action_cancel))
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
                            if (error is CancellationException) throw error
                            AboutUpdateCheckState.Error(
                                resources.getString(R.string.about_update_download_failed).withErrorDetails(error),
                            )
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
        state = recordLoader.state,
        title = stringResource(R.string.settings_about),
        onClose = onClose,
        modifier = modifier,
        onRetry = recordLoader.retry,
    ) { innerPadding, record ->
        val actionableRecord = record?.takeIf { it.shouldSurfaceInAbout(BuildConfig.VERSION_NAME) }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                AboutSection {
                    AboutRow(stringResource(R.string.diagnostics_version_name), value = stringResource(R.string.about_version_value, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
                    GroupDivider()
                    AboutRow(stringResource(R.string.build_commit), value = BuildCommit.current?.shortSha
                        ?: stringResource(R.string.diagnostics_unavailable))
                }
            }
            item {
                AboutSection(stringResource(R.string.about_updates)) {
                    AboutRow(
                        title = stringResource(R.string.about_check_updates),
                        detail = if (checkState == AboutUpdateCheckState.Idle && actionableRecord == null) null
                            else if (checkState.hasError(actionableRecord)) null
                            else updateStatusText(resources, checkState, actionableRecord),
                        busy = checkState is AboutUpdateCheckState.Checking,
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
                                        if (error is CancellationException) throw error
                                        AboutUpdateCheckState.Error(
                                            resources.getString(R.string.about_update_check_failed).withErrorDetails(error),
                                        )
                                    },
                                )
                            }
                        },
                    )
                    if (checkState.hasError(actionableRecord)) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            AboutStatusText(updateStatusText(resources, checkState, actionableRecord), true)
                        }
                    }
                    val downloadedFile = actionableRecord?.takeIf { it.status == UpdateDownloadRecordStatus.Downloaded }
                        ?.let { appContainer.updateDownloadManager.updateFile(it.fileName) }?.takeIf(File::isFile)
                    val availableUpdate = actionableRecord?.takeIf {
                        it.status == UpdateDownloadRecordStatus.Available || it.status == UpdateDownloadRecordStatus.Skipped ||
                            it.status == UpdateDownloadRecordStatus.Failed
                    }?.toAvailableUpdate()
                    if (availableUpdate != null) {
                        AboutRow(stringResource(R.string.about_download_stable),
                            enabled = checkState !is AboutUpdateCheckState.Checking, onClick = {
                                scope.launch {
                                    checkState = runCatching {
                                        withContext(Dispatchers.IO) {
                                            appContainer.updateCheckService.download(availableUpdate)
                                        }
                                    }.fold(
                                        onSuccess = { AboutUpdateCheckState.Result(it) },
                                        onFailure = { error ->
                                            if (error is CancellationException) throw error
                                            AboutUpdateCheckState.Error(
                                                resources.getString(R.string.about_update_download_failed).withErrorDetails(error),
                                            )
                                        },
                                    )
                                }
                            })
                    }
                    if (downloadedFile != null) {
                        AboutRow(stringResource(R.string.about_install_stable), onClick = {
                            try {
                                openDownloadedUpdate(context, downloadedFile)?.let { checkState = AboutUpdateCheckState.Error(it) }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) {
                                checkState = AboutUpdateCheckState.Error(resources.getString(R.string.about_update_download_failed).withErrorDetails(error))
                            }
                        })
                    }
                    GroupDivider()
                    PreviewUpdateControls(previewModel)
                }
            }
            item {
                AboutSection(stringResource(R.string.about_project)) {
                    AboutRow(stringResource(R.string.about_android_project),
                        detail = stringResource(R.string.about_star_project), onClick = { openLink(GitHubRepositoryUrl) })
                }
            }
            item {
                AboutSection(stringResource(R.string.about_related_projects)) {
                    AboutRow(stringResource(R.string.about_ios_project), onClick = { openLink("https://github.com/Manhhao/Hoshi-Reader") })
                    GroupDivider()
                    AboutRow(stringResource(R.string.about_desktop_project), onClick = { openLink("https://github.com/Manhhao/Hoshi-Reader-Desktop") })
                }
            }
            item { AboutSupportSection(actionsModel, openLink) }
            item {
                AboutSection(stringResource(R.string.about_storage_cleanup)) {
                    AboutRow(stringResource(R.string.about_scan_storage),
                        detail = if (cleanupState == StorageCleanupUiState.Idle || cleanupState is StorageCleanupUiState.Error) null
                            else storageCleanupStatusText(resources, cleanupState),
                        busy = cleanupState.isBusy, onClick = {
                            scope.launch {
                                cleanupState = StorageCleanupUiState.Scanning
                                cleanupState = runCatching {
                                    withContext(Dispatchers.IO) {
                                        appContainer.storageCleanupRepository.scan()
                                    }
                                }.fold(
                                    onSuccess = { StorageCleanupUiState.Ready(it) },
                                    onFailure = { error ->
                                        if (error is CancellationException) throw error
                                        StorageCleanupUiState.Error(
                                            resources.getString(R.string.about_storage_scan_failed).withErrorDetails(error),
                                        )
                                    },
                                )
                            }
                        })
                    if (cleanupState is StorageCleanupUiState.Error) {
                        Column(Modifier.padding(16.dp)) { AboutStatusText(storageCleanupStatusText(resources, cleanupState), true) }
                    }
                    val report = cleanupState.reportOrNull()
                    if (report != null && report.categories.isNotEmpty()) {
                        report.categories.forEach { category ->
                            AboutRow(resources.getString(category.id.titleRes), value = formatStorageSize(category.sizeBytes),
                                detail = formatItemCount(resources, category.itemCount))
                        }
                    }
                    if (report?.hasCleanableItems == true) {
                        GroupDivider()
                        AboutRow(stringResource(R.string.about_clean_up), enabled = !cleanupState.isBusy,
                            onClick = { pendingCleanupConfirmation = report })
                    }
                }
            }
            item {
                AboutSection {
                    AboutRow(stringResource(R.string.about_dependencies), showChevron = true, onClick = onOpenDependencies)
                    GroupDivider()
                    AboutRow(stringResource(R.string.about_attribution), showChevron = true, onClick = onOpenAcknowledgements)
                }
            }
        }
    }
}

@Composable
private fun AboutStatusText(text: String, isError: Boolean) {
    if (isError) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.dialog_error_title), style = MaterialTheme.typography.labelLarge)
                SelectionContainer { Text(text) }
            }
        }
    } else {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private sealed interface AboutUpdateCheckState {
    data object Idle : AboutUpdateCheckState
    data object Checking : AboutUpdateCheckState
    data class Result(val outcome: UpdateCheckOutcome) : AboutUpdateCheckState
    data class Error(val message: String) : AboutUpdateCheckState
}

private fun AboutUpdateCheckState.hasError(record: UpdateDownloadRecord?): Boolean = when (this) {
    is AboutUpdateCheckState.Error -> true
    AboutUpdateCheckState.Idle -> record?.status == UpdateDownloadRecordStatus.Failed
    is AboutUpdateCheckState.Result -> outcome is UpdateCheckOutcome.Available && record?.status == UpdateDownloadRecordStatus.Failed
    AboutUpdateCheckState.Checking -> false
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

private fun storageCleanupStatusText(resources: Resources, state: StorageCleanupUiState): String =
    when (state) {
        StorageCleanupUiState.Idle -> resources.getString(R.string.about_storage_cleanup_scan_hint)
        StorageCleanupUiState.Scanning -> resources.getString(R.string.about_storage_scanning)
        is StorageCleanupUiState.Cleaning -> resources.getString(R.string.about_storage_cleaning)
        is StorageCleanupUiState.Error -> state.message
        is StorageCleanupUiState.Ready -> {
            if (state.report.hasCleanableItems) {
                resources.getString(
                    R.string.about_storage_cleanable_format,
                    formatStorageSize(state.report.totalSizeBytes),
                    state.report.categories.size,
                )
            } else {
                resources.getString(R.string.about_storage_no_leftovers)
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

private fun formatItemCount(resources: Resources, count: Int): String =
    resources.getQuantityString(R.plurals.about_item_count, count, count)

private val StorageCleanupCategoryId.titleRes: Int
    get() = when (this) {
        StorageCleanupCategoryId.AnkiMediaCache -> R.string.about_storage_category_anki_media_cache
        StorageCleanupCategoryId.EpubImportResidue -> R.string.about_storage_category_epub_import_residue
        StorageCleanupCategoryId.BackupRestoreResidue -> R.string.about_storage_category_backup_restore_residue
        StorageCleanupCategoryId.DictionaryImportResidue -> R.string.about_storage_category_dictionary_import_residue
        StorageCleanupCategoryId.LocalAudioImportResidue -> R.string.about_storage_category_local_audio_import_residue
        StorageCleanupCategoryId.OrphanSasayakiAudio -> R.string.about_storage_category_orphan_sasayaki_audio
    }

private fun updateStatusText(
    resources: Resources,
    checkState: AboutUpdateCheckState,
    record: UpdateDownloadRecord?,
): String =
    when (checkState) {
        AboutUpdateCheckState.Idle -> when (record?.status) {
            UpdateDownloadRecordStatus.Available -> resources.getString(R.string.about_update_available_format, record.versionName)
            UpdateDownloadRecordStatus.Skipped -> resources.getString(R.string.about_update_skipped_format, record.versionName)
            UpdateDownloadRecordStatus.Downloading -> resources.getString(R.string.about_update_downloading)
            UpdateDownloadRecordStatus.Downloaded -> resources.getString(R.string.about_update_downloaded_format, record.versionName)
            UpdateDownloadRecordStatus.Failed -> record.failureMessage(resources.getString(R.string.about_update_last_download_failed))
            null -> resources.getString(R.string.about_update_check_github)
        }
        AboutUpdateCheckState.Checking -> resources.getString(R.string.about_update_checking_github)
        is AboutUpdateCheckState.Error -> checkState.message
        is AboutUpdateCheckState.Result -> when (val outcome = checkState.outcome) {
            UpdateCheckOutcome.UpToDate -> resources.getString(R.string.about_update_latest)
            UpdateCheckOutcome.NoInstallableAsset -> resources.getString(R.string.about_update_no_matching_apk)
            is UpdateCheckOutcome.Skipped -> resources.getString(R.string.about_update_skipped_format, outcome.update.versionName)
            is UpdateCheckOutcome.Available -> if (record?.status == UpdateDownloadRecordStatus.Failed) {
                record.failureMessage(resources.getString(R.string.about_update_last_download_failed))
            } else resources.getString(R.string.about_update_available_format, outcome.update.versionName)
            is UpdateCheckOutcome.DownloadStarted -> resources.getString(R.string.about_update_downloading_format, outcome.update.versionName)
            is UpdateCheckOutcome.DownloadInProgress -> resources.getString(
                R.string.about_update_already_downloading_format,
                outcome.update.versionName,
            )
            is UpdateCheckOutcome.DownloadAlreadyFinished -> resources.getString(
                R.string.about_update_already_downloaded_format,
                outcome.update.versionName,
            )
        }
    }

internal fun UpdateDownloadRecord.failureMessage(summary: String): String =
    listOfNotNull(summary, failureDetails?.takeIf(String::isNotBlank)).distinct().joinToString("\n")
