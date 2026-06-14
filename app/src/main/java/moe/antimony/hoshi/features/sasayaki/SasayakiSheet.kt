package moe.antimony.hoshi.features.sasayaki

import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.epub.SasayakiPlaybackData
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.reader.ReaderBottomPanel
import moe.antimony.hoshi.features.reader.ReaderColorPickerDialog
import moe.antimony.hoshi.features.reader.ReaderColorSettingRow
import moe.antimony.hoshi.features.reader.readerSheetDensityMetrics
import moe.antimony.hoshi.features.reader.readerSheetStyle
import moe.antimony.hoshi.importing.ImportFileType
import moe.antimony.hoshi.importing.OpenDocumentContent
import moe.antimony.hoshi.importing.localizedImportMessage
import moe.antimony.hoshi.importing.validateImportFile
import moe.antimony.hoshi.ui.asString
import moe.antimony.hoshi.ui.HoshiBlockingProgressOverlay

internal val SasayakiSpeedSliderRange = 0.5f..2.0f
internal const val SasayakiSpeedSliderSteps = 29

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
    var skipActionMenuExpanded by remember { mutableStateOf(false) }
    var colorDialogRow by remember { mutableStateOf<SasayakiColorRow?>(null) }
    val importFailedMessage = stringResource(R.string.sasayaki_import_audiobook_failed)
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
                importError = error.localizedImportMessage(context, importFailedMessage)
            }
            isImporting = false
        }
    }

    ReaderBottomPanel(
        sheetStyle = sheetStyle,
        onDismiss = {
            if (!isImporting) {
                onDismiss()
            }
        },
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 28.dp),
            ) {
                if (player.hasAudio) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = player::previousCue) {
                            Icon(Icons.Rounded.FastRewind, contentDescription = stringResource(R.string.sasayaki_previous_cue))
                        }
                        IconButton(onClick = player::togglePlayback) {
                            Icon(
                                if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (player.isPlaying) {
                                    stringResource(R.string.sasayaki_pause)
                                } else {
                                    stringResource(R.string.sasayaki_play)
                                },
                            )
                        }
                        IconButton(onClick = player::nextCue) {
                            Icon(Icons.Rounded.FastForward, contentDescription = stringResource(R.string.sasayaki_next_cue))
                        }
                    }
                    Text(
                        text = "${formatDuration(player.currentTime)} / ${formatDuration(player.duration)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
                SasayakiLoadAudioRow(
                    summary = player.playback.audioStorageSummaryText(),
                    button = if (player.hasAudio) {
                        stringResource(R.string.sasayaki_remove_audio)
                    } else if (isImporting) {
                        stringResource(R.string.reader_appearance_importing)
                    } else {
                        stringResource(R.string.action_open)
                    },
                    enabled = !isImporting,
                    onClick = {
                        if (player.hasAudio) {
                            player.clearAudio()
                        } else {
                            importer.launch(ImportFileType.SasayakiAudiobook.mimeTypes)
                        }
                    },
                )
                SasayakiSettingsSwitchRow(
                    label = stringResource(R.string.sasayaki_copy_audiobook_to_storage),
                    checked = settings.copyAudiobookToPrivateStorage,
                    onCheckedChange = { onSettingsChange(settings.copy(copyAudiobookToPrivateStorage = it)) },
                )
                importError?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                player.errorMessage?.let { message ->
                    Text(
                        text = message.asString(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                SliderRow(
                    label = stringResource(R.string.sasayaki_delay),
                    valueText = String.format(java.util.Locale.US, "%+.2fs", player.delay),
                    value = player.delay.toFloat(),
                    range = -2f..2f,
                    steps = 79,
                    onValueChange = { player.setDelay(it.toDouble()) },
                )
                SliderRow(
                    label = stringResource(R.string.sasayaki_speed),
                    valueText = String.format(java.util.Locale.US, "%.2fx", player.rate),
                    value = player.rate,
                    range = SasayakiSpeedSliderRange,
                    steps = SasayakiSpeedSliderSteps,
                    onValueChange = { player.setRate(it) },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                Text(
                    text = stringResource(R.string.sasayaki_reader_controls),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                SasayakiSettingsSwitchRow(
                    label = stringResource(R.string.sasayaki_show_bottom_playback_controls),
                    checked = settings.showReaderBottomPlaybackControls,
                    onCheckedChange = { onSettingsChange(settings.copy(showReaderBottomPlaybackControls = it)) },
                )
                if (settings.showReaderBottomPlaybackControls) {
                    SasayakiSettingsSwitchRow(
                        label = stringResource(R.string.sasayaki_reverse_vertical_skip_buttons),
                        checked = settings.reverseVerticalReaderSkipButtons,
                        onCheckedChange = { onSettingsChange(settings.copy(reverseVerticalReaderSkipButtons = it)) },
                    )
                }
                SasayakiSettingsActionRow(
                    label = stringResource(R.string.sasayaki_skip_action),
                    selected = settings.readerSkipButtonAction,
                    expanded = skipActionMenuExpanded,
                    onExpandedChange = { skipActionMenuExpanded = it },
                    onSelected = { action ->
                        skipActionMenuExpanded = false
                        onSettingsChange(settings.copy(readerSkipButtonAction = action))
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                Text(
                    text = stringResource(R.string.sasayaki_playback),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                SasayakiSettingsSwitchRow(
                    label = stringResource(R.string.sasayaki_auto_scroll),
                    checked = settings.autoScroll,
                    onCheckedChange = { onSettingsChange(settings.copy(autoScroll = it)) },
                )
                SasayakiSettingsSwitchRow(
                    label = stringResource(R.string.sasayaki_auto_pause_on_lookup),
                    checked = settings.autoPause,
                    onCheckedChange = { onSettingsChange(settings.copy(autoPause = it)) },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                SasayakiColorSheetSection(
                    title = stringResource(R.string.sasayaki_light_theme),
                    rows = SasayakiColorRow.lightRows,
                    settings = settings,
                    onRowClick = { colorDialogRow = it },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                SasayakiColorSheetSection(
                    title = stringResource(R.string.sasayaki_dark_theme),
                    rows = SasayakiColorRow.darkRows,
                    settings = settings,
                    onRowClick = { colorDialogRow = it },
                )
            }
            if (isImporting) {
                HoshiBlockingProgressOverlay(
                    message = stringResource(R.string.sasayaki_importing_audio),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
    colorDialogRow?.let { row ->
        ReaderColorPickerDialog(
            title = stringResource(row.labelRes),
            initialColor = row.color(settings),
            defaultColor = row.defaultColor,
            onColorChange = { color ->
                onSettingsChange(row.updated(settings, color))
                colorDialogRow = null
            },
            onDismiss = { colorDialogRow = null },
        )
    }
}

@Composable
private fun SasayakiColorSheetSection(
    title: String,
    rows: List<SasayakiColorRow>,
    settings: SasayakiSettings,
    onRowClick: (SasayakiColorRow) -> Unit,
) {
    Text(
        text = title,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
    rows.forEach { row ->
        ReaderColorSettingRow(
            label = stringResource(row.labelRes),
            color = row.color(settings),
            onClick = { onRowClick(row) },
            horizontalPadding = 20.dp,
        )
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
    val metrics = readerSheetDensityMetrics()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = metrics.sasayakiSliderVerticalPaddingDp.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            Text(valueText, fontWeight = FontWeight.SemiBold)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range, steps = steps)
    }
}

@Composable
private fun SasayakiLoadAudioRow(
    summary: String,
    button: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val metrics = readerSheetDensityMetrics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = metrics.sasayakiRowVerticalPaddingDp.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.sasayaki_load_audio), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Button(enabled = enabled, onClick = onClick) {
            Text(button)
        }
    }
}

@Composable
private fun SasayakiSettingsActionRow(
    label: String,
    selected: SasayakiReaderSkipButtonAction,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelected: (SasayakiReaderSkipButtonAction) -> Unit,
) {
    val metrics = readerSheetDensityMetrics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = metrics.sasayakiRowVerticalPaddingDp.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Box {
            TextButton(onClick = { onExpandedChange(true) }) {
                Text(selected.labelText())
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
            ) {
                SasayakiReaderSkipButtonAction.entries.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(action.labelText()) },
                        onClick = { onSelected(action) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SasayakiSettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val metrics = readerSheetDensityMetrics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = metrics.sasayakiRowVerticalPaddingDp.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

private fun formatDuration(seconds: Double): String =
    DateUtils.formatElapsedTime(seconds.toLong().coerceAtLeast(0L))

@Composable
private fun SasayakiPlaybackData.audioStorageSummaryText(): String =
    when {
        audioFileName != null -> stringResource(R.string.sasayaki_storage_copied)
        audioUri != null -> stringResource(R.string.sasayaki_storage_linked)
        else -> stringResource(R.string.sasayaki_storage_select_audio)
    }
