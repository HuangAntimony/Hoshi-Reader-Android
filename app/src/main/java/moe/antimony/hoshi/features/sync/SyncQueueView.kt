package moe.antimony.hoshi.features.sync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlin.math.ceil
import moe.antimony.hoshi.LocalHoshiUiDependencies
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.bookshelf.BookCoverCard
import moe.antimony.hoshi.features.bookshelf.BookshelfCoverMode
import moe.antimony.hoshi.features.settings.StaticSettingsDetailScaffold
import moe.antimony.hoshi.ui.ErrorDetailsText
import moe.antimony.hoshi.ui.HoshiAlertDialog
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.asString
import moe.antimony.hoshi.ui.theme.LocalHoshiEInkMode

@Composable
internal fun SyncQueueView(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val manager = LocalHoshiUiDependencies.current.googleDriveSyncManager
    val state by manager.state.collectAsStateWithLifecycle()
    SyncQueueScreen(state, onClose, modifier)
}

@Composable
internal fun SyncQueueScreen(state: GoogleDriveSyncState, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var errorDetails by remember { mutableStateOf<UiText?>(null) }
    StaticSettingsDetailScaffold(
        title = stringResource(R.string.sync_queue),
        onClose = onClose,
        modifier = modifier,
    ) { padding ->
        SyncQueueContent(state, { errorDetails = it }, Modifier.fillMaxSize().padding(padding))
    }
    errorDetails?.let { error ->
        HoshiAlertDialog(
            onDismissRequest = { errorDetails = null },
            title = { Text(stringResource(R.string.dialog_error_title)) },
            text = { ErrorDetailsText(error.asString()) },
            confirmButton = {
                TextButton(onClick = { errorDetails = null }) { Text(stringResource(R.string.action_ok)) }
            },
        )
    }
}

private enum class QueueSection(val title: Int) {
    Syncing(R.string.sync_queue_syncing_section),
    Waiting(R.string.sync_queue_waiting_section),
    Failed(R.string.sync_queue_failed_section),
}

private fun queueBookKey(item: SyncQueueItem) = "book:${item.key}"

