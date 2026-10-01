package moe.antimony.hoshi.features.tts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.reader.ReaderBottomPanel
import moe.antimony.hoshi.features.reader.readerSheetStyle
import moe.antimony.hoshi.ui.theme.hoshiSurfaces

internal enum class TtsSourceGroup(val labelRes: Int) {
    Device(R.string.tts_group_device), Online(R.string.tts_group_online), Cloud(R.string.tts_group_cloud), Other(R.string.tts_group_other)
}
internal data class TtsSourceChoice(val id: String, val title: String, val group: TtsSourceGroup)

@Composable
internal fun TtsSelectionSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ReaderBottomPanel(readerSheetStyle(), onDismiss) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, null, tint = hoshiSurfaces.muted) }
            }
            content()
        }
    }
}

@Composable
internal fun TtsProviderPicker(
    choices: List<TtsSourceChoice>, selected: String, onSelected: (String) -> Unit,
    onConfigure: (TtsOnlineSource) -> Unit, onSystemSettings: () -> Unit, onDismiss: () -> Unit,
) {
    TtsSelectionSheet(stringResource(R.string.tts_choose_source), onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 20.dp)) {
            for (group in TtsSourceGroup.entries) {
                val entries = choices.filter { it.group == group }
                if (entries.isEmpty()) continue
                item(key = "group:$group") {
                    Text(stringResource(group.labelRes), style = MaterialTheme.typography.labelLarge,
                        color = hoshiSurfaces.muted, modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp))
                }
                items(entries, key = { it.id }) { choice ->
                    val source = TtsOnlineSource.from(choice.id)
                    val supporting = when {
                        choice.id.isBlank() -> R.string.tts_system_preferred
                        source?.needsKey == true -> R.string.tts_key_required
                        source != null -> R.string.tts_no_account
                        choice.group == TtsSourceGroup.Other -> R.string.tts_saved_source
                        else -> R.string.tts_installed_engine
                    }
                    Row(Modifier.fillMaxWidth().clickable { onSelected(choice.id) }.padding(horizontal = 20.dp, vertical = 8.dp)
                        .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(24.dp)) { if (choice.id == selected) Icon(Icons.Rounded.Check, null) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(choice.title, style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(supporting), style = MaterialTheme.typography.bodySmall, color = hoshiSurfaces.muted)
                        }
                        if (source?.needsKey == true) IconButton(onClick = { onConfigure(source) }) { Icon(Icons.Rounded.Settings, null, tint = hoshiSurfaces.muted) }
                    }
                }
            }
            if (TtsOnlineSource.from(selected) == null) item(key = "system-settings") {
                HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = hoshiSurfaces.divider)
                TextButton(onClick = onSystemSettings, modifier = Modifier.padding(horizontal = 12.dp).heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.Settings, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.tts_system_settings))
                }
            }
        }
    }
}

@Composable
internal fun TtsVoicePicker(
    title: String, voices: List<TtsVoiceInfo>, selected: String, busy: Boolean,
    failure: TtsFailure?, onSelected: (String) -> Unit, onRetry: () -> Unit, onDismiss: () -> Unit,
) {
    val choices = voices.withSelectedVoice(selected)
    TtsSelectionSheet(title, onDismiss) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp))
        failure?.let {
            Text(stringResource(it.messageRes()), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp))
            TextButton(onClick = onRetry, modifier = Modifier.padding(horizontal = 12.dp)) { Text(stringResource(R.string.tts_retry)) }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 20.dp)) {
            item(key = "automatic") {
                TtsVoiceRow(stringResource(R.string.tts_system_default), null, selected.isBlank()) { onSelected("") }
            }
            items(choices, key = { it.id }) { voice ->
                val connection = if (voices.any { it.id == voice.id })
                    stringResource(if (voice.network) R.string.tts_online else R.string.tts_offline) else null
                TtsVoiceRow(voice.id, connection, voice.id == selected) { onSelected(voice.id) }
            }
        }
    }
}

internal fun List<TtsVoiceInfo>.withSelectedVoice(selected: String): List<TtsVoiceInfo> =
    if (selected.isBlank() || any { it.id == selected }) this else listOf(TtsVoiceInfo(selected, false)) + this

@Composable
private fun TtsVoiceRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp)
        .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(24.dp)) { if (selected) Icon(Icons.Rounded.Check, null) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = hoshiSurfaces.muted) }
        }
    }
}
