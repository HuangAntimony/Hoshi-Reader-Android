package moe.antimony.hoshi.features.sasayaki

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import moe.antimony.hoshi.ui.HoshiAlertDialog as AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToLong
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.HoshiButton
import moe.antimony.hoshi.ui.asString
import moe.antimony.hoshi.ui.theme.hoshiContainerOutline
import moe.antimony.hoshi.ui.theme.hoshiSurfaces

@Composable
internal fun SasayakiMatchModeControl(
    selected: SasayakiMatchMode,
    enabled: Boolean,
    onSelected: (SasayakiMatchMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .selectableGroup()
            .background(hoshiSurfaces.nested, RoundedCornerShape(12.dp))
            .hoshiContainerOutline(RoundedCornerShape(12.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        SasayakiMatchMode.entries.forEach { mode ->
            val isSelected = selected == mode
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(if (isSelected) hoshiSurfaces.selected else Color.Transparent, RoundedCornerShape(10.dp))
                    .then(if (isSelected) Modifier.hoshiContainerOutline(RoundedCornerShape(10.dp)) else Modifier)
                    .selectable(selected = isSelected, enabled = enabled, onClick = { onSelected(mode) })
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(mode.labelRes),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) hoshiSurfaces.onSelected else hoshiSurfaces.muted,
                )
            }
        }
    }
}

@Composable
internal fun SasayakiTranscriptionSection(
    state: SasayakiTranscriptionUiState,
    enabled: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onConfirmDownload: () -> Unit,
    onRequestClear: () -> Unit,
    onDismissClear: () -> Unit,
    onConfirmClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentTranscript = state.hasTranscript && state.transcriptMatchesSource
    val showAudioProgress = state.duration > 0 && (state.running || currentTranscript) &&
        state.stage != SasayakiTranscriptionStage.Downloading && state.stage != SasayakiTranscriptionStage.AwaitingDownload
    SasayakiResourceCard {
        Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    transcriptionStatusLabel(state),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                state.remainingSeconds?.takeIf {
                    state.stage == SasayakiTranscriptionStage.Transcribing && it.isFinite() && it >= 0
                }?.let { seconds ->
                    Text(
                        stringResource(R.string.sasayaki_transcription_remaining, ceil(seconds / 60).toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.running || showAudioProgress) {
                val progress = when (state.stage) {
                    SasayakiTranscriptionStage.Downloading -> state.download.toFloat().coerceIn(0f, 1f)
                    SasayakiTranscriptionStage.Transcribing, SasayakiTranscriptionStage.Idle ->
                        if (showAudioProgress) (state.through / state.duration).toFloat().coerceIn(0f, 1f) else null
                    else -> null
                }
                if (state.stage != SasayakiTranscriptionStage.AwaitingDownload) {
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                if (showAudioProgress) {
                    Text(
                        stringResource(
                            R.string.sasayaki_transcription_saved_progress,
                            formatSasayakiTranscriptionTime(state.through),
                            formatSasayakiTranscriptionTime(state.duration),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.error?.let { error ->
                Text(error.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.running) {
                    HoshiButton(onClick = onPause, enabled = state.canPause, modifier = Modifier.weight(1f)) {
                        Text(stringResource(
                            if (state.stage == SasayakiTranscriptionStage.AwaitingDownload) R.string.action_cancel
                            else R.string.sasayaki_transcription_pause,
                        ))
                    }
                } else {
                    HoshiButton(onClick = onStart, enabled = enabled && state.canStart, modifier = Modifier.weight(1f)) {
                        Text(stringResource(state.actionLabelRes))
                    }
                    if (state.hasTranscript) {
                        TextButton(onClick = onRequestClear, enabled = enabled && !state.controlsLocked) {
                            Text(stringResource(R.string.sasayaki_transcription_clear))
                        }
                    }
                }
            }
        }
    }
    if (state.stage == SasayakiTranscriptionStage.AwaitingDownload) {
        AlertDialog(
            onDismissRequest = onPause,
            title = { Text(stringResource(R.string.sasayaki_transcription_download_title)) },
            text = { Text(stringResource(
                R.string.sasayaki_transcription_download_message,
                ceil(state.downloadBytes / (1024.0 * 1024.0)).toLong(),
            )) },
            confirmButton = {
                TextButton(onClick = onConfirmDownload) { Text(stringResource(R.string.sasayaki_transcription_download_action)) }
            },
            dismissButton = {
                TextButton(onClick = onPause) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    if (state.showClearConfirmation) {
        AlertDialog(
            onDismissRequest = onDismissClear,
            title = { Text(stringResource(R.string.sasayaki_transcription_clear_confirmation)) },
            text = { Text(stringResource(R.string.sasayaki_transcription_clear_message)) },
            confirmButton = {
                TextButton(onClick = onConfirmClear, enabled = !state.controlsLocked) {
                    Text(stringResource(R.string.action_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissClear) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun transcriptionStatusLabel(state: SasayakiTranscriptionUiState): String = when (state.stage) {
    SasayakiTranscriptionStage.AwaitingDownload -> stringResource(R.string.sasayaki_transcription_awaiting_download)
    SasayakiTranscriptionStage.Downloading ->
        stringResource(R.string.sasayaki_transcription_downloading, (state.download * 100).toInt().coerceIn(0, 100))
    SasayakiTranscriptionStage.Transcribing -> stringResource(R.string.sasayaki_transcription_progress)
    SasayakiTranscriptionStage.Aligning -> stringResource(R.string.sasayaki_transcription_aligning)
    SasayakiTranscriptionStage.Pausing -> stringResource(R.string.sasayaki_transcription_pausing)
    SasayakiTranscriptionStage.Preparing -> stringResource(R.string.sasayaki_transcription_preparing)
    SasayakiTranscriptionStage.Idle -> stringResource(when {
        state.busyElsewhere -> R.string.sasayaki_transcription_busy
        !state.hasSource -> R.string.sasayaki_transcription_select_audio
        state.hasTranscript && state.transcriptMatchesSource && state.transcriptComplete -> R.string.sasayaki_transcription_complete
        state.hasTranscript && state.transcriptMatchesSource && state.through > 0 -> R.string.sasayaki_transcription_paused
        else -> R.string.sasayaki_transcription_description
    })
}

internal fun formatSasayakiTranscriptionTime(seconds: Double): String {
    val total = if (seconds.isFinite()) seconds.coerceAtLeast(0.0).roundToLong() else 0L
    return String.format(Locale.US, "%d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
}
