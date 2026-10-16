package moe.antimony.hoshi.features.tts

import moe.antimony.hoshi.ui.asString

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.HoshiAlertDialog
import moe.antimony.hoshi.ui.HoshiButton
import moe.antimony.hoshi.ui.HoshiDropdownMenu
import moe.antimony.hoshi.ui.hoshiOutlinedTextFieldColors
import moe.antimony.hoshi.ui.theme.hoshiSurfaces
import moe.antimony.hoshi.ui.theme.LocalHoshiEInkMode

@Composable
internal fun TtsCustomSourceEditor(
    initial: TtsCustomSource, editing: Boolean,
    onTest: suspend (TtsCustomSource, String, Boolean) -> TtsError?,
    onSave: suspend (TtsCustomSource, String, Boolean) -> TtsError?,
    onDelete: suspend () -> TtsError?,
    onModels: suspend (TtsCustomSource, String, Boolean) -> List<String>,
    onDismiss: () -> Unit,
    selectedVoice: String = "",
) {
    var source by remember(initial.id) { mutableStateOf(initial) }
    var voiceIds by remember(initial.id) { mutableStateOf(initial.voiceIds.joinToString("\n")) }
    var key by remember(initial.id) { mutableStateOf("") }
    var removeKey by remember(initial.id) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<TtsError?>(null) }
    var verified by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelsLoaded by remember { mutableStateOf(false) }
    var modelsExpanded by remember { mutableStateOf(false) }
    var modelsLoading by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val update: (TtsCustomSource) -> Unit = { source = it; verified = false; failure = null }
    TtsSelectionSheet(stringResource(if (editing) R.string.tts_edit_service else R.string.tts_add_service), onDismiss) {
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TtsServiceField(R.string.tts_service_name, source.name, !busy) { update(source.copy(name = it)) }
            TtsChoice(stringResource(R.string.tts_service_protocol), stringResource(source.protocol.labelRes), source.protocol.name,
                TtsCustomProtocol.entries.map { TtsChoiceOption(it.name, stringResource(it.labelRes)) }, enabled = !busy) {
                if (!busy) {
                    val protocol = TtsCustomProtocol.valueOf(it)
                    if (protocol != source.protocol) {
                        update(source.copy(protocol = protocol, model = "", rate = 1f, preset = "", encryptedKey = "", voiceIds = emptyList()))
                        key = ""; removeKey = false; models = emptyList(); modelsLoaded = false; voiceIds = ""
                    }
                }
            }
            TtsServiceField(R.string.tts_service_url, source.baseUrl, !busy, KeyboardType.Uri) { update(source.copy(baseUrl = it)); modelsLoaded = false; models = emptyList() }
            Text(stringResource(if (source.protocol == TtsCustomProtocol.OpenAi) R.string.tts_service_url_openai else R.string.tts_service_url_vits),
                style = MaterialTheme.typography.bodySmall, color = hoshiSurfaces.muted)
            OutlinedTextField(key, { key = it; removeKey = false; verified = false; failure = null }, enabled = !busy,
                modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.tts_api_key)) },
                colors = hoshiOutlinedTextFieldColors(), visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Text(stringResource(R.string.tts_service_key_hint), style = MaterialTheme.typography.bodySmall, color = hoshiSurfaces.muted)
            if (source.encryptedKey.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(removeKey, { removeKey = it; key = ""; verified = false }, enabled = !busy)
                Text(stringResource(R.string.tts_service_clear_key), style = MaterialTheme.typography.bodyMedium)
            }
            if (source.protocol == TtsCustomProtocol.OpenAi) {
                OutlinedTextField(source.model, { update(source.copy(model = it)) }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.tts_service_model)) }, colors = hoshiOutlinedTextFieldColors(),
                    trailingIcon = {
                        Box {
                            IconButton(enabled = !busy, onClick = { scope.launch {
                                busy = true; modelsLoading = true; modelsLoaded = false; failure = null
                                try {
                                    models = onModels(source, key, removeKey).map { it.trim() }.filter { it.isNotBlank() }.distinct()
                                    modelsLoaded = true
                                    modelsExpanded = models.isNotEmpty()
                                } catch (error: CancellationException) { throw error }
                                catch (error: Exception) { models = emptyList(); modelsLoaded = false; modelsExpanded = false; failure = error.toTtsError(TtsFailure.Network) }
                                finally { busy = false; modelsLoading = false }
                            } }) {
                                if (modelsLoading && !LocalHoshiEInkMode.current)
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                else Icon(if (modelsLoading) Icons.Rounded.HourglassTop else Icons.Rounded.ExpandMore, null)
                            }
                            HoshiDropdownMenu(modelsExpanded, onDismissRequest = { modelsExpanded = false }, modifier = Modifier.widthIn(max = 320.dp)) {
                                models.forEach { model ->
                                    DropdownMenuItem(text = { Text(model) }, onClick = {
                                        modelsExpanded = false; update(source.copy(model = model))
                                    }, trailingIcon = { if (model == source.model) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) })
                                }
                            }
                        }
                    })
                if (modelsLoaded && models.isEmpty()) Text(stringResource(R.string.tts_service_no_models), style = MaterialTheme.typography.bodySmall, color = hoshiSurfaces.muted)
                OutlinedTextField(voiceIds, {
                    voiceIds = it
                    update(source.copy(voiceIds = it.lineSequence().map { id -> id.trim() }.filter { id -> id.isNotEmpty() }.distinct().toList()))
                }, enabled = !busy, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6,
                    label = { Text(stringResource(R.string.tts_service_voice_ids)) }, colors = hoshiOutlinedTextFieldColors())
                Text(stringResource(R.string.tts_service_voice_ids_hint), style = MaterialTheme.typography.bodySmall, color = hoshiSurfaces.muted)
            } else if (vitsSpeaker(selectedVoice.takeIf { source.protocol == initial.protocol }.orEmpty())?.first == "GPT-SOVITS") {
                TtsServiceField(R.string.tts_service_preset, source.preset, !busy) { update(source.copy(preset = it)) }
                Text(stringResource(R.string.tts_service_preset_hint), style = MaterialTheme.typography.bodySmall, color = hoshiSurfaces.muted)
            }
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TtsConnectionTestControls(!busy, testing, verified, failure, onTest = { scope.launch {
                busy = true; testing = true; failure = null; verified = false
                try { failure = onTest(source, key, removeKey); verified = failure == null }
                finally { busy = false; testing = false }
            } })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (editing) TextButton(enabled = !busy, onClick = { confirmDelete = true }, modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.action_delete)) }
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(48.dp)) { Text(stringResource(R.string.action_cancel)) }
                HoshiButton(enabled = !busy, modifier = Modifier.weight(1f).height(48.dp), onClick = { scope.launch {
                    busy = true; failure = null
                    try { failure = onSave(source, key, removeKey); if (failure == null) { key = ""; onDismiss() } } finally { busy = false }
                } }) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
    if (confirmDelete) HoshiAlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.tts_service_delete_title)) },
        text = { Text(stringResource(R.string.tts_service_delete_message, source.name)) },
        confirmButton = { TextButton(onClick = { scope.launch {
            confirmDelete = false; busy = true
            try { failure = onDelete(); if (failure == null) onDismiss() } finally { busy = false }
        } }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable
private fun TtsServiceField(label: Int, value: String, enabled: Boolean, type: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(), enabled = enabled, singleLine = true,
        label = { Text(stringResource(label)) }, colors = hoshiOutlinedTextFieldColors(), keyboardOptions = KeyboardOptions(keyboardType = type))
}