@Composable
internal fun SyncQueueContent(state: GoogleDriveSyncState, onError: (UiText) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    val syncing = state.syncingBookKeys + state.activeTransfers.keys
    val groups = remember(state.queue, syncing) {
        state.queue.groupBy { item ->
            when {
                item.key in syncing -> QueueSection.Syncing
                item.error != null -> QueueSection.Failed
                else -> QueueSection.Waiting
            }
        }
    }
    val queueKeys = remember(groups) {
        QueueSection.entries.flatMap { section ->
            groups[section]?.let { rows -> listOf("section:$section") + rows.map(::queueBookKey) }.orEmpty()
        }
    }
    var previousKeys by remember { mutableStateOf<List<String>?>(null) }
    val atTop by remember { derivedStateOf { !listState.canScrollBackward } }
    // Capture before LazyColumn anchors the previous first-visible key at its new index.
    val followHead = (previousKeys.isNullOrEmpty() || atTop) && !listState.isScrollInProgress
    SideEffect {
        if (previousKeys != queueKeys) {
            if (previousKeys != null && followHead) listState.requestScrollToItem(0)
            previousKeys = queueKeys
        }
    }
    val emptyMessage = if (state.queue.isEmpty()) {
        when {
            state.isCheckingForChanges -> R.string.sync_queue_checking
            state.errorMessage != null -> R.string.sync_queue_interrupted
            state.isSyncing -> R.string.sync_queue_syncing_books
            state.lastSync == null -> R.string.sync_queue_not_checked
            else -> R.string.sync_queue_all_synced
        }
    } else null
    val loading = emptyMessage == R.string.sync_queue_checking || emptyMessage == R.string.sync_queue_syncing_books
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!loading || state.transferSummaries.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SyncTransferSummaryView(SyncTransferDirection.Upload, state.transferSummaries[SyncTransferDirection.Upload], Modifier.weight(1f))
                SyncTransferSummaryView(SyncTransferDirection.Download, state.transferSummaries[SyncTransferDirection.Download], Modifier.weight(1f))
            }
        }
        if (emptyMessage != null) {
            if (loading) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SyncBusyIndicator()
                        Text(stringResource(emptyMessage), color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
            } else {
                Text(stringResource(emptyMessage), color = colors.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (section in QueueSection.entries) {
                    val rows = groups[section] ?: continue
                    item(key = "section:$section") {
                        Text(stringResource(section.title), Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant)
                    }
                    items(rows, key = ::queueBookKey) { item ->
                        SyncQueueRow(item, state.activeTransfers[item.key], item.key in syncing, onError)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SyncBusyIndicator() {
    val modifier = Modifier.size(18.dp)
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    if (LocalHoshiEInkMode.current) {
        Icon(Icons.Rounded.HourglassTop, contentDescription = null, modifier = modifier, tint = color)
    } else {
        CircularProgressIndicator(modifier, color = color, strokeWidth = 2.dp)
    }
}

@Composable
internal fun SyncTransferSummaryView(
    direction: SyncTransferDirection,
    summary: SyncTransferSummary?,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val label = if (direction == SyncTransferDirection.Upload) R.string.sync_upload_speed else R.string.sync_download_speed
        Text(stringResource(label, transferRate(summary?.bytesPerSecond ?: 0.0)), style = MaterialTheme.typography.bodyMedium)
        if (summary != null) {
            val color = MaterialTheme.colorScheme.onSurfaceVariant
            summary.fraction?.let { fraction ->
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            }
            val done = transferSize(summary.doneBytes)
            val amount = summary.totalBytes?.let {
                stringResource(R.string.sync_transfer_amount, done, transferSize(it))
            } ?: stringResource(R.string.sync_transfer_amount_unknown, done)
            Text(amount, style = MaterialTheme.typography.bodySmall, color = color)
            summary.remainingSeconds?.let { seconds ->
                val remaining = if (seconds < 60) stringResource(R.string.sync_transfer_remaining_under_minute)
                else stringResource(R.string.sync_transfer_remaining_minutes, ceil(seconds / 60).toLong())
                Text(remaining, style = MaterialTheme.typography.bodySmall, color = color)
            }
        }
    }
}

@Composable
private fun SyncQueueRow(item: SyncQueueItem, active: SyncFileTransfer?, syncing: Boolean, onError: (UiText) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BookCoverCard(
            title = item.title,
            author = null,
            coverSource = item.coverSource,
            coverMode = BookshelfCoverMode.Show,
            modifier = Modifier.width(34.dp).clearAndSetSemantics {},
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                (active?.direction ?: item.interruptedTransfer?.direction ?: item.direction)?.let { direction ->
                    Icon(when (direction) {
                        SyncTransferDirection.Upload -> Icons.Rounded.ArrowUpward
                        SyncTransferDirection.Download -> Icons.Rounded.ArrowDownward
                        SyncTransferDirection.Both -> Icons.Rounded.SwapVert
                    }, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val transfer = active ?: item.interruptedTransfer
            if (transfer != null) {
                SyncFileProgress(transfer, interrupted = active == null)
            } else if (syncing) {
                Text(stringResource(R.string.sync_transfer_preparing), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            } else {
                item.pendingBytes?.let {
                    Text(transferSize(it), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            item.error?.let { error ->
                TextButton(onClick = { onError(error) }) { Text(stringResource(R.string.sync_error_details), color = colors.error) }
            }
        }
    }
}

@Composable
private fun SyncFileProgress(transfer: SyncFileTransfer, interrupted: Boolean) {
    val colors = MaterialTheme.colorScheme
    LinearProgressIndicator(
        progress = { transfer.fraction ?: 0f },
        modifier = Modifier.fillMaxWidth(),
        color = if (interrupted) colors.onSurfaceVariant else colors.primary,
    )
    val type = stringResource(when (transfer.type) {
        SyncFileType.epub -> R.string.sync_file_epub
        SyncFileType.cover -> R.string.sync_file_cover
        SyncFileType.sasayaki -> R.string.sync_file_sasayaki
    })
    val stage = stringResource(if (interrupted) R.string.sync_transfer_interrupted else when (transfer.phase) {
        SyncTransferPhase.Preparing -> R.string.sync_transfer_preparing
        SyncTransferPhase.Transferring -> if (transfer.direction == SyncTransferDirection.Upload) R.string.sync_transfer_uploading else R.string.sync_transfer_downloading
        SyncTransferPhase.AwaitingResponse -> R.string.sync_transfer_confirming
        SyncTransferPhase.Retrying -> R.string.sync_transfer_retrying
    })
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.sync_transfer_stage, type, stage), Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        if (!interrupted) Text(transferRate(transfer.bytesPerSecond), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
    val amount = transfer.totalBytes?.let { stringResource(R.string.sync_transfer_amount, transferSize(transfer.bytes), transferSize(it)) }
        ?: transferSize(transfer.bytes)
    val detail = transfer.fraction?.let { stringResource(R.string.sync_transfer_percent, (it * 100).toInt(), amount) } ?: amount
    Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
}

internal fun transferRate(bytes: Double): String = "${transferSize(bytes.coerceAtLeast(0.0).toLong())}/s"
internal fun transferSize(bytes: Long): String {
    val size = bytes.coerceAtLeast(0)
    return when {
        size < 1024 -> "$size B"
        size < 1024L * 1024 -> String.format(Locale.getDefault(), "%.1f KiB", size / 1024.0)
        size < 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MiB", size / (1024.0 * 1024))
        else -> String.format(Locale.getDefault(), "%.1f GiB", size / (1024.0 * 1024 * 1024))
    }
}
