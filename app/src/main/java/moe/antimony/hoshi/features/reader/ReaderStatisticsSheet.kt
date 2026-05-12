package moe.antimony.hoshi.features.reader

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
                start = 24.dp,
                top = if (chrome.showHeader || chrome.showCloseButton) 0.dp else 8.dp,
                end = 24.dp,
                bottom = 32.dp,
            ),
        ) {
            item {
                StatisticsSection(
                    title = "Session",
                    statistic = state.session,
                    isTracking = state.isTracking,
                    onToggleTracking = onToggleTracking,
                    extraRows = listOf(
                        "Time to finish Book:" to formatDurationSeconds(
                            secondsRemaining(
                                remainingCharacters = totalCharacters - currentCharacter,
                                speed = state.session.lastReadingSpeed,
                            ),
                        ),
                        "Time to finish Chapter:" to formatDurationSeconds(
                            secondsRemaining(
                                remainingCharacters = currentChapterEndCharacter - currentCharacter,
                                speed = state.session.lastReadingSpeed,
                            ),
                        ),
                    ),
                )
            }
            item {
                StatisticsSection(title = "Today", statistic = state.today)
            }
            item {
                StatisticsSection(title = "All Time", statistic = state.allTime)
            }
        }
    }
}

@Composable
private fun StatisticsSection(
    title: String,
    statistic: ReadingStatistics,
    isTracking: Boolean? = null,
    onToggleTracking: () -> Unit = {},
    extraRows: List<Pair<String, String>> = emptyList(),
) {
    Column(modifier = Modifier.padding(bottom = 22.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
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
                        contentDescription = if (isTracking) "Pause statistics" else "Start statistics",
                    )
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 0.dp,
        ) {
            Column {
                StatisticRow("Characters Read:", statistic.charactersRead.toString())
                StatisticsDivider()
                StatisticRow("Reading Speed:", "${statistic.lastReadingSpeed} / h")
                StatisticsDivider()
                StatisticRow("Reading Time:", formatDurationSeconds(statistic.readingTime))
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
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

private fun secondsRemaining(remainingCharacters: Int, speed: Int): Double {
    if (speed <= 0) return 0.0
    return max(remainingCharacters, 0).toDouble() / (speed.toDouble() / 3600.0)
}
