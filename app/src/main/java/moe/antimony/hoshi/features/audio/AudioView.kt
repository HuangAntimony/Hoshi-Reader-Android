package moe.antimony.hoshi.features.audio

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.annotation.StringRes
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.settings.collectAsLoadedSettings
import moe.antimony.hoshi.features.settings.GroupCard
import moe.antimony.hoshi.features.settings.GroupDivider
import moe.antimony.hoshi.features.settings.SectionTitle
import moe.antimony.hoshi.importing.FileImportContent
import moe.antimony.hoshi.importing.ImportFileType
import moe.antimony.hoshi.importing.localizedImportMessage
import moe.antimony.hoshi.ui.hoshiSingleLineTextFieldLineLimits
import moe.antimony.hoshi.ui.hoshiTextFieldCursorBrush
import moe.antimony.hoshi.ui.rememberSyncedTextFieldState
import moe.antimony.hoshi.ui.HoshiBlockingProgressOverlay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSettingsView(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContainer = LocalHoshiAppContainer.current
    val scope = rememberCoroutineScope()
    val audioSettingsRepository = appContainer.audioSettingsRepository
    val settings = audioSettingsRepository.settings.collectAsLoadedSettings()
    val repository = appContainer.localAudioRepository
    var nameInput by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }
    var importedSize by remember { mutableStateOf(repository.databaseSizeBytes()) }
    var localAudioSourceConfig by remember { mutableStateOf<LocalAudioSourceConfig?>(null) }
    var importProgress by remember { mutableStateOf<LocalAudioImportProgress?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    val hasImportedDatabase = importedSize != null
    val importFailedMessage = stringResource(R.string.audio_import_android_db_failed)

    fun save(next: AudioSettings) {
        scope.launch {
            audioSettingsRepository.update { next }
        }
    }

    fun moveSource(settings: AudioSettings, from: Int, to: Int) {
        val sources = settings.audioSources.toMutableList()
        if (from !in sources.indices || to !in sources.indices) return
        val source = sources.removeAt(from)
        sources.add(to, source)
        save(settings.copy(audioSources = sources))
    }

    fun moveLocalAudioSource(from: Int, to: Int) {
        val current = localAudioSourceConfig ?: return
        val sources = current.sourceOrder.toMutableList()
        if (from !in sources.indices || to !in sources.indices) return
        val source = sources.removeAt(from)
        sources.add(to, source)
        scope.launch {
            localAudioSourceConfig = withContext(Dispatchers.IO) {
                repository.updateSourceOrder(sources)
            }
        }
    }

    LaunchedEffect(hasImportedDatabase, isImporting) {
        localAudioSourceConfig = if (hasImportedDatabase && !isImporting) {
            withContext(Dispatchers.IO) {
                repository.ensureSourceConfig()
            }
        } else {
            null
        }
    }

    val importer = rememberLauncherForActivityResult(FileImportContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (isImporting || hasImportedDatabase) return@rememberLauncherForActivityResult
        isImporting = true
        importError = null
        importProgress = LocalAudioImportProgress(copiedBytes = 0, totalBytes = null)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    var lastProgressUpdate = 0L
                    repository.importDatabase(context.contentResolver, uri) { progress ->
                        val shouldUpdate = progress.totalBytes == progress.copiedBytes ||
                            progress.copiedBytes - lastProgressUpdate >= ProgressUpdateBytes
                        if (shouldUpdate) {
                            lastProgressUpdate = progress.copiedBytes
                            scope.launch { importProgress = progress }
                        }
                    }
                }
            }.onSuccess { size ->
                importedSize = size
                localAudioSourceConfig = withContext(Dispatchers.IO) {
                    repository.ensureSourceConfig()
                }
            }.onFailure { error ->
                importError = error.localizedImportMessage(context, importFailedMessage)
            }
            importProgress = null
            isImporting = false
        }
    }

    BackHandler {
        if (!isImporting) {
            onClose()
        }
    }
    val colorScheme = MaterialTheme.colorScheme
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colorScheme.background,
                    scrolledContainerColor = colorScheme.background,
                ),
                title = { Text(stringResource(R.string.advanced_audio), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    BackIconButton {
                        if (!isImporting) {
                            onClose()
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item {
                    val loadedSettings = settings ?: return@item
                    SectionTitle(stringResource(R.string.audio_sources))
                    GroupCard {
                        loadedSettings.audioSources.forEachIndexed { index, source ->
                            AudioSourceRow(
                                source = source,
                                canDelete = !source.isDefault && !source.isBuiltInLocalAudioSource,
                                canMoveUp = index > 0,
                                canMoveDown = index < loadedSettings.audioSources.lastIndex,
                                onEnabledChange = { enabled ->
                                    save(loadedSettings.withAudioSourceEnabled(source, enabled))
                                },
                                onDelete = {
                                    save(loadedSettings.copy(audioSources = loadedSettings.audioSources.filterIndexed { sourceIndex, _ -> sourceIndex != index }))
                                },
                                onMoveUp = { moveSource(loadedSettings, index, index - 1) },
                                onMoveDown = { moveSource(loadedSettings, index, index + 1) },
                            )
                            if (index < loadedSettings.audioSources.lastIndex) GroupDivider()
                        }
                    }
                }
                item {
                    val loadedSettings = settings ?: return@item
                    SectionTitle(stringResource(R.string.audio_add_source))
                    GroupCard {
                        TextInputRow(label = stringResource(R.string.audio_name), value = nameInput, onValueChange = { nameInput = it })
                        GroupDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                TextInputRowContent(
                                    label = stringResource(R.string.audio_url),
                                    value = urlInput,
                                    onValueChange = { urlInput = it },
                                )
                            }
                            IconButton(
                                onClick = {
                                    save(loadedSettings.addSource(AudioSource(name = nameInput.trim(), url = urlInput.trim())))
                                    nameInput = ""
                                    urlInput = ""
                                },
                                enabled = nameInput.isNotBlank() && urlInput.isNotBlank(),
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.audio_add_source))
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.audio_yomitan_json_supported),
                        color = colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                    )
                }
                item {
                    val loadedSettings = settings ?: return@item
                    GroupCard {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(stringResource(R.string.audio_auto_play_on_lookup)) },
                            trailingContent = {
                                Switch(
                                    checked = loadedSettings.enableAutoplay,
                                    onCheckedChange = { save(loadedSettings.copy(enableAutoplay = it)) },
                                )
                            },
                        )
                        GroupDivider()
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(stringResource(R.string.audio_background_audio)) },
                            supportingContent = {
                                SingleChoiceSegmentedButtonRow(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                ) {
                                    AudioPlaybackMode.entries.forEachIndexed { index, mode ->
                                        SegmentedButton(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(58.dp),
                                            selected = loadedSettings.playbackMode == mode,
                                            onClick = { save(loadedSettings.copy(playbackMode = mode)) },
                                            shape = SegmentedButtonDefaults.itemShape(index, AudioPlaybackMode.entries.size),
                                            icon = {},
                                        ) {
                                            Text(stringResource(mode.labelRes))
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
                item {
                    val loadedSettings = settings ?: return@item
                    SectionTitle(stringResource(R.string.audio_local_audio))
                    GroupCard {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(stringResource(R.string.action_enable)) },
                            trailingContent = {
                                Switch(
                                    checked = loadedSettings.enableLocalAudio,
                                    onCheckedChange = { save(loadedSettings.withLocalAudioEnabled(it)) },
                                )
                            },
                        )
                        if (loadedSettings.enableLocalAudio) {
                            GroupDivider()
                            if (!hasImportedDatabase) {
                                ListItem(
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    headlineContent = { Text(stringResource(R.string.audio_import_android_db)) },
                                    supportingContent = {
                                        Text(stringResource(R.string.audio_import_android_db_hint))
                                    },
                                    trailingContent = {
                                        Button(
                                            enabled = !isImporting,
                                            onClick = { importer.launch(ImportFileType.LocalAudioDatabase.mimeTypes) },
                                        ) {
                                            Text(
                                                if (isImporting) {
                                                    stringResource(R.string.reader_appearance_importing)
                                                } else {
                                                    stringResource(R.string.action_import)
                                                },
                                            )
                                        }
                                    },
                                )
                                importError?.let { message ->
                                    GroupDivider()
                                    ListItem(
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                        headlineContent = { Text(stringResource(R.string.audio_import_failed)) },
                                        supportingContent = { Text(message) },
                                    )
                                }
                            }
                            importedSize?.let { size ->
                                GroupDivider()
                                ListItem(
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    headlineContent = {
                                        Text(stringResource(R.string.audio_android_db_size_format, Formatter.formatFileSize(context, size)))
                                    },
                                    trailingContent = {
                                        OutlinedButton(
                                            enabled = !isImporting,
                                            onClick = {
                                                repository.deleteDatabase()
                                                importedSize = null
                                                localAudioSourceConfig = null
                                                importError = null
                                                importProgress = null
                                            },
                                        ) {
                                            Text(stringResource(R.string.action_delete))
                                        }
                                    },
                                )
                            }
                            localAudioSourceConfig?.sourceOrder?.takeIf { it.isNotEmpty() }?.let { sources ->
                                GroupDivider()
                                ListItem(
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    headlineContent = { Text(stringResource(R.string.audio_local_source_order)) },
                                    supportingContent = {
                                        Column {
                                            sources.forEachIndexed { index, source ->
                                                LocalAudioSourceOrderRow(
                                                    source = source,
                                                    canMoveUp = index > 0,
                                                    canMoveDown = index < sources.lastIndex,
                                                    onMoveUp = { moveLocalAudioSource(index, index - 1) },
                                                    onMoveDown = { moveLocalAudioSource(index, index + 1) },
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.audio_local_audio_hint),
                        color = colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 24.dp),
                    )
                }
            }
            if (isImporting) {
                HoshiBlockingProgressOverlay(
                    message = stringResource(R.string.audio_copying_android_db),
                    progress = importProgress?.takeIf { it.totalBytes != null }?.fraction,
                    supportingText = importProgress?.label(context),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun AudioSourceRow(
    source: AudioSource,
    canDelete: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(source.name, maxLines = 1) },
        supportingContent = {
            if (!source.isDefault && !source.isBuiltInLocalAudioSource) {
                Text(source.url, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(Icons.Rounded.ArrowUpward, contentDescription = stringResource(R.string.audio_move_source_up))
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(Icons.Rounded.ArrowDownward, contentDescription = stringResource(R.string.audio_move_source_down))
                }
                if (canDelete) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.audio_delete_source))
                    }
                }
                Switch(checked = source.isEnabled, onCheckedChange = onEnabledChange)
            }
        },
    )
}

@Composable
private fun LocalAudioSourceOrderRow(
    source: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = source,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(Icons.Rounded.ArrowUpward, contentDescription = stringResource(R.string.audio_move_local_source_up))
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(Icons.Rounded.ArrowDownward, contentDescription = stringResource(R.string.audio_move_local_source_down))
        }
    }
}

