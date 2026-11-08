package moe.antimony.hoshi.features.audio

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.annotation.StringRes
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import moe.antimony.hoshi.LocalHoshiUiDependencies
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.settings.GroupCard
import moe.antimony.hoshi.features.settings.GroupDivider
import moe.antimony.hoshi.features.settings.SectionTitle
import moe.antimony.hoshi.features.settings.SettingsContent
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold
import moe.antimony.hoshi.features.settings.rememberSettingsLoader
import moe.antimony.hoshi.importing.FileImportContent
import moe.antimony.hoshi.importing.ImportFileType
import moe.antimony.hoshi.importing.OpenDocumentContent
import moe.antimony.hoshi.ui.ErrorDetailsText
import moe.antimony.hoshi.ui.HoshiAlertDialog
import moe.antimony.hoshi.ui.HoshiBlockingProgressOverlay
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.asString
import moe.antimony.hoshi.ui.hoshiSingleLineTextFieldLineLimits
import moe.antimony.hoshi.ui.hoshiTextFieldCursorBrush
import moe.antimony.hoshi.ui.rememberSyncedTextFieldState
import moe.antimony.hoshi.ui.HoshiButton as Button

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSettingsView(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContainer = LocalHoshiUiDependencies.current
    val scope = rememberCoroutineScope()
    val audioSettingsRepository = appContainer.audioSettingsRepository
    val settingsLoader = audioSettingsRepository.settings.rememberSettingsLoader()
    val localAudio: LocalAudioSettingsViewModel = hiltViewModel()
    val localState by localAudio.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { localAudio.refresh() }
    var nameInput by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }
    var showImportOptions by rememberSaveable { mutableStateOf(false) }
    val copyPicker = rememberLauncherForActivityResult(FileImportContent()) { uri ->
        uri?.let { localAudio.importDatabase(it, LocalAudioImportMode.Copy) }
    }
    val linkPicker = rememberLauncherForActivityResult(OpenDocumentContent()) { uri ->
        uri?.let { localAudio.importDatabase(it, LocalAudioImportMode.Link) }
    }

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

    val colorScheme = MaterialTheme.colorScheme
    SettingsDetailScaffold(
        state = settingsLoader.state,
        title = stringResource(R.string.advanced_audio),
        onClose = { if (localAudio.state.value.importMode == null) onClose() },
        onRetry = settingsLoader.retry,
        modifier = modifier,
    ) { innerPadding, loadedSettings ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item {
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
                            localState.operationError?.let { error ->
                                GroupDivider()
                                LocalAudioError(error)
                            }
                            GroupDivider()
                            SettingsContent(
                                state = localState.database,
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                onRetry = localAudio::refresh,
                            ) { database ->
                                LocalAudioDatabaseSettings(
                                    database = database,
                                    state = localState,
                                    onSelect = { showImportOptions = true },
                                    onDelete = localAudio::deleteDatabase,
                                    onDeleteRetainedCopy = localAudio::deleteRetainedPrivateDatabase,
                                    onMoveSource = localAudio::moveSource,
                                    onSourceEnabled = localAudio::setSourceEnabled,
                                )
                            }
                        }
                    }
                }
            }
            localState.importMode?.let { mode ->
                val progress = localState.copyProgress
                HoshiBlockingProgressOverlay(
                    message = stringResource(
                        if (mode == LocalAudioImportMode.Copy) R.string.audio_copying_android_db
                        else R.string.audio_validating_database,
                    ),
                    progress = progress?.takeIf { it.totalBytes != null }?.fraction,
                    supportingText = progress?.label(context),
                )
            }
        }
    }
    if (showImportOptions) {
        LocalAudioImportOptions(
            onDismiss = { showImportOptions = false },
            onCopy = {
                showImportOptions = false
                copyPicker.launch(ImportFileType.LocalAudioDatabase.mimeTypes)
            },
            onLink = {
                showImportOptions = false
                linkPicker.launch(ImportFileType.LocalAudioDatabase.mimeTypes)
            },
        )
    }
}

@Composable
internal fun LocalAudioImportOptions(
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onLink: () -> Unit,
) {
    HoshiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.audio_import_android_db)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.audio_copy_database_option))
                }
                Text(stringResource(R.string.audio_local_audio_hint))
                Button(onClick = onLink, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.audio_link_database_option))
                }
                Text(stringResource(R.string.audio_link_database_hint))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
internal fun LocalAudioDatabaseSettings(
    database: LocalAudioDatabaseInfo?,
    state: LocalAudioSettingsState,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onDeleteRetainedCopy: () -> Unit,
    onMoveSource: (String, Int) -> Unit,
    onSourceEnabled: (String, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val availableDatabase = database?.takeUnless { it.isExternal && state.readError != null }
    if (availableDatabase != null) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(availableDatabase.displayName) },
            supportingContent = {
                Text(
                    stringResource(
                        if (availableDatabase.isExternal) R.string.audio_linked_database_size else R.string.audio_private_database_size,
                        Formatter.formatFileSize(context, availableDatabase.sizeBytes),
                    ),
                )
            },
            trailingContent = {
                OutlinedButton(enabled = !state.isBusy, onClick = onDelete) {
                    Text(stringResource(if (availableDatabase.isExternal) R.string.audio_unlink_database else R.string.action_delete))
                }
            },
        )
    } else {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(stringResource(R.string.audio_import_android_db)) },
            trailingContent = {
                Button(enabled = !state.isBusy, onClick = onSelect) {
                    Text(stringResource(R.string.action_import))
                }
            },
        )
    }
    listOfNotNull(state.readError, state.source.errorMessage).forEach { error ->
        GroupDivider()
        LocalAudioError(error)
    }
    if (state.hasRetainedPrivateDatabase) {
        GroupDivider()
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(stringResource(R.string.audio_retained_private_database)) },
            supportingContent = { Text(stringResource(R.string.audio_retained_private_database_hint)) },
            trailingContent = {
                OutlinedButton(enabled = !state.isBusy, onClick = onDeleteRetainedCopy) {
                    Text(stringResource(R.string.action_delete))
                }
            },
        )
    }
    state.source.config?.takeIf { it.sourceOrder.isNotEmpty() }?.let { config ->
        val sources = config.sourceOrder
        GroupDivider()
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(stringResource(R.string.audio_local_source_order)) },
            supportingContent = {
                Column {
                    sources.forEachIndexed { index, source ->
                        LocalAudioSourceOrderRow(
                            source = source,
                            enabled = source !in config.disabledSources,
                            controlsEnabled = !state.isBusy,
                            canMoveUp = index > 0,
                            canMoveDown = index < sources.lastIndex,
                            onEnabledChange = { onSourceEnabled(source, it) },
                            onMoveUp = { onMoveSource(source, -1) },
                            onMoveDown = { onMoveSource(source, 1) },
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun LocalAudioError(error: UiText) {
    ErrorDetailsText(error.asString(), modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
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
        headlineContent = { Text(audioSourceName(source), maxLines = 1) },
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
    enabled: Boolean,
    controlsEnabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEnabledChange: (Boolean) -> Unit,
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
        IconButton(onClick = onMoveUp, enabled = controlsEnabled && canMoveUp) {
            Icon(Icons.Rounded.ArrowUpward, contentDescription = stringResource(R.string.audio_move_local_source_up))
        }
        IconButton(onClick = onMoveDown, enabled = controlsEnabled && canMoveDown) {
            Icon(Icons.Rounded.ArrowDownward, contentDescription = stringResource(R.string.audio_move_local_source_down))
        }
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange,
            enabled = controlsEnabled,
        )
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
