package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.ui.theme.hoshiContainerBorder
import moe.antimony.hoshi.ui.theme.hoshiSurfaces
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.ReadingStatistics
import kotlin.math.max

internal data class ReaderStatisticsSheetChrome(
    val showHeader: Boolean,
    val showCloseButton: Boolean,
    val opensAsReaderPanel: Boolean,
)

internal fun readerStatisticsSheetChrome(): ReaderStatisticsSheetChrome =
    ReaderStatisticsSheetChrome(
        showHeader = false,
        showCloseButton = false,
        opensAsReaderPanel = true,
    )

@Composable
internal fun ReaderStatisticsSheet(
    state: ReaderStatisticsState,
    currentCharacter: Int,
    currentChapterEndCharacter: Int,
    totalCharacters: Int,
    progressDisplay: ReaderProgressDisplay = ReaderProgressDisplay.characters(),
    onToggleTracking: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetStyle = readerSheetStyle()
    val chrome = readerStatisticsSheetChrome()
    ReaderBottomPanel(
        sheetStyle = sheetStyle,
        onDismiss = onDismiss,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(
                start = 20.dp,
                top = if (chrome.showHeader || chrome.showCloseButton) 0.dp else 4.dp,
                end = 20.dp,
                bottom = 24.dp,
            ),
        ) {
            item {
                StatisticsSection(
                    title = stringResource(R.string.reader_statistics_session),
                    statistic = state.session,
                    progressDisplay = progressDisplay,
                    isTracking = state.isTracking,
                    onToggleTracking = onToggleTracking,
                    extraRows = listOf(
                        stringResource(R.string.reader_statistics_time_to_finish_book) to formatDurationSeconds(
                            secondsRemaining(
                                remainingCharacters = totalCharacters - currentCharacter,
                                speed = state.session.lastReadingSpeed,
                                progressDisplay = progressDisplay,
                            ),
                        ),
                        stringResource(R.string.reader_statistics_time_to_finish_chapter) to formatDurationSeconds(
                            secondsRemaining(
                                remainingCharacters = currentChapterEndCharacter - currentCharacter,
                                speed = state.session.lastReadingSpeed,
                                progressDisplay = progressDisplay,
                            ),
                        ),
                    ),
                )
            }
            item {
                StatisticsSection(
                    title = stringResource(R.string.reader_statistics_today),
                    statistic = state.today,
                    progressDisplay = progressDisplay,
                )
            }
            item {
                StatisticsSection(
                    title = stringResource(R.string.reader_statistics_all_time),
                    statistic = state.allTime,
                    progressDisplay = progressDisplay,
                )
            }
        }
    }
}

@Composable
private fun StatisticsSection(
    title: String,
    statistic: ReadingStatistics,
    progressDisplay: ReaderProgressDisplay,
    isTracking: Boolean? = null,
    onToggleTracking: () -> Unit = {},
    extraRows: List<Pair<String, String>> = emptyList(),
) {
    val metrics = readerSheetDensityMetrics()
    Column(modifier = Modifier.padding(bottom = metrics.statisticsSectionBottomPaddingDp.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (isTracking != null) {
                IconButton(onClick = onToggleTracking) {
                    Icon(
                        imageVector = if (isTracking) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isTracking) {
                            stringResource(R.string.reader_statistics_pause)
                        } else {
                            stringResource(R.string.reader_statistics_start)
                        },
                    )
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = hoshiSurfaces.group,
            border = hoshiContainerBorder(),
            tonalElevation = 0.dp,
        ) {
            Column {
                StatisticRow(
                    stringResource(
                        if (progressDisplay.usesWords) {
                            R.string.reader_statistics_words_read
                        } else {
                            R.string.reader_statistics_characters_read
                        },
                    ),
                    progressDisplay.countText(statistic.charactersRead),
                )
                StatisticsDivider()
                StatisticRow(stringResource(R.string.reader_statistics_reading_speed), progressDisplay.speedText(statistic.lastReadingSpeed))
                StatisticsDivider()
                StatisticRow(stringResource(R.string.reader_statistics_reading_time), formatDurationSeconds(statistic.readingTime))
                extraRows.forEach { (label, value) ->
                    StatisticsDivider()
                    StatisticRow(label, value)
                }
            }
        }
    }
}

@Composable
private fun StatisticRow(label: String, value: String) {
    val metrics = readerSheetDensityMetrics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = metrics.statisticsRowVerticalPaddingDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.weight(1f))
        Text(text = value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatisticsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

internal fun formatDurationSeconds(seconds: Double): String {
    val totalSeconds = max(seconds.toLong(), 0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val remainingSeconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m ${remainingSeconds}s"
        minutes > 0 -> "${minutes}m ${remainingSeconds}s"
        else -> "${remainingSeconds}s"
    }
}

private fun secondsRemaining(
    remainingCharacters: Int,
    speed: Int,
    progressDisplay: ReaderProgressDisplay,
): Double {
    val displayedSpeed = progressDisplay.displayCount(speed)
    if (displayedSpeed <= 0) return 0.0
    return progressDisplay.displayCount(max(remainingCharacters, 0)).toDouble() / (displayedSpeed.toDouble() / 3600.0)
}
