package moe.antimony.hoshi.features.reader

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubTocItem
import moe.antimony.hoshi.ui.hoshiOutlinedTextFieldColors
import moe.antimony.hoshi.ui.hoshiSingleLineTextFieldLineLimits
import moe.antimony.hoshi.ui.rememberSyncedTextFieldState
import moe.antimony.hoshi.ui.theme.LocalHoshiEInkMode
import java.util.Locale

@Composable
internal fun ReaderChapterSheet(
    book: EpubBook,
    currentPosition: ReaderChapterPosition,
    progressDisplay: ReaderProgressDisplay = ReaderProgressDisplay.characters(),
    onJump: (ReaderChapterPosition, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val rows = remember(book, currentPosition.index) { book.chapterRows(currentPosition.index) }
    val sheetStyle = readerSheetStyle()
    val chrome = readerChapterSheetChrome()
    val coverBitmap = remember(book) {
        if (chrome.cacheCoverOutsideLazyList) book.decodeCoverImageBitmap() else null
    }
    val scrollState = rememberScrollState()
    var showJumpDialog by remember { mutableStateOf(false) }

    ReaderBottomPanel(
        sheetStyle = sheetStyle,
        onDismiss = onDismiss,
    ) {
        CompositionLocalProvider(
            LocalOverscrollFactory provides if (chrome.disableListOverscrollEffect) null else LocalOverscrollFactory.current,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            ) {
                if (chrome.showBookHeader) {
                    ReaderChapterBookHeader(
                        book = book,
                        coverBitmap = coverBitmap,
                        currentPosition = currentPosition,
                        progressDisplay = progressDisplay,
                        onJumpToCharacter = { showJumpDialog = true },
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                rows.forEach { row ->
                    ReaderChapterListRow(
                        row = row,
                        progressDisplay = progressDisplay,
                        onClick = {
                            onJump(
                                ReaderChapterPosition(index = row.spineIndex, progress = 0.0),
                                row.fragment,
                            )
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f))
                }
            }
        }
    }

    if (showJumpDialog) {
        JumpToCharacterDialog(
            totalCharacters = book.bookInfo.characterCount,
            progressDisplay = progressDisplay,
            onDismiss = { showJumpDialog = false },
            onConfirm = { count ->
                showJumpDialog = false
                onJump(book.chapterPositionForCharacter(count), null)
            },
        )
    }
}

@Composable
internal fun ReaderChapterBookHeader(
    book: EpubBook,
    coverBitmap: ImageBitmap?,
    currentPosition: ReaderChapterPosition,
    progressDisplay: ReaderProgressDisplay,
    onJumpToCharacter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentCharacter = book.characterCountAt(currentPosition.index, currentPosition.progress)
    val totalCharacters = book.bookInfo.characterCount
    val percent = if (totalCharacters > 0) currentCharacter.toDouble() / totalCharacters.toDouble() * 100.0 else 0.0
    val metrics = readerSheetDensityMetrics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onJumpToCharacter)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ReaderChapterCover(
            coverBitmap = coverBitmap,
            modifier = Modifier.size(
                width = metrics.chapterHeaderCoverWidthDp.dp,
                height = metrics.chapterHeaderCoverHeightDp.dp,
            ),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                text = "${progressDisplay.rangeText(currentCharacter, totalCharacters)} (${String.format(Locale.US, "%.1f", percent)}%)",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
            contentDescription = stringResource(R.string.reader_jump_to_character),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(26.dp),
        )
    }
}

@Composable
internal fun ReaderGoToBookHeader(
    book: EpubBook,
    coverBitmap: ImageBitmap?,
    currentPosition: ReaderChapterPosition,
    progressDisplay: ReaderProgressDisplay,
    onJumpToCharacter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentCharacter = book.characterCountAt(currentPosition.index, currentPosition.progress)
    val totalCharacters = book.bookInfo.characterCount
    val percent = if (totalCharacters > 0) currentCharacter.toDouble() / totalCharacters.toDouble() * 100.0 else 0.0
    val metrics = readerSheetDensityMetrics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ReaderChapterCover(
            coverBitmap = coverBitmap,
            modifier = Modifier.size(
                width = metrics.chapterHeaderCoverWidthDp.dp,
                height = metrics.chapterHeaderCoverHeightDp.dp,
            ),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                text = "${progressDisplay.rangeText(currentCharacter, totalCharacters)} (${String.format(Locale.US, "%.1f", percent)}%)",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        TextButton(onClick = onJumpToCharacter) {
            Text(stringResource(R.string.reader_jump))
        }
    }
}

