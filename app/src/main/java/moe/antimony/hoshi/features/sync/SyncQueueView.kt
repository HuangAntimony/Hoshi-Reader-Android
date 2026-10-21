package moe.antimony.hoshi.features.sync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.theme.hoshiContainerOutline
import moe.antimony.hoshi.ui.theme.hoshiSurfaces

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SyncQueueSheet(state: GoogleDriveSyncState, onDismiss: () -> Unit, onError: (UiText) -> Unit) {
    ModalBottomSheet(
        modifier = Modifier.hoshiContainerOutline(BottomSheetDefaults.ExpandedShape),
        containerColor = hoshiSurfaces.overlay,
        tonalElevation = 0.dp,
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) { SyncQueueContent(state, onError) }
}

@Composable
internal fun SyncQueueContent(state: GoogleDriveSyncState, onError: (UiText) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    val queueKeys = remember(state.queue) { state.queue.map { it.key } }
    var previousKeys by remember { mutableStateOf<List<String>?>(null) }
    val atTop by remember { derivedStateOf { !listState.canScrollBackward } }
    // Capture before LazyColumn can anchor the old first-visible key at its new index.
    // Follow the queue head only while already at the top; preserve deliberate scrolling.
    val followHead = (previousKeys.isNullOrEmpty() || atTop) && !listState.isScrollInProgress
    SideEffect {
        if (previousKeys != queueKeys) {
            if (previousKeys != null && followHead) listState.requestScrollToItem(0)
            previousKeys = queueKeys
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.sync_queue), style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.sync_upload_speed, transferRate(state.activeTransfers.values.filter { it.direction == SyncTransferDirection.Upload }.sumOf { it.bytesPerSecond })), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.sync_download_speed, transferRate(state.activeTransfers.values.filter { it.direction == SyncTransferDirection.Download }.sumOf { it.bytesPerSecond })), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }
        if (state.queue.isEmpty()) {
            Text(stringResource(R.string.sync_queue_all_synced), color = colors.onSurfaceVariant)
        } else {
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(state.queue, key = { it.key }) { item ->
                    val transfer = state.activeTransfers[item.key]
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (transfer?.direction ?: item.direction)?.let { direction ->
                            Icon(when (direction) {
                                SyncTransferDirection.Upload -> Icons.Rounded.ArrowUpward
                                SyncTransferDirection.Download -> Icons.Rounded.ArrowDownward
                                SyncTransferDirection.Both -> Icons.Rounded.SwapVert
                            }, null, tint = colors.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp).size(16.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            transfer?.let {
                                // Determinate even during preparation; no perpetual animation on E-ink.
                                LinearProgressIndicator(progress = { it.fraction ?: 0f }, modifier = Modifier.fillMaxWidth())
                                val type = stringResource(when (it.type) {
                                    SyncFileType.epub -> R.string.sync_file_epub
                                    SyncFileType.cover -> R.string.sync_file_cover
                                    SyncFileType.sasayaki -> R.string.sync_file_sasayaki
                                })
                                val stage = stringResource(when (it.phase) {
                                    SyncTransferPhase.Preparing -> R.string.sync_transfer_preparing
                                    SyncTransferPhase.Transferring -> if (it.direction == SyncTransferDirection.Upload) R.string.sync_transfer_uploading else R.string.sync_transfer_downloading
                                    SyncTransferPhase.AwaitingResponse -> R.string.sync_transfer_confirming
                                    SyncTransferPhase.Retrying -> R.string.sync_transfer_retrying
                                })
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(stringResource(R.string.sync_transfer_stage, type, stage), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                    Text(transferRate(it.bytesPerSecond), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                }
                                val amount = it.totalBytes?.let { total -> stringResource(R.string.sync_transfer_amount, transferSize(it.bytes), transferSize(total)) } ?: transferSize(it.bytes)
                                val detail = it.fraction?.let { fraction -> stringResource(R.string.sync_transfer_percent, (fraction * 100).toInt(), amount) } ?: amount
                                Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                            item.error?.let { error ->
                                TextButton(onClick = { onError(error) }) { Text(stringResource(R.string.sync_error_details), color = colors.error) }
                            }
                        }
                    }
                }
            }
        }
    }
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