@Composable
private fun TextInputRow(label: String, value: String, onValueChange: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        TextInputRowContent(label = label, value = value, onValueChange = onValueChange)
    }
}

@Composable
private fun TextInputRowContent(label: String, value: String, onValueChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(72.dp))
        val fieldScrollState = rememberScrollState()
        val fieldState = rememberSyncedTextFieldState(
            value = value,
            onValueChange = onValueChange,
            scrollState = fieldScrollState,
        )
        BasicTextField(
            state = fieldState,
            lineLimits = hoshiSingleLineTextFieldLineLimits(),
            scrollState = fieldScrollState,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = hoshiTextFieldCursorBrush(),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun BackIconButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
    }
}

private const val ProgressUpdateBytes = 64L * 1024L * 1024L

@get:StringRes
private val AudioPlaybackMode.labelRes: Int
    get() = when (this) {
        AudioPlaybackMode.Interrupt -> R.string.audio_playback_interrupt
        AudioPlaybackMode.Duck -> R.string.audio_playback_duck
        AudioPlaybackMode.Mix -> R.string.audio_playback_mix
    }

private val LocalAudioImportProgress.fraction: Float
    get() = totalBytes?.takeIf { it > 0 }?.let { (copiedBytes.toFloat() / it.toFloat()).coerceIn(0f, 1f) } ?: 0f

private fun LocalAudioImportProgress.label(context: android.content.Context): String {
    val copied = Formatter.formatFileSize(context, copiedBytes)
    val total = totalBytes?.let { Formatter.formatFileSize(context, it) }
    return if (total == null) copied else context.getString(R.string.audio_copied_size_format, copied, total)
}
