package moe.antimony.hoshi.features.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import coil3.compose.AsyncImage
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.bookshelf.toBookCoverSource

@Composable
internal fun StatisticsBooksSection(
    rows: List<BookDistributionRow>,
    range: StatisticsDateRange,
    selectedBucket: StatisticsDateRange?,
    onOpenBook: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var visibleCount by rememberSaveable(range, selectedBucket) { mutableIntStateOf(5) }
    StatisticsSection(
        title = stringResource(R.string.statistics_books),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        modifier = modifier,
    ) {
        if (rows.isEmpty()) {
            Text(
                text = stringResource(R.string.statistics_no_reading_records),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
        rows.take(visibleCount).forEachIndexed { index, row ->
            key(row.folder) {
                if (index > 0) HorizontalDivider(
                    modifier = Modifier.padding(start = 46.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                DistributionRow(row = row, onClick = { onOpenBook(row.folder) })
            }
        }
        if (visibleCount < rows.size) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 46.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            TextButton(
                onClick = { visibleCount += 5 },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                Text(stringResource(R.string.statistics_show_more_books), modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun DistributionRow(row: BookDistributionRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DistributionCover(row = row)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (row.isArchived) {
                    Icon(
                        Icons.Outlined.Delete, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.statistics_characters_value_format, formatStatisticsGroupedCount(row.characters)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = formatStatisticsDuration(row.readingSeconds),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(
                Modifier.fillMaxWidth(row.timeFraction).height(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)),
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun DistributionCover(row: BookDistributionRow) {
    val coverSource = remember(row.coverPath) {
        row.coverPath?.let { path ->
            runCatching { File(path).toBookCoverSource() }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .size(width = 34.dp, height = 48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = row.title.firstOrNull()?.uppercase() ?: "",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        if (coverSource != null) {
            AsyncImage(
                model = coverSource,
                contentDescription = row.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
