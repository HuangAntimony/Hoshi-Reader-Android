package moe.antimony.hoshi.features.sasayaki

import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.features.reader.ReaderSheetDragHandle
import moe.antimony.hoshi.features.reader.readerSheetStyle
import moe.antimony.hoshi.importing.ImportFileType
import moe.antimony.hoshi.importing.OpenDocumentContent
import moe.antimony.hoshi.importing.validateImportFile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SasayakiSheet(
    player: SasayakiPlayer,
    audioRepository: SasayakiAudioRepository,
    settings: SasayakiSettings,
    onSettingsChange: (SasayakiSettings) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetStyle = readerSheetStyle()
    var isImporting by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    val importer = rememberLauncherForActivityResult(OpenDocumentContent()) { uri ->
        if (uri == null || isImporting) return@rememberLauncherForActivityResult
        isImporting = true
        importError = null
        scope.launch {
            runCatching {
                val copyToPrivateStorage = settings.copyAudiobookToPrivateStorage
                withContext(Dispatchers.IO) {
                    context.contentResolver.validateImportFile(uri, ImportFileType.SasayakiAudiobook)
                    if (copyToPrivateStorage) {
                        audioRepository.importAudio(context.contentResolver, uri)
                    } else {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                        null
                    }
                }
            }.onSuccess { copiedFileName ->
                player.importAudio(
                    audioUri = uri,
                    copiedAudioFileName = copiedFileName,
                )
            }.onFailure { error ->
                importError = error.localizedMessage ?: "Unable to import audiobook."
            }
            isImporting = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        containerColor = sheetStyle.containerColor,
        contentColor = sheetStyle.contentColor,
        scrimColor = sheetStyle.scrimColor,
        dragHandle = { ReaderSheetDragHandle(sheetStyle) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 28.dp),
        ) {
            if (player.hasAudio) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = player::previousCue) {
                        Icon(Icons.Rounded.FastRewind, contentDescription = "Previous Cue")
                    }
                    IconButton(onClick = player::togglePlayback) {
                        Icon(
                            if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (player.isPlaying) "Pause" else "Play",
                        )
                    }
                    IconButton(onClick = player::nextCue) {
                        Icon(Icons.Rounded.FastForward, contentDescription = "Next Cue")
                    }
                }
                Text(
                    text = "${formatDuration(player.currentTime)} / ${formatDuration(player.duration)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("Load Audio") },
                supportingContent = {
                    Text(player.audioStorageSummary)
                },
                trailingContent = {
                    Button(
                        enabled = !isImporting,
                        onClick = {
                            if (player.hasAudio) {
                                player.clearAudio()
                            } else {
                                importer.launch(ImportFileType.SasayakiAudiobook.mimeTypes)
                            }
                        },
                    ) {
                        Text(if (player.hasAudio) "Remove" else if (isImporting) "Importing" else "Open")
                    }
                },
            )
            SasayakiSettingsSwitchRow(
                label = "Copy Audiobook to App Storage",
                checked = settings.copyAudiobookToPrivateStorage,
                onCheckedChange = { onSettingsChange(settings.copy(copyAudiobookToPrivateStorage = it)) },
            )
            importError?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            player.errorMessage?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            SliderRow(
                label = "Delay",
                valueText = String.format(java.util.Locale.US, "%+.2fs", player.delay),
                value = player.delay.toFloat(),
                range = -2f..2f,
                steps = 79,
                onValueChange = { player.setDelay(it.toDouble()) },
            )
            SliderRow(
                label = "Speed",
                valueText = String.format(java.util.Locale.US, "%.2fx", player.rate),
                value = player.rate,
                range = 0.5f..1.5f,
                steps = 19,
                onValueChange = { player.setRate(it) },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            Text(
                text = "Settings",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )
            SasayakiSettingsSwitchRow(
                label = "Show Sasayaki Toggle",
                checked = settings.showReaderToggle,
                onCheckedChange = { onSettingsChange(settings.copy(showReaderToggle = it)) },
            )
            SasayakiSettingsSwitchRow(
                label = "Auto-Scroll",
                checked = settings.autoScroll,
                onCheckedChange = { onSettingsChange(settings.copy(autoScroll = it)) },
            )
            SasayakiSettingsSwitchRow(
                label = "Auto-Pause on Lookup",
                checked = settings.autoPause,
                onCheckedChange = { onSettingsChange(settings.copy(autoPause = it)) },
            )
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            Text(valueText, fontWeight = FontWeight.SemiBold)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range, steps = steps)
    }
}

@Composable
private fun SasayakiSettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(label) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        },
    )
}

private fun formatDuration(seconds: Double): String =
    DateUtils.formatElapsedTime(seconds.toLong().coerceAtLeast(0L))
