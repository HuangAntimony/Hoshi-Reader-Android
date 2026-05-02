package moe.antimony.hoshi.features.reader

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubTocItem
import moe.antimony.hoshi.ui.theme.LocalHoshiEInkMode
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderChapterSheet(
    book: EpubBook,
    currentPosition: ReaderChapterPosition,
    onJump: (ReaderChapterPosition) -> Unit,
    onDismiss: () -> Unit,
) {
    val rows = remember(book, currentPosition.index) { book.chapterRows(currentPosition.index) }
    val numberFormat = remember { NumberFormat.getIntegerInstance(Locale.US) }
    val sheetStyle = readerSheetStyle()
    val eInkMode = sheetStyle.eInkMode
    var showJumpDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = sheetStyle.containerColor,
        contentColor = sheetStyle.contentColor,
        scrimColor = sheetStyle.scrimColor,
        dragHandle = { ReaderSheetDragHandle(sheetStyle) },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 20.dp),
                ) {
                    Text(
                        text = "Chapters",
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(top = 10.dp),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Surface(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        shape = CircleShape,
                        color = if (eInkMode) {
                            MaterialTheme.colorScheme.surface
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                        },
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        border = if (eInkMode) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Close chapters",
                                modifier = Modifier.size(34.dp),
                            )
                        }
                    }
                }
            }
            item {
                ReaderChapterBookHeader(
                    book = book,
                    currentPosition = currentPosition,
                    numberFormat = numberFormat,
                    onJumpToCharacter = { showJumpDialog = true },
                    modifier = Modifier.padding(bottom = 22.dp),
                )
            }
            items(
                items = rows,
                key = { "${it.spineIndex}:${it.fragment.orEmpty()}:${it.label}:${it.indentLevel}" },
            ) { row ->
                ReaderChapterListRow(
                    row = row,
                    numberFormat = numberFormat,
                    onClick = { onJump(ReaderChapterPosition(index = row.spineIndex, progress = 0.0)) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f))
            }
        }
    }

    if (showJumpDialog) {
        JumpToCharacterDialog(
            totalCharacters = book.bookInfo.characterCount,
            onDismiss = { showJumpDialog = false },
            onConfirm = { count ->
                showJumpDialog = false
                onJump(book.chapterPositionForCharacter(count))
            },
        )
    }
}

@Composable
private fun ReaderChapterBookHeader(
    book: EpubBook,
    currentPosition: ReaderChapterPosition,
    numberFormat: NumberFormat,
    onJumpToCharacter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentCharacter = book.characterCountAt(currentPosition.index, currentPosition.progress)
    val totalCharacters = book.bookInfo.characterCount
    val percent = if (totalCharacters > 0) currentCharacter.toDouble() / totalCharacters.toDouble() * 100.0 else 0.0
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onJumpToCharacter)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ReaderChapterCover(
            book = book,
            modifier = Modifier.size(width = 58.dp, height = 86.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                text = "${numberFormat.format(currentCharacter)} / ${numberFormat.format(totalCharacters)} (${String.format(Locale.US, "%.1f", percent)}%)",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
            contentDescription = "Jump to character",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(34.dp),
        )
    }
}

@Composable
private fun ReaderChapterCover(
    book: EpubBook,
    modifier: Modifier = Modifier,
) {
    val coverBitmap = remember(book.coverHref) {
        book.coverHref
            ?.let(book::readResource)
            ?.let { bytes -> runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() }
    }
    if (coverBitmap != null) {
        Image(
            bitmap = coverBitmap.asImageBitmap(),
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

@Composable
private fun ReaderChapterListRow(
    row: ReaderChapterRow,
    numberFormat: NumberFormat,
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = currentRowColor,
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(start = (row.indentLevel * 22).dp, top = 18.dp, end = 8.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = row.label.ifBlank { "Untitled" },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            color = rowContentColor,
        )
        row.characterCount?.let { count ->
            Text(
                text = numberFormat.format(count),
                color = rowMetaColor,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun JumpToCharacterDialog(
    totalCharacters: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val parsed = input.filter(Char::isDigit).toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Jump to Character") },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.filter(Char::isDigit) },
                label = { Text("Character") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null,
                onClick = { onConfirm((parsed ?: 0).coerceIn(0, totalCharacters)) },
            ) {
                Text("Jump")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private data class ReaderChapterRow(
    val label: String,
    val spineIndex: Int,
    val fragment: String?,
    val characterCount: Int?,
    val isCurrent: Boolean,
    val indentLevel: Int,
)

private fun EpubBook.chapterRows(currentIndex: Int): List<ReaderChapterRow> {
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

private fun EpubBook.chapterPositionForCharacter(characterCount: Int): ReaderChapterPosition {
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
