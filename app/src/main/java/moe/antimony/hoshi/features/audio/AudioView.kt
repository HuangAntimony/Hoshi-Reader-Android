package moe.antimony.hoshi.features.audio

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.features.backup.BackupSettingsView
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.reader.ReaderStatisticsSettingsView
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold
import moe.antimony.hoshi.features.settings.collectAsLoadedSettings
import moe.antimony.hoshi.features.sasayaki.SasayakiSettingsView
import moe.antimony.hoshi.importing.FileImportContent
import moe.antimony.hoshi.importing.ImportFileType
import moe.antimony.hoshi.features.sync.SyncSettingsView
import moe.antimony.hoshi.ui.HoshiBlockingProgressOverlay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsView(
    readerSettings: ReaderSettings,
    onReaderSettingsChange: (ReaderSettings) -> Unit,
    onClose: () -> Unit,
    onBooksRestored: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var destination by remember { mutableStateOf<AdvancedDestination?>(null) }
    if (destination == AdvancedDestination.Audio) {
        AudioSettingsView(
            onClose = { destination = null },
            modifier = modifier,
        )
        return
    }
    if (destination == AdvancedDestination.Statistics) {
        ReaderStatisticsSettingsView(
            settings = readerSettings,
            onSettingsChange = onReaderSettingsChange,
            onClose = { destination = null },
            modifier = modifier,
        )
        return
    }
    if (destination == AdvancedDestination.Sasayaki) {
        SasayakiSettingsView(
            onClose = { destination = null },
            modifier = modifier,
        )
        return
    }
    if (destination == AdvancedDestination.Backup) {
        BackupSettingsView(
            onClose = { destination = null },
            onBooksRestored = onBooksRestored,
            modifier = modifier,
        )
        return
    }
    if (destination == AdvancedDestination.Syncing) {
        SyncSettingsView(
            onClose = { destination = null },
            modifier = modifier,
        )
        return
    }

    val colorScheme = MaterialTheme.colorScheme
    SettingsDetailScaffold(
        title = "Advanced",
        onClose = onClose,
        modifier = modifier.fillMaxSize(),
        containerColor = colorScheme.background,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            advancedSettingsSections().forEach { section ->
                item {
                    GroupCard {
                        section.rows.forEachIndexed { index, row ->
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                leadingContent = { Icon(row.icon.imageVector(), contentDescription = null) },
                                headlineContent = { Text(row.title) },
                                supportingContent = row.subtitle?.let { subtitle -> { Text(subtitle) } },
                                modifier = Modifier.clickable { destination = row.destination },
                            )
                            if (index != section.rows.lastIndex) {
                                GroupDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

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
    var importProgress by remember { mutableStateOf<LocalAudioImportProgress?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    val hasImportedDatabase = importedSize != null

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
            }.onFailure { error ->
                importError = error.message ?: "Unable to import android.db."
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
                title = { Text("Audio", fontWeight = FontWeight.SemiBold) },
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
                    SectionTitle("Sources")
                    GroupCard {
                        loadedSettings.audioSources.forEachIndexed { index, source ->
                            AudioSourceRow(
                                source = source,
                                canDelete = !source.isDefault && source.url != AudioSettings.LocalAudioSource.url,
                                canMoveUp = index > 0,
                                canMoveDown = index < loadedSettings.audioSources.lastIndex,
                                onEnabledChange = { enabled ->
                                    save(
                                        loadedSettings.copy(
                                            audioSources = loadedSettings.audioSources.mapIndexed { sourceIndex, item ->
                                                if (sourceIndex == index) item.copy(isEnabled = enabled) else item
                                            },
                                        ),
                                    )
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
                    SectionTitle("Add Source")
                    GroupCard {
                        TextInputRow(label = "Name", value = nameInput, onValueChange = { nameInput = it })
                        GroupDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                TextInputRowContent(
                                    label = "URL",
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
                                Icon(Icons.Rounded.Add, contentDescription = "Add Source")
                            }
                        }
                    }
                    Text(
                        text = "Yomitan JSON audio sources are supported",
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
                            headlineContent = { Text("Auto-play on Lookup") },
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
                            headlineContent = { Text("Background Audio") },
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
                                        ) {
                                            Text(mode.displayName)
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
                item {
                    val loadedSettings = settings ?: return@item
                    SectionTitle("Local Audio")
                    GroupCard {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text("Enable") },
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
                                    headlineContent = { Text("Import android.db") },
                                    supportingContent = {
                                        Text("Copies the selected database in the background")
                                    },
                                    trailingContent = {
                                        Button(
                                            enabled = !isImporting,
                                            onClick = { importer.launch(ImportFileType.LocalAudioDatabase.mimeTypes) },
                                        ) {
                                            Text(if (isImporting) "Importing" else "Import")
                                        }
                                    },
                                )
                                importError?.let { message ->
                                    GroupDivider()
                                    ListItem(
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                        headlineContent = { Text("Import failed") },
                                        supportingContent = { Text(message) },
                                    )
                                }
                            }
                            importedSize?.let { size ->
                                GroupDivider()
                                ListItem(
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    headlineContent = {
                                        Text("android.db (${Formatter.formatFileSize(context, size)})")
                                    },
                                    trailingContent = {
                                        OutlinedButton(
                                            enabled = !isImporting,
                                            onClick = {
                                                repository.deleteDatabase()
                                                importedSize = null
                                                importError = null
                                                importProgress = null
                                            },
                                        ) {
                                            Text("Delete")
                                        }
                                    },
                                )
                            }
                        }
                    }
                    Text(
                        text = "1. Import copies android.db into Hoshi's private storage, so keep enough free space for one extra copy before importing.\n" +
                            "2. After import completes, you can safely delete the original external file to free space.",
                        color = colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 24.dp),
                    )
                }
            }
            if (isImporting) {
                HoshiBlockingProgressOverlay(
                    message = "Copying android.db",
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
            if (!source.isDefault && source.url != AudioSettings.LocalAudioSource.url) {
                Text(source.url, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(Icons.Rounded.ArrowUpward, contentDescription = "Move Up")
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(Icons.Rounded.ArrowDownward, contentDescription = "Move Down")
                }
                if (canDelete) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Delete Source")
                    }
                }
                Switch(checked = source.isEnabled, onCheckedChange = onEnabledChange)
            }
        },
    )
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
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun GroupCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
    ) {
        Column(content = { content() })
    }
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun BackIconButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
    }
}

