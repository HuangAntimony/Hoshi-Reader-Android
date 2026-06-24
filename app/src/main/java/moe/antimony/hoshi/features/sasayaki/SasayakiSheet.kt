package moe.antimony.hoshi.features.sasayaki

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.SasayakiMatchData
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
internal const val SasayakiAudiobookCoverWidthDp = 48
internal const val SasayakiAudiobookCoverHeightDp = 68

@Composable
internal fun SasayakiSheet(
    player: SasayakiPlayer,
    audioRepository: SasayakiAudioRepository,
    settings: SasayakiSettings,
    bookTitle: String,
    bookCoverFile: File?,
    audiobookMetadata: SasayakiAudiobookMetadata,
    subtitleMatchData: SasayakiMatchData?,
    matchDependencies: SasayakiMatchDependencies?,
    chapters: List<SasayakiAudiobookChapter>,
    onSubtitleMatchUpdated: (SasayakiMatchData) -> Unit,
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
                if (sasayakiShouldShowPlaybackHeader(player.hasAudio)) {
                    SasayakiPlaybackHeader(
                        player = player,
                        bookTitle = bookTitle,
                        bookCoverFile = bookCoverFile,
                        audiobookMetadata = audiobookMetadata,
                        currentChapter = currentChapter,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                    )
                }
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
                        subtitleMatchData = subtitleMatchData,
                        matchDependencies = matchDependencies,
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
                        onSubtitleMatchUpdated = onSubtitleMatchUpdated,
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
    bookTitle: String,
    bookCoverFile: File?,
    audiobookMetadata: SasayakiAudiobookMetadata,
    currentChapter: SasayakiAudiobookChapter?,
    modifier: Modifier = Modifier,
) {
    val headerInfo = sasayakiPlaybackHeaderInfo(
        playback = player.playback,
        metadata = audiobookMetadata,
        fallbackBookTitle = bookTitle,
        currentChapter = currentChapter,
    )
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SasayakiAudiobookCover(
                metadata = audiobookMetadata,
                fallbackCoverFile = bookCoverFile,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = headerInfo.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                headerInfo.artist?.let { artist ->
                    Text(
                        text = artist,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                headerInfo.chapterTitle?.let { chapterTitle ->
                    Text(
                        text = chapterTitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        SasayakiPlaybackProgress(player = player)
    }
}

@Composable
private fun SasayakiAudiobookCover(
    metadata: SasayakiAudiobookMetadata,
    fallbackCoverFile: File?,
) {
    val coverBitmap = rememberSasayakiCoverBitmap(metadata, fallbackCoverFile)
    Box(
        modifier = Modifier
            .width(SasayakiAudiobookCoverWidthDp.dp)
            .height(SasayakiAudiobookCoverHeightDp.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (coverBitmap != null) {
            Image(
                bitmap = coverBitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.sasayaki_audiobook_cover),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Album,
                contentDescription = stringResource(R.string.sasayaki_audiobook_cover),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun rememberSasayakiCoverBitmap(
    metadata: SasayakiAudiobookMetadata,
    fallbackCoverFile: File?,
): Bitmap? {
    var bitmap by remember(metadata.artworkData, fallbackCoverFile?.absolutePath) {
        mutableStateOf<Bitmap?>(null)
    }
    LaunchedEffect(metadata.artworkData, fallbackCoverFile?.absolutePath) {
        bitmap = withContext(Dispatchers.IO) {
            metadata.artworkData?.let(::decodeSampledSasayakiCoverBitmap)
                ?: fallbackCoverFile?.let(::decodeSampledSasayakiCoverBitmap)
        }
    }
    return bitmap
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatDuration(if (isScrubbing) sliderValue.toDouble() else currentTime),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
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
            Text(
                text = formatDuration(duration),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
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
    subtitleMatchData: SasayakiMatchData?,
    matchDependencies: SasayakiMatchDependencies?,
    importError: String?,
    isImporting: Boolean,
    onAudioAction: () -> Unit,
    onSettingsChange: (SasayakiSettings) -> Unit,
    onSubtitleMatchUpdated: (SasayakiMatchData) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 28.dp),
    ) {
        SasayakiAudiobookResourceCard(
            player = player,
            settings = settings,
            isImporting = isImporting,
            onSettingsChange = onSettingsChange,
            onAction = onAudioAction,
        )
        importError?.let { message ->
            SasayakiErrorMessage(message = message)
        }
        player.errorMessage?.let { message ->
            SasayakiErrorMessage(message = message.asString())
        }
        SasayakiSubtitleMatchSection(
            dependencies = matchDependencies,
            currentMatchData = subtitleMatchData,
            onMatchUpdated = onSubtitleMatchUpdated,
        )
    }
}

@Composable
private fun SasayakiAudiobookResourceCard(
    player: SasayakiPlayer,
    settings: SasayakiSettings,
    isImporting: Boolean,
    onSettingsChange: (SasayakiSettings) -> Unit,
    onAction: () -> Unit,
) {
    SasayakiResourceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.sasayaki_audiobook_resource),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = player.playback.audioStorageSummaryText(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Button(
                enabled = !isImporting,
                onClick = onAction,
            ) {
                Text(
                    if (player.hasAudio) {
                        stringResource(R.string.sasayaki_remove_audio)
                    } else if (isImporting) {
                        stringResource(R.string.reader_appearance_importing)
                    } else {
                        stringResource(R.string.action_open)
                    },
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.sasayaki_copy_audiobook_to_storage),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = settings.copyAudiobookToPrivateStorage,
                onCheckedChange = { onSettingsChange(settings.copy(copyAudiobookToPrivateStorage = it)) },
            )
        }
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
    val rowInfo = sasayakiChapterRowInfo(chapter)
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rowInfo.title ?: stringResource(R.string.sasayaki_unknown_chapter),
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
            Text(
                text = formatSasayakiChapterRowTime(rowInfo.startSeconds),
                color = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                textAlign = TextAlign.End,
            )
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
internal fun SasayakiResourceCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            content()
        }
    }
}

@Composable
internal fun SasayakiInlineActionRow(
    label: String,
    value: String,
    action: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = value,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(enabled = actionEnabled, onClick = onAction) {
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

private fun formatDuration(seconds: Double): String {
    val totalSeconds = seconds.nonNegativeFiniteSeconds().toLong()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val remainingSeconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, remainingSeconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, remainingSeconds)
    }
}

internal data class SasayakiChapterRowInfo(
    val title: String?,
    val startSeconds: Double,
)

internal fun sasayakiChapterRowInfo(chapter: SasayakiAudiobookChapter): SasayakiChapterRowInfo =
    SasayakiChapterRowInfo(
        title = chapter.title.trim().takeIf { it.isNotBlank() },
        startSeconds = chapter.startSeconds,
    )

internal fun formatSasayakiChapterRowTime(seconds: Double): String =
    formatDuration(seconds)

private fun Double.nonNegativeFiniteSeconds(): Double =
    if (isFinite()) coerceAtLeast(0.0) else 0.0

internal data class SasayakiPlaybackHeaderInfo(
    val title: String,
    val artist: String?,
    val chapterTitle: String?,
)

internal fun sasayakiPlaybackHeaderInfo(
    playback: SasayakiPlaybackData,
    metadata: SasayakiAudiobookMetadata,
    fallbackBookTitle: String,
    currentChapter: SasayakiAudiobookChapter?,
): SasayakiPlaybackHeaderInfo {
    val normalizedMetadata = metadata.normalized()
    return SasayakiPlaybackHeaderInfo(
        title = normalizedMetadata.title
            ?: playback.audioSourceDisplayTitle()
            ?: fallbackBookTitle.trim().takeIf { it.isNotBlank() }
            ?: "",
        artist = normalizedMetadata.artist,
        chapterTitle = currentChapter?.title?.trim()?.takeIf { it.isNotBlank() },
    )
}

private fun SasayakiPlaybackData.audioSourceDisplayTitle(): String? =
    when {
        audioFileName != null -> audioFileName.audioSourceNameTitle()
        audioUri != null -> audioUri.audioSourceNameTitle()
        else -> null
    }

private fun String.audioSourceNameTitle(): String? {
    val name = substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('/')
        .trim()
    val title = name.substringBeforeLast('.', missingDelimiterValue = name)
        .trim()
        .takeIf { it.isNotBlank() }
        ?.takeUnless { it.equals("sasayaki_audio", ignoreCase = true) }
    return title
}

private const val SasayakiCoverMaxDimensionPx = 512

private fun decodeSampledSasayakiCoverBitmap(file: File): Bitmap? {
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply {
            inSampleSize = sasayakiCoverSampleSize(bounds.outWidth, bounds.outHeight)
        },
    )
}

private fun decodeSampledSasayakiCoverBitmap(data: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    return BitmapFactory.decodeByteArray(
        data,
        0,
        data.size,
        BitmapFactory.Options().apply {
            inSampleSize = sasayakiCoverSampleSize(bounds.outWidth, bounds.outHeight)
        },
    )
}

private fun sasayakiCoverSampleSize(width: Int, height: Int): Int {
    val largest = maxOf(width, height)
    if (largest <= SasayakiCoverMaxDimensionPx || largest <= 0) return 1
    var sampleSize = 1
    while (largest / (sampleSize * 2) >= SasayakiCoverMaxDimensionPx) {
        sampleSize *= 2
    }
    return sampleSize
}

@Composable
private fun SasayakiPlaybackData.audioStorageSummaryText(): String =
    when {
        audioFileName != null -> stringResource(R.string.sasayaki_storage_copied)
        audioUri != null -> stringResource(R.string.sasayaki_storage_linked)
        else -> stringResource(R.string.sasayaki_storage_select_audio)
    }
