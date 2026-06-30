package moe.antimony.hoshi.features.statistics

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.bookshelf.BookCoverBitmapCache
import moe.antimony.hoshi.features.bookshelf.toBookCoverSource

@Composable
internal fun StatisticsDistributionList(
    rows: List<BookDistributionRow>,
    modifier: Modifier = Modifier,
) {
    if (rows.isEmpty()) {
        Text(
            text = stringResource(R.string.statistics_no_reading_records),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(vertical = 24.dp),
        )
        return
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        rows.forEach { row ->
            DistributionRow(row = row)
        }
    }
}

@Composable
private fun DistributionRow(
    row: BookDistributionRow,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .padding(vertical = 6.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DistributionCover(row = row)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    R.string.statistics_distribution_meta_format,
                    formatStatisticsDuration(row.readingSeconds),
                    formatStatisticsCharacters(row.characters),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LinearProgressIndicator(
                progress = { row.percent.toFloat() / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            text = stringResource(R.string.statistics_percent_format, row.percent),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
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
    val cachedBitmap = remember(coverSource?.cacheKey) {
        BookCoverBitmapCache.get(coverSource)
    }
    val bitmap by produceState(initialValue = cachedBitmap, key1 = coverSource) {
        if (cachedBitmap == null) {
            value = BookCoverBitmapCache.load(coverSource)
        }
    }
    val coverBitmap = bitmap
    if (coverBitmap != null) {
        Image(
            bitmap = coverBitmap.asImageBitmap(),
            contentDescription = row.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 42.dp, height = 58.dp)
                .clip(RoundedCornerShape(6.dp)),
        )
    } else {
        Box(
            modifier = Modifier
                .size(width = 42.dp, height = 58.dp)
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
        }
    }
}
