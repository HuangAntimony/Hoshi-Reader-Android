package moe.antimony.hoshi.features.backup

import moe.antimony.hoshi.ui.theme.hoshiSurfaces
import moe.antimony.hoshi.ui.theme.hoshiContainerBorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import moe.antimony.hoshi.ui.withErrorDetails
import moe.antimony.hoshi.LocalHoshiUiDependencies
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.ErrorDetailsText
import moe.antimony.hoshi.ui.HoshiAlertDialog
import androidx.compose.material3.TextButton
import moe.antimony.hoshi.features.settings.StaticSettingsDetailScaffold
import moe.antimony.hoshi.importing.FileImportContent
import moe.antimony.hoshi.importing.ImportFileType
import moe.antimony.hoshi.importing.UnsupportedImportFileTypeException
import moe.antimony.hoshi.importing.validateImportFile
import moe.antimony.hoshi.ui.HoshiBlockingProgressOverlay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsView(
    onClose: () -> Unit,
    onBooksRestored: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContainer = LocalHoshiUiDependencies.current
    val repository = appContainer.backupRepository
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var operation by remember { mutableStateOf<BackupOperation?>(null) }
    var operationError by remember { mutableStateOf<String?>(null) }
    suspend fun showOperationMessage(message: String, isError: Boolean) {
        if (isError) operationError = message else snackbarHostState.showSnackbar(message)
    }
    operationError?.let { message ->
        HoshiAlertDialog(
            onDismissRequest = { operationError = null },
            text = { ErrorDetailsText(message) },
            confirmButton = {
                TextButton(onClick = { operationError = null }) { Text(stringResource(R.string.action_ok)) }
            },
        )
    }
    val booksBackupSaved = stringResource(R.string.backup_books_saved)
    val booksBackupSaveFailed = stringResource(R.string.backup_books_save_failed)
    val dictionariesBackupSaved = stringResource(R.string.backup_dictionaries_saved)
    val dictionariesBackupSaveFailed = stringResource(R.string.backup_dictionaries_save_failed)
    val booksRestored = stringResource(R.string.backup_books_restored)
    val booksRestoreFailed = stringResource(R.string.backup_books_restore_failed)
    val dictionariesRestored = stringResource(R.string.backup_dictionaries_restored)
    val dictionariesRestoreFailed = stringResource(R.string.backup_dictionaries_restore_failed)
    val hoshiBackupUnsupported = stringResource(R.string.import_select_hoshi_backup)
    val ttuExported = stringResource(R.string.backup_ttu_saved)
    val ttuExportFailed = stringResource(R.string.backup_ttu_save_failed)
    val ttuImported = stringResource(R.string.backup_ttu_restored)
    val ttuImportFailed = stringResource(R.string.backup_ttu_restore_failed)
    val ttuBackupUnsupported = stringResource(R.string.import_select_ttu_bookdata_backup)
    val booksExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null || operation != null) return@rememberLauncherForActivityResult
        operation = BackupOperation.Exporting
        scope.launch {
            val result = runCatching {
                repository.exportBooks(context.contentResolver, uri)
            }
            operation = null
            showOperationMessage(
                if (result.isSuccess) {
                    booksBackupSaved
                } else {
                    result.exceptionOrNull().stableBackupFailureMessage(booksBackupSaveFailed)
                },
                isError = result.isFailure,
            )
        }
    }
    val dictionariesExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null || operation != null) return@rememberLauncherForActivityResult
        operation = BackupOperation.Exporting
        scope.launch {
            val result = runCatching {
                repository.exportDictionaries(context.contentResolver, uri)
            }
            operation = null
            showOperationMessage(
                if (result.isSuccess) {
                    dictionariesBackupSaved
                } else {
                    result.exceptionOrNull().stableBackupFailureMessage(dictionariesBackupSaveFailed)
                },
                isError = result.isFailure,
            )
        }
    }
    val ttuExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null || operation != null) return@rememberLauncherForActivityResult
        operation = BackupOperation.ExportingTtu
        scope.launch {
            val result = runCatching {
                repository.exportTtuBookData(context.contentResolver, uri)
            }
            operation = null
            showOperationMessage(
                if (result.isSuccess) {
                    ttuExported
                } else {
                    result.exceptionOrNull().stableBackupFailureMessage(ttuExportFailed)
                },
                isError = result.isFailure,
            )
        }
    }
    val booksImporter = rememberLauncherForActivityResult(FileImportContent()) { uri ->
        if (uri == null || operation != null) return@rememberLauncherForActivityResult
        operation = BackupOperation.Restoring
        scope.launch {
            val result = runCatching {
                context.contentResolver.validateImportFile(uri, ImportFileType.HoshiBackup)
                appContainer.googleDriveSyncManager.stop()
                repository.restoreBooks(context.contentResolver, uri)
                appContainer.googleDriveSyncManager.resetConnection(restoringBackup = true)
            }
            if (result.isFailure) appContainer.syncSettingsRepository.update { it.copy(enabled = false) }
            appContainer.googleDriveSyncManager.start()
            operation = null
            if (result.isSuccess) {
                onBooksRestored()
            }
            showOperationMessage(
                if (result.isSuccess) {
                    booksRestored
                } else {
                    result.exceptionOrNull().stableBackupFailureMessage(booksRestoreFailed, hoshiBackupUnsupported)
                },
                isError = result.isFailure,
            )
        }
    }
    val dictionariesImporter = rememberLauncherForActivityResult(FileImportContent()) { uri ->
        if (uri == null || operation != null) return@rememberLauncherForActivityResult
        operation = BackupOperation.Restoring
        scope.launch {
            val result = runCatching {
                context.contentResolver.validateImportFile(uri, ImportFileType.HoshiBackup)
                repository.restoreDictionaries(context.contentResolver, uri)
                appContainer.dictionaryRepository.rebuildLookupQuery()
            }
            operation = null
            showOperationMessage(
                if (result.isSuccess) {
                    dictionariesRestored
                } else {
                    result.exceptionOrNull().stableBackupFailureMessage(dictionariesRestoreFailed, hoshiBackupUnsupported)
                },
                isError = result.isFailure,
            )
        }
    }
    val ttuImporter = rememberLauncherForActivityResult(FileImportContent()) { uri ->
        if (uri == null || operation != null) return@rememberLauncherForActivityResult
        operation = BackupOperation.ImportingTtu
        scope.launch {
            val result = runCatching {
                context.contentResolver.validateImportFile(uri, ImportFileType.TtuBookDataBackup)
                repository.restoreTtuBookData(context.contentResolver, uri)
            }
            operation = null
            val restoredCount = result.getOrNull() ?: 0
            if (restoredCount > 0) {
                onBooksRestored()
            }
            showOperationMessage(
                when {
                    result.isSuccess && restoredCount > 0 -> ttuImported
                    result.isSuccess -> ttuImportFailed
                    else -> result.exceptionOrNull().stableBackupFailureMessage(ttuImportFailed, ttuBackupUnsupported)
                },
                isError = result.isFailure || restoredCount <= 0,
            )
        }
    }

    StaticSettingsDetailScaffold(
        title = stringResource(R.string.settings_backup),
        onClose = {
            if (operation == null) {
                onClose()
            }
        },
        modifier = modifier.fillMaxSize(),
        containerColor = hoshiSurfaces.page,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item {
                    BackupSection(
                        title = stringResource(R.string.backup_books),
                        footer = null,
                        onBackup = { booksExporter.launch(booksBackupFileName()) },
                        onRestore = { booksImporter.launch(ImportFileType.HoshiBackup.mimeTypes) },
                        enabled = operation == null,
                    )
                }
                item {
                    BackupSection(
                        title = stringResource(R.string.backup_dictionaries),
                        footer = stringResource(R.string.backup_restore_overwrites),
                        onBackup = { dictionariesExporter.launch(dictionariesBackupFileName()) },
                        onRestore = { dictionariesImporter.launch(ImportFileType.HoshiBackup.mimeTypes) },
                        enabled = operation == null,
                    )
                }
                item {
                    BackupSection(
                        title = stringResource(R.string.backup_ttu_bookdata),
                        footer = stringResource(R.string.backup_ttu_bookdata_description),
                        onBackup = { ttuExporter.launch(ttuBookDataBackupFileName()) },
                        onRestore = { ttuImporter.launch(ImportFileType.TtuBookDataBackup.mimeTypes) },
                        enabled = operation == null,
                        backupLabelRes = R.string.action_export,
                        restoreLabelRes = R.string.action_import,
                    )
                }
            }
            operation?.let { current ->
                HoshiBlockingProgressOverlay(
                    stringResource(current.labelRes),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun BackupSection(
    title: String,
    footer: String?,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    enabled: Boolean,
    @StringRes backupLabelRes: Int = R.string.backup_action_backup,
    @StringRes restoreLabelRes: Int = R.string.backup_action_restore,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    BackupGroupCard {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { Icon(Icons.Rounded.Upload, contentDescription = null) },
            headlineContent = { Text(stringResource(backupLabelRes)) },
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onBackup),
        )
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { Icon(Icons.Rounded.Download, contentDescription = null) },
            headlineContent = { Text(stringResource(restoreLabelRes)) },
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onRestore),
        )
    }
    footer?.let { text ->
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun BackupGroupCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = hoshiSurfaces.group,
        border = hoshiContainerBorder(),
        tonalElevation = 0.dp,
    ) {
        Column(content = { content() })
    }
}

private enum class BackupOperation(val labelRes: Int) {
    Exporting(R.string.backup_archiving),
    Restoring(R.string.backup_restoring),
    ExportingTtu(R.string.backup_exporting),
    ImportingTtu(R.string.backup_importing),
}

internal fun Throwable?.stableBackupFailureMessage(
    fallback: String,
    unsupportedImportMessage: String? = null,
): String =
    when {
        this is CancellationException -> throw this
        this is UnsupportedImportFileTypeException && unsupportedImportMessage != null ->
            unsupportedImportMessage.withErrorDetails(this)
        this != null -> fallback.withErrorDetails(this)
        else -> fallback
    }
