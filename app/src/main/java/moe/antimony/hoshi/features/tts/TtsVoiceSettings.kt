package moe.antimony.hoshi.features.tts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.reader.ReaderSheetCard
import moe.antimony.hoshi.features.reader.ReaderSheetSliderRow
import moe.antimony.hoshi.features.reader.ReaderSheetSwitchRow
import moe.antimony.hoshi.ui.HoshiButton
import moe.antimony.hoshi.ui.theme.hoshiSurfaces

@Composable
internal fun TtsVoiceSettings(
    settings: TtsSettings, catalog: TtsCatalog?, engines: List<TtsEngineInfo>, busy: Boolean,
    preview: TtsPreviewState, failure: TtsFailure?, onSettings: (TtsSettings) -> Unit,
    onPreview: () -> Unit, onStopPreview: () -> Unit, onRetry: () -> Unit,
    onConfigure: (TtsOnlineSource) -> Unit, onSystemSettings: () -> Unit, modifier: Modifier = Modifier, onCustom: (String?) -> Unit = {},
    catalogLoading: Boolean = busy && preview == TtsPreviewState.Idle,
) {
    var sourcePicker by remember { mutableStateOf(false) }
    var voicePicker by remember { mutableStateOf<TtsTextRole?>(null) }
    val routing = settings.routing()
    val source = TtsOnlineSource.from(settings.engine)
    val custom = settings.customSource()
    val automatic = stringResource(R.string.tts_system_default)
    val choices = buildList {
        add(TtsSourceChoice("", automatic, TtsSourceGroup.Device))
        engines.forEach { add(TtsSourceChoice(it.id, it.label, TtsSourceGroup.Device)) }
        TtsOnlineSource.entries.forEach { add(TtsSourceChoice(it.id, stringResource(it.labelRes), if (it.needsKey) TtsSourceGroup.Cloud else TtsSourceGroup.Online)) }
        settings.customSources.forEach { add(TtsSourceChoice(it.id, it.name, TtsSourceGroup.Custom, it.protocol)) }
        if (settings.engine.isNotBlank() && none { it.id == settings.engine }) add(TtsSourceChoice(settings.engine, settings.engine, TtsSourceGroup.Other))
    }
    val sourceTitle = if (settings.engine.isBlank()) engines.firstOrNull { it.id == catalog?.selectedEngine }?.label ?: automatic
        else choices.first { it.id == settings.engine }.title
    val voices = catalog?.voices.orEmpty()
    val voice = if (settings.voice.isBlank()) voices.firstOrNull() else voices.firstOrNull { it.id == settings.voice }
    val voiceTitle = if (settings.voice.isBlank()) automatic else voice?.label ?: settings.voice
    val connection = when {
        voice?.network == true -> stringResource(R.string.tts_online)
        voice != null -> stringResource(R.string.tts_offline)
        else -> null
    }
    val dialogueVoice = if (routing.dialogue.isBlank()) voices.firstOrNull() else voices.firstOrNull { it.id == routing.dialogue }
    val dialogueConnection = dialogueVoice?.let { stringResource(if (it.network) R.string.tts_online else R.string.tts_offline) }
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
            ReaderSheetCard(verticalPadding = 4.dp, shape = RoundedCornerShape(16.dp)) {
                TtsCurrentVoiceRow(stringResource(R.string.tts_source), sourceTitle, null) { sourcePicker = true }
            }
            ReaderSheetSwitchRow(stringResource(R.string.tts_dialogue_routing), routing.enabled, onCheckedChange = {
                onSettings(settings.withRouting(routing.copy(enabled = it)).withCompatibleVoiceOptions(voices))
            })
            ReaderSheetCard(verticalPadding = 4.dp, shape = RoundedCornerShape(16.dp)) {
                TtsCurrentVoiceRow(stringResource(if (routing.enabled) R.string.tts_narration_voice else R.string.tts_voice), voiceTitle, connection) { voicePicker = TtsTextRole.Narration }
                if (routing.enabled) {
                    HorizontalDivider(color = hoshiSurfaces.divider)
                    TtsCurrentVoiceRow(stringResource(R.string.tts_dialogue_voice), if (routing.dialogue.isBlank()) automatic else dialogueVoice?.label ?: routing.dialogue,
                        dialogueConnection) { voicePicker = TtsTextRole.Dialogue }
                }
            }
            if (busy && preview == TtsPreviewState.Idle) TtsCatalogLoading(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
            failure?.let {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(stringResource(it.messageRes()), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (custom != null) TextButton(onClick = { onCustom(custom.id) }) { Text(stringResource(R.string.tts_configure_source)) }
                        if (source?.needsKey == true) TextButton(onClick = { onConfigure(source) }) { Text(stringResource(R.string.tts_configure_source)) }
                        TextButton(onClick = onRetry, enabled = !busy) { Text(stringResource(R.string.tts_retry)) }
                    }
                }
            }
            var rate by remember(settings.engine, settings.rate) { mutableFloatStateOf(settings.rate) }
            if (source == TtsOnlineSource.GoogleTranslate) ReaderSheetCard(verticalPadding = 4.dp) {
                TtsChoice(stringResource(R.string.tts_rate_label), stringResource(if (rate < 1f) R.string.tts_rate_slow else R.string.tts_rate_normal),
                    if (rate < 1f) "slow" else "normal",
                    listOf(TtsChoiceOption("normal", stringResource(R.string.tts_rate_normal)), TtsChoiceOption("slow", stringResource(R.string.tts_rate_slow))),
                ) { onSettings(settings.copy(rate = if (it == "slow") .5f else 1f)) }
            } else if (settings.supportsSharedRate(voices)) {
                val range = custom?.rateRange ?: (if (source == TtsOnlineSource.GoogleCloud) .25f else .5f)..(if (source == TtsOnlineSource.GoogleCloud || source?.usesAzureRest == true) 2f else 3f)
                ReaderSheetSliderRow(stringResource(R.string.tts_rate_label), stringResource(R.string.tts_rate_value, rate), rate, range,
                    steps = if (custom != null) ((range.endInclusive - range.start) * 20).toInt() - 1 else when (source) { TtsOnlineSource.GoogleCloud -> 34; TtsOnlineSource.Azure, TtsOnlineSource.MicrosoftTranslator -> 29; else -> 49 },
                    onValueChange = { rate = it }, onValueChangeFinished = { onSettings(settings.copy(rate = rate)) }, showTicks = false)
            }
            var playbackRate by remember(settings.playbackRate) { mutableFloatStateOf(settings.playbackRate) }
            ReaderSheetSliderRow(stringResource(R.string.tts_playback_rate), stringResource(R.string.tts_rate_value, playbackRate),
                playbackRate, ttsPlaybackRateRange, steps = 49, onValueChange = { playbackRate = it },
                onValueChangeFinished = { onSettings(settings.copy(playbackRate = playbackRate)) }, showTicks = false)
            settings.sharedPitchRange(voices)?.let { range ->
                var pitch by remember(settings.engine, settings.pitch) { mutableFloatStateOf(settings.pitch) }
                ReaderSheetSliderRow(stringResource(R.string.tts_pitch), pitchLabel(source, pitch.toInt()), pitch, range,
                    steps = (range.endInclusive - range.start).toInt() - 1, onValueChange = { pitch = it },
                    onValueChangeFinished = { onSettings(settings.copy(pitch = pitch)) }, showTicks = false)
            }
            TtsSynthesisControls(settings, settings.sharedVoice(voices), onSettings)
            source?.let {
                Text(stringResource(if (it.needsKey) R.string.tts_cloud_source_hint else R.string.tts_unofficial_hint),
                    style = MaterialTheme.typography.bodySmall, color = hoshiSurfaces.muted, modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
        HorizontalDivider(color = hoshiSurfaces.divider)
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
            HoshiButton(onClick = if (preview == TtsPreviewState.Idle) onPreview else onStopPreview,
                enabled = preview != TtsPreviewState.Idle || !busy,
                modifier = Modifier.fillMaxWidth().height(48.dp)) {
                if (preview == TtsPreviewState.Preparing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                else Icon(if (preview == TtsPreviewState.Playing) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.VolumeUp, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (preview == TtsPreviewState.Idle) R.string.tts_preview_voice else R.string.tts_stop_preview))
            }
        }
    }
    if (sourcePicker) TtsProviderPicker(choices, settings.engine, onSelected = {
        sourcePicker = false
        val next = settings.selectSource(it)
        onSettings(next.withOptions(next.options().copy(style = "", role = "")))
    }, onConfigure = { sourcePicker = false; onConfigure(it) }, onSystemSettings = { sourcePicker = false; onSystemSettings() }, onDismiss = { sourcePicker = false }, onCustom = { sourcePicker = false; onCustom(it) })
    voicePicker?.let { role -> TtsVoicePicker(sourceTitle, voices, if (role == TtsTextRole.Narration) settings.voice else routing.dialogue, catalogLoading, failure,
        onSelected = {
            voicePicker = null
            val next = if (role == TtsTextRole.Dialogue) settings.withRouting(routing.copy(dialogue = it))
                else settings.withRouting(routing.copy(narration = it))
            onSettings(next.withCompatibleVoiceOptions(voices))
        }, onRefresh = onRetry, onDismiss = { voicePicker = null }) }
}

@Composable
private fun TtsCurrentVoiceRow(label: String, value: String, status: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (status == null) label else stringResource(R.string.tts_voice_status, label, status),
                style = MaterialTheme.typography.bodyMedium, color = hoshiSurfaces.muted)
            Text(value, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = hoshiSurfaces.muted)
    }
}

@Composable
private fun pitchLabel(source: TtsOnlineSource?, value: Int): String = stringResource(when {
    source == TtsOnlineSource.Edge && value == 0 -> R.string.tts_pitch_zero_hz
    source == TtsOnlineSource.Edge -> R.string.tts_pitch_hz
    value == 0 -> R.string.tts_pitch_zero
    else -> R.string.tts_pitch_short
}, value)