@Composable
internal fun ReaderChapterCover(
    coverBitmap: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    if (coverBitmap != null) {
        Image(
            bitmap = coverBitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(2.dp)),
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}

internal fun EpubBook.decodeCoverImageBitmap(): ImageBitmap? =
    coverHref
        ?.let(::readResource)
        ?.let { bytes -> runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() }
        ?.asImageBitmap()

@Composable
internal fun ReaderChapterListRow(
    row: ReaderChapterRow,
    progressDisplay: ReaderProgressDisplay,
    onClick: () -> Unit,
) {
    val eInkMode = LocalHoshiEInkMode.current
    val isCurrentEInkRow = eInkMode && row.isCurrent
    val currentRowColor = when {
        isCurrentEInkRow -> MaterialTheme.colorScheme.onSurface
        row.isCurrent -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
        else -> Color.Transparent
    }
    val rowContentColor = if (isCurrentEInkRow) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val rowMetaColor = if (isCurrentEInkRow) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val metrics = readerSheetDensityMetrics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = currentRowColor,
                shape = RoundedCornerShape(metrics.chapterRowCornerRadiusDp.dp),
            )
            .clickable(onClick = onClick)
            .padding(
                start = (row.indentLevel * 18).dp,
                top = metrics.chapterRowVerticalPaddingDp.dp,
                end = 8.dp,
                bottom = metrics.chapterRowVerticalPaddingDp.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = row.label.ifBlank { stringResource(R.string.reader_untitled_chapter) },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            color = rowContentColor,
        )
        row.characterCount?.let { count ->
            Text(
                text = progressDisplay.countText(count),
                color = rowMetaColor,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
internal fun JumpToCharacterDialog(
    totalCharacters: Int,
    progressDisplay: ReaderProgressDisplay,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val inputScrollState = rememberScrollState()
    val inputState = rememberSyncedTextFieldState(
        value = input,
        onValueChange = { input = it.filter(Char::isDigit) },
        scrollState = inputScrollState,
    )
    val parsed = input.filter(Char::isDigit).toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (progressDisplay.usesWords) {
                        R.string.reader_jump_to_word
                    } else {
                        R.string.reader_jump_to_character
                    },
                ),
            )
        },
        text = {
            OutlinedTextField(
                state = inputState,
                label = {
                    Text(
                        stringResource(
                            if (progressDisplay.usesWords) {
                                R.string.reader_word
                            } else {
                                R.string.reader_character
                            },
                        ),
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                lineLimits = hoshiSingleLineTextFieldLineLimits(),
                scrollState = inputScrollState,
                colors = hoshiOutlinedTextFieldColors(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null,
                onClick = {
                    onConfirm(
                        progressDisplay.rawTargetFromDisplayCount(
                            displayCount = parsed ?: 0,
                            totalCharacters = totalCharacters,
                        ),
                    )
                },
            ) {
                Text(stringResource(R.string.reader_jump))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

internal data class ReaderChapterRow(
    val label: String,
    val spineIndex: Int,
    val fragment: String?,
    val characterCount: Int?,
    val isCurrent: Boolean,
    val indentLevel: Int,
)

internal fun EpubBook.chapterRows(currentIndex: Int): List<ReaderChapterRow> {
    val tocRows = toc.flatMap { item ->
        flattenChapterRows(item, indentLevel = 0, currentIndex = currentIndex)
    }
    if (tocRows.isNotEmpty()) return tocRows
    return chapters.mapIndexed { index, chapter ->
        ReaderChapterRow(
            label = chapter.href.substringAfterLast('/').substringBeforeLast('.').ifBlank { title },
            spineIndex = index,
            fragment = null,
            characterCount = bookInfo.chapterInfo[chapter.href]?.currentTotal,
            isCurrent = index == currentIndex,
            indentLevel = 0,
        )
    }
}

private fun EpubBook.flattenChapterRows(
    item: EpubTocItem,
    indentLevel: Int,
    currentIndex: Int,
): List<ReaderChapterRow> {
    val row = item.href?.let { href ->
        val spineIndex = chapterIndexForHref(href) ?: return@let null
        ReaderChapterRow(
            label = item.label,
            spineIndex = spineIndex,
            fragment = href.substringAfter('#', "").ifBlank { null },
            characterCount = bookInfo.chapterInfo[chapters[spineIndex].href]?.currentTotal,
            isCurrent = spineIndex == currentIndex,
            indentLevel = indentLevel,
        )
    }
    return listOfNotNull(row) + item.children.flatMap { child ->
        flattenChapterRows(child, indentLevel = indentLevel + 1, currentIndex = currentIndex)
    }
}

private fun EpubBook.chapterIndexForHref(href: String): Int? {
    val tocPath = href.readerHrefBase()
    if (tocPath.isBlank()) return null
    return chapters.indexOfFirst { chapter ->
        val chapterPath = chapter.href.readerHrefBase()
        tocPath == chapterPath ||
            tocPath.endsWith("/$chapterPath") ||
            chapterPath.endsWith("/$tocPath")
    }.takeIf { it >= 0 }
}

internal fun EpubBook.chapterPositionForCharacter(characterCount: Int): ReaderChapterPosition {
    val targetCharacter = characterCount.coerceIn(0, bookInfo.characterCount)
    val chapterEntries = chapters.mapIndexedNotNull { index, chapter ->
        bookInfo.chapterInfo[chapter.href]?.let { info -> index to info }
    }
    val (index, info) = chapterEntries.lastOrNull { (_, info) -> info.currentTotal <= targetCharacter }
        ?: chapterEntries.firstOrNull()
        ?: return ReaderChapterPosition(index = 0)
    val progress = if (info.chapterCount <= 0) {
        0.0
    } else {
        (targetCharacter - info.currentTotal).toDouble() / info.chapterCount.toDouble()
    }
    return ReaderChapterPosition(index = index, progress = progress.coerceIn(0.0, 1.0))
}

private fun String.readerHrefBase(): String =
    trim()
        .replace('\\', '/')
        .removePrefix("/")
        .substringBefore('#')
        .substringBefore('?')
