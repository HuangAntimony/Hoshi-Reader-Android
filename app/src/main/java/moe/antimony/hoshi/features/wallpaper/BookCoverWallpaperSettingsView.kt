package moe.antimony.hoshi.features.wallpaper

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.settings.GroupCard
import moe.antimony.hoshi.features.settings.GroupDivider
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold
import moe.antimony.hoshi.features.settings.collectAsLoadedSettings

@Composable
internal fun BookCoverWallpaperSettingsView(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BookCoverWallpaperViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val settings = viewModel.settings.collectAsLoadedSettings()
    val capability = viewModel.capability()
    var targetSelectionFailed by remember { mutableStateOf(false) }
    val targetLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }.onSuccess {
            targetSelectionFailed = false
            val previousTarget = settings?.exportTargetUri
                ?.takeIf { it != uri.toString() }
                ?.let(Uri::parse)
            viewModel.setExportTarget(uri.toString()) {
                previousTarget?.let { previousUri ->
                    runCatching {
                        context.contentResolver.releasePersistableUriPermission(previousUri, flags)
                    }
                }
            }
        }.onFailure {
            targetSelectionFailed = true
        }
    }
    val exportTargetName by produceState<String?>(
        initialValue = null,
        settings?.exportTargetUri,
        context.contentResolver,
    ) {
        value = withContext(Dispatchers.IO) {
            settings?.exportTargetUri?.let { rawUri ->
                queryDisplayName(context.contentResolver, Uri.parse(rawUri))
            }
        }
    }

    SettingsDetailScaffold(
        title = stringResource(R.string.settings_book_cover_wallpaper),
        onClose = onClose,
        modifier = modifier.fillMaxSize(),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                GroupCard {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(stringResource(R.string.book_cover_wallpaper_lock_screen)) },
                        supportingContent = {
                            val summary = when {
                                !capability.isSupported -> R.string.book_cover_wallpaper_not_supported
                                !capability.isSetAllowed -> R.string.book_cover_wallpaper_not_allowed
                                else -> R.string.book_cover_wallpaper_lock_screen_summary
                            }
                            Text(stringResource(summary))
                        },
                        trailingContent = {
                            Switch(
                                checked = settings?.updateLockScreen == true,
                                enabled = settings != null && capability.canUpdateLockScreen,
                                onCheckedChange = viewModel::setUpdateLockScreen,
                            )
                        },
                    )
                }
            }
            item {
                GroupCard {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(stringResource(R.string.book_cover_wallpaper_export)) },
                        supportingContent = { Text(stringResource(R.string.book_cover_wallpaper_export_summary)) },
                        trailingContent = {
                            Switch(
                                checked = settings?.exportEnabled == true,
                                enabled = settings != null,
                                onCheckedChange = { enabled ->
                                    if (!enabled) {
                                        viewModel.setExportEnabled(false)
                                    } else if (hasPersistedWritePermission(
                                            context.contentResolver.persistedUriPermissions
                                                .asSequence()
                                                .filter { it.isWritePermission }
                                                .map { it.uri.toString() }
                                                .toSet(),
                                            settings?.exportTargetUri,
                                        )
                                    ) {
                                        viewModel.setExportEnabled(true)
                                    } else {
                                        targetLauncher.launch(DefaultExportFileName)
                                    }
                                },
                            )
                        },
                    )
                    GroupDivider()
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(stringResource(R.string.book_cover_wallpaper_export_file)) },
                        supportingContent = {
                            Text(
                                when {
                                    targetSelectionFailed -> stringResource(
                                        R.string.book_cover_wallpaper_export_file_failed,
                                    )
                                    exportTargetName != null -> exportTargetName.orEmpty()
                                    else -> stringResource(R.string.book_cover_wallpaper_export_file_none)
                                },
                                color = if (targetSelectionFailed) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                        trailingContent = {
                            TextButton(onClick = { targetLauncher.launch(DefaultExportFileName) }) {
                                Text(stringResource(R.string.book_cover_wallpaper_change_file))
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun queryDisplayName(contentResolver: android.content.ContentResolver, uri: Uri): String? =
    runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

internal fun hasPersistedWritePermission(
    grantedWriteUris: Set<String>,
    rawUri: String?,
): Boolean = rawUri != null && rawUri in grantedWriteUris

private const val DefaultExportFileName = "hoshi-current-cover.png"
