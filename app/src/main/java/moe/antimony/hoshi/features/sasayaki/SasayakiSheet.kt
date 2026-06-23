package moe.antimony.hoshi.features.sasayaki

import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.SasayakiPlaybackData
import moe.antimony.hoshi.features.reader.ReaderBottomPanel
import moe.antimony.hoshi.features.reader.ReaderColorPickerDialog
import moe.antimony.hoshi.features.reader.ReaderColorSettingRow
import moe.antimony.hoshi.features.reader.readerSheetDensityMetrics
import moe.antimony.hoshi.features.reader.readerSheetStyle
import moe.antimony.hoshi.importing.ImportFileType
import moe.antimony.hoshi.importing.OpenDocumentContent
import moe.antimony.hoshi.importing.localizedImportMessage
import moe.antimony.hoshi.importing.validateImportFile
import moe.antimony.hoshi.ui.HoshiBlockingProgressOverlay
import moe.antimony.hoshi.ui.asString

internal val SasayakiSpeedSliderRange = 0.5f..2.0f
internal const val SasayakiSpeedSliderSteps = 29

@Composable
internal fun SasayakiSheet(
    player: SasayakiPlayer,
    audioRepository: SasayakiAudioRepository,
    settings: SasayakiSettings,
    hasSubtitleMatch: Boolean,
    chapters: List<SasayakiAudiobookChapter>,
    onMatchSubtitles: (() -> Unit)?,
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
    val defaultTab = sasayakiDefaultSheetTab(
        hasAudio = player.hasAudio,
        hasChapters = chapters.isNotEmpty(),
    )
    var selectedTab by remember { mutableStateOf(defaultTab) }
    var userSelectedTab by remember { mutableStateOf(false) }
    val currentChapter = SasayakiAudiobookChapters.currentChapterAt(chapters, player.currentTime)
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

    LaunchedEffect(defaultTab) {
        if (!userSelectedTab) {
            selectedTab = defaultTab
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
            Column(modifier = Modifier.fillMaxSize()) {
                SasayakiPlaybackHeader(
                    player = player,
                    currentChapter = currentChapter,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                )
                SasayakiSheetTabs(
                    selectedTab = selectedTab,
                    onSelectedTabChange = { tab ->
                        userSelectedTab = true
                        selectedTab = tab
                    },
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
                )
                when (selectedTab) {
                    SasayakiSheetTab.Resources -> SasayakiResourcesTab(
                        player = player,
                        settings = settings,
                        hasSubtitleMatch = hasSubtitleMatch,
                        importError = importError,
                        isImporting = isImporting,
                        onAudioAction = {
                            if (player.hasAudio) {
                                player.clearAudio()
                            } else {
                                importer.launch(ImportFileType.SasayakiAudiobook.mimeTypes)
                            }
                        },
                        onSettingsChange = onSettingsChange,
                        onMatchSubtitles = onMatchSubtitles,
                        modifier = Modifier.weight(1f),
                    )
                    SasayakiSheetTab.Chapters -> SasayakiChaptersTab(
                        chapters = chapters,
                        currentChapter = currentChapter,
                        onChapterJump = { chapter -> player.seekTo(chapter.startSeconds) },
                        modifier = Modifier.weight(1f),
                    )
                    SasayakiSheetTab.Settings -> SasayakiSettingsTab(
                        player = player,
                        settings = settings,
                        skipActionMenuExpanded = skipActionMenuExpanded,
                        onSkipActionMenuExpandedChange = { skipActionMenuExpanded = it },
                        onSettingsChange = onSettingsChange,
                        onColorRowClick = { colorDialogRow = it },
                        modifier = Modifier.weight(1f),
                    )
                }
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
private fun SasayakiPlaybackHeader(
    player: SasayakiPlayer,
    currentChapter: SasayakiAudiobookChapter?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.sasayaki_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = currentChapter?.title ?: player.playback.audioStorageSummaryText(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                enabled = player.hasAudio,
                onClick = player::previousCue,
            ) {
                Icon(Icons.Rounded.FastRewind, contentDescription = stringResource(R.string.sasayaki_previous_cue))
            }
            IconButton(
                enabled = player.hasAudio,
                onClick = player::togglePlayback,
            ) {
                Icon(
                    if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (player.isPlaying) {
                        stringResource(R.string.sasayaki_pause)
                    } else {
                        stringResource(R.string.sasayaki_play)
                    },
                )
            }
            IconButton(
                enabled = player.hasAudio,
                onClick = player::nextCue,
            ) {
                Icon(Icons.Rounded.FastForward, contentDescription = stringResource(R.string.sasayaki_next_cue))
            }
        }
        SasayakiPlaybackProgress(player = player)
    }
}

@Composable
private fun SasayakiPlaybackProgress(player: SasayakiPlayer) {
    val duration = player.duration.nonNegativeFiniteSeconds()
    val canSeek = player.hasAudio && duration > 0.0
    val rangeEnd = duration.toFloat().coerceAtLeast(1f)
    var isScrubbing by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(0f) }
    val currentTime = player.currentTime
        .nonNegativeFiniteSeconds()
        .coerceAtMost(duration.takeIf { it > 0.0 } ?: Double.MAX_VALUE)

    LaunchedEffect(currentTime, rangeEnd, isScrubbing) {
        if (!isScrubbing) {
            sliderValue = currentTime.toFloat().coerceIn(0f, rangeEnd)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = sliderValue.coerceIn(0f, rangeEnd),
            enabled = canSeek,
            onValueChange = { value ->
                isScrubbing = true
                sliderValue = value.coerceIn(0f, rangeEnd)
            },
            onValueChangeFinished = {
                if (canSeek) {
                    player.seekTo(sliderValue.toDouble())
                }
                isScrubbing = false
            },
            valueRange = 0f..rangeEnd,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatDuration(if (isScrubbing) sliderValue.toDouble() else currentTime),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = formatDuration(duration),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SasayakiSheetTabs(
    selectedTab: SasayakiSheetTab,
    onSelectedTabChange: (SasayakiSheetTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        SasayakiSheetTab.entries.forEach { tab ->
            val selected = tab == selectedTab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
                        shape = RoundedCornerShape(10.dp),
                    )
                    .clickable { onSelectedTabChange(tab) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(tab.labelRes),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SasayakiResourcesTab(
    player: SasayakiPlayer,
    settings: SasayakiSettings,
    hasSubtitleMatch: Boolean,
    importError: String?,
    isImporting: Boolean,
    onAudioAction: () -> Unit,
    onSettingsChange: (SasayakiSettings) -> Unit,
    onMatchSubtitles: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 28.dp),
    ) {
        SasayakiResourceRow(
            title = stringResource(R.string.sasayaki_audiobook_resource),
            summary = player.playback.audioStorageSummaryText(),
            action = if (player.hasAudio) {
                stringResource(R.string.sasayaki_remove_audio)
            } else if (isImporting) {
                stringResource(R.string.reader_appearance_importing)
            } else {
                stringResource(R.string.action_open)
            },
            actionEnabled = !isImporting,
            onAction = onAudioAction,
        )
        SasayakiSettingsSwitchRow(
            label = stringResource(R.string.sasayaki_copy_audiobook_to_storage),
            checked = settings.copyAudiobookToPrivateStorage,
            onCheckedChange = { onSettingsChange(settings.copy(copyAudiobookToPrivateStorage = it)) },
        )
        importError?.let { message ->
            SasayakiErrorMessage(message = message)
        }
        player.errorMessage?.let { message ->
            SasayakiErrorMessage(message = message.asString())
        }
        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        SasayakiResourceRow(
            title = stringResource(R.string.sasayaki_subtitle_match),
            summary = if (hasSubtitleMatch) {
                stringResource(R.string.sasayaki_subtitle_match_ready)
            } else {
                stringResource(R.string.sasayaki_subtitle_match_optional)
            },
            action = stringResource(R.string.sasayaki_match_title),
            actionEnabled = onMatchSubtitles != null,
            onAction = { onMatchSubtitles?.invoke() },
        )
    }
}

@Composable
private fun SasayakiChaptersTab(
    chapters: List<SasayakiAudiobookChapter>,
    currentChapter: SasayakiAudiobookChapter?,
    onChapterJump: (SasayakiAudiobookChapter) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (chapters.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(
                text = stringResource(R.string.sasayaki_no_chapters),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        items(chapters, key = { chapter -> chapter.index }) { chapter ->
            SasayakiChapterRow(
                chapter = chapter,
                selected = chapter.index == currentChapter?.index,
                onClick = { onChapterJump(chapter) },
            )
        }
    }
}

@Composable
private fun SasayakiChapterRow(
    chapter: SasayakiAudiobookChapter,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 2.dp),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatDuration(chapter.startSeconds),
                color = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chapter.title.ifBlank { stringResource(R.string.sasayaki_unknown_chapter) },
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selected) {
                    Text(
                        text = stringResource(R.string.sasayaki_current_chapter),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun SasayakiSettingsTab(
    player: SasayakiPlayer,
    settings: SasayakiSettings,
    skipActionMenuExpanded: Boolean,
    onSkipActionMenuExpandedChange: (Boolean) -> Unit,
    onSettingsChange: (SasayakiSettings) -> Unit,
    onColorRowClick: (SasayakiColorRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 28.dp),
    ) {
        SliderRow(
            label = stringResource(R.string.sasayaki_delay),
            valueText = String.format(Locale.US, "%+.2fs", player.delay),
            value = player.delay.toFloat(),
            range = -2f..2f,
            steps = 79,
            onValueChange = { player.setDelay(it.toDouble()) },
        )
        SliderRow(
            label = stringResource(R.string.sasayaki_speed),
            valueText = String.format(Locale.US, "%.2fx", player.rate),
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
            onExpandedChange = onSkipActionMenuExpandedChange,
            onSelected = { action ->
                onSkipActionMenuExpandedChange(false)
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
            onRowClick = onColorRowClick,
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        SasayakiColorSheetSection(
            title = stringResource(R.string.sasayaki_dark_theme),
            rows = SasayakiColorRow.darkRows,
            settings = settings,
            onRowClick = onColorRowClick,
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
private fun SasayakiResourceRow(
    title: String,
    summary: String,
    action: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
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
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Button(enabled = actionEnabled, onClick = onAction) {
            Text(action)
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

@Composable
private fun SasayakiErrorMessage(message: String) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

private fun formatDuration(seconds: Double): String =
    DateUtils.formatElapsedTime(seconds.nonNegativeFiniteSeconds().toLong())

private fun Double.nonNegativeFiniteSeconds(): Double =
    if (isFinite()) coerceAtLeast(0.0) else 0.0

@Composable
private fun SasayakiPlaybackData.audioStorageSummaryText(): String =
    when {
        audioFileName != null -> stringResource(R.string.sasayaki_storage_copied)
        audioUri != null -> stringResource(R.string.sasayaki_storage_linked)
        else -> stringResource(R.string.sasayaki_storage_select_audio)
    }