internal enum class AdvancedDestination {
    Audio,
    Statistics,
    Sasayaki,
    Backup,
    Syncing,
}

internal enum class AdvancedSettingsIcon {
    Speaker,
    Chart,
    Waveform,
    Cloud,
    ExternalDrive,
}

internal data class AdvancedSettingsRow(
    val title: String,
    val destination: AdvancedDestination,
    val icon: AdvancedSettingsIcon,
    val subtitle: String? = null,
)

internal data class AdvancedSettingsSection(
    val rows: List<AdvancedSettingsRow>,
)

internal fun advancedSettingsSections(): List<AdvancedSettingsSection> =
    listOf(
        AdvancedSettingsSection(
            rows = listOf(
                AdvancedSettingsRow(
                    title = "Audio",
                    destination = AdvancedDestination.Audio,
                    icon = AdvancedSettingsIcon.Speaker,
                ),
                AdvancedSettingsRow(
                    title = "Statistics",
                    destination = AdvancedDestination.Statistics,
                    icon = AdvancedSettingsIcon.Chart,
                    subtitle = "Track per-book reading time, speed, and characters read",
                ),
                AdvancedSettingsRow(
                    title = "Sasayaki (Audiobooks)",
                    destination = AdvancedDestination.Sasayaki,
                    icon = AdvancedSettingsIcon.Waveform,
                    subtitle = "Read along with matched audiobook subtitles",
                ),
            ),
        ),
        AdvancedSettingsSection(
            rows = listOf(
                AdvancedSettingsRow(
                    title = "ッツ Sync",
                    destination = AdvancedDestination.Syncing,
                    icon = AdvancedSettingsIcon.Cloud,
                ),
            ),
        ),
        AdvancedSettingsSection(
            rows = listOf(
                AdvancedSettingsRow(
                    title = "Backup",
                    destination = AdvancedDestination.Backup,
                    icon = AdvancedSettingsIcon.ExternalDrive,
                ),
            ),
        ),
    )

private fun AdvancedSettingsIcon.imageVector(): ImageVector =
    when (this) {
        AdvancedSettingsIcon.Speaker -> Icons.AutoMirrored.Rounded.VolumeUp
        AdvancedSettingsIcon.Chart -> Icons.AutoMirrored.Rounded.ShowChart
        AdvancedSettingsIcon.Waveform -> Icons.Rounded.GraphicEq
        AdvancedSettingsIcon.Cloud -> Icons.Rounded.Cloud
        AdvancedSettingsIcon.ExternalDrive -> Icons.Rounded.Storage
    }

private const val ProgressUpdateBytes = 64L * 1024L * 1024L

private val LocalAudioImportProgress.fraction: Float
    get() = totalBytes?.takeIf { it > 0 }?.let { (copiedBytes.toFloat() / it.toFloat()).coerceIn(0f, 1f) } ?: 0f

private fun LocalAudioImportProgress.label(context: android.content.Context): String {
    val copied = Formatter.formatFileSize(context, copiedBytes)
    val total = totalBytes?.let { Formatter.formatFileSize(context, it) }
    return if (total == null) copied else "$copied of $total"
}
