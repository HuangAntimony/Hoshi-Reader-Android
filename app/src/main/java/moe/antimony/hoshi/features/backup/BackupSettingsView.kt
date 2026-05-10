package moe.antimony.hoshi.features.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold
import moe.antimony.hoshi.importing.FileImportContent
import moe.antimony.hoshi.importing.ImportFileType
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
    val appContainer = LocalHoshiAppContainer.current
    val repository = appContainer.backupRepository
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var operation by remember { mutableStateOf<BackupOperation?>(null) }
    val booksExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null || operation != null) return@rememberLauncherForActivityResult
        operation = BackupOperation.Exporting
        scope.launch {
            val result = runCatching {
                repository.exportBooks(context.contentResolver, uri)
            }
            operation = null
            snackbarHostState.showSnackbar(
                if (result.isSuccess) {
                    "Books backup saved."
                } else {
                    result.exceptionOrNull()?.message ?: "Unable to save Books backup."
                },
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
            snackbarHostState.showSnackbar(
                if (result.isSuccess) {
                    "Dictionaries backup saved."
                } else {
                    result.exceptionOrNull()?.message ?: "Unable to save Dictionaries backup."
                },
            )
        }
    }
    val booksImporter = rememberLauncherForActivityResult(FileImportContent()) { uri ->
        if (uri == null || operation != null) return@rememberLauncherForActivityResult
        operation = BackupOperation.Restoring
        scope.launch {
            val result = runCatching {
                context.contentResolver.validateImportFile(uri, ImportFileType.HoshiBackup)
                repository.restoreBooks(context.contentResolver, uri)
            }
            operation = null
            if (result.isSuccess) {
                onBooksRestored()
            }
            snackbarHostState.showSnackbar(
                if (result.isSuccess) {
                    "Books restored."
                } else {
                    result.exceptionOrNull()?.message ?: "Unable to restore Books backup."
                },
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
            snackbarHostState.showSnackbar(
                if (result.isSuccess) {
                    "Dictionaries restored."
                } else {
                    result.exceptionOrNull()?.message ?: "Unable to restore Dictionaries backup."
                },
            )
        }
    }

    SettingsDetailScaffold(
        title = "Backup",
        onClose = {
            if (operation == null) {
                onClose()
            }
        },
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
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
                        title = "Books",
                        footer = null,
                        onBackup = { booksExporter.launch(booksBackupFileName()) },
                        onRestore = { booksImporter.launch(ImportFileType.HoshiBackup.mimeTypes) },
                        enabled = operation == null,
                    )
                }
                item {
                    BackupSection(
                        title = "Dictionaries",
                        footer = "Restoring will overwrite the current collection.",
                        onBackup = { dictionariesExporter.launch(dictionariesBackupFileName()) },
                        onRestore = { dictionariesImporter.launch(ImportFileType.HoshiBackup.mimeTypes) },
                        enabled = operation == null,
                    )
                }
            }
            operation?.let { current ->
                HoshiBlockingProgressOverlay(
                    current.label,
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
            headlineContent = { Text("Backup") },
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
            headlineContent = { Text("Restore") },
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
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
    ) {
        Column(content = { content() })
    }
}

private enum class BackupOperation(val label: String) {
    Exporting("Archiving..."),
    Restoring("Restoring..."),
}
