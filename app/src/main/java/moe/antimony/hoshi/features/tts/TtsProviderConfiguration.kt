package moe.antimony.hoshi.features.tts

import moe.antimony.hoshi.ui.asString

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.HoshiAlertDialog
import moe.antimony.hoshi.ui.HoshiButton
import moe.antimony.hoshi.ui.hoshiOutlinedTextFieldColors
import moe.antimony.hoshi.ui.theme.hoshiSurfaces

@Composable
internal fun TtsSourceConfigurationSheet(
    source: TtsOnlineSource, config: TtsOnlineConfig, viewModel: TtsViewModel, onDismiss: () -> Unit,
) {
    val sample = stringResource(R.string.tts_sample)
    TtsProviderConfiguration(source, config,
        onTest = { region, key -> viewModel.testConfiguration(source, region, key, sample) },
        onSave = { region, key -> viewModel.configure(source, region, key) }, onDismiss = onDismiss,
        onDelete = { viewModel.removeKey(source) })
}

@Composable
internal fun TtsProviderConfiguration(
    source: TtsOnlineSource, config: TtsOnlineConfig,
    onTest: suspend (String, String) -> TtsError?, onSave: suspend (String, String) -> TtsError?, onDismiss: () -> Unit,
    onDelete: suspend () -> TtsError?,
) {
    var region by remember(source) { mutableStateOf(config.region) }
    // Plaintext credentials stay out of saveable state; saved keys are never decrypted into the field.
    var apiKey by remember(source) { mutableStateOf("") }
    var busy by remember(source) { mutableStateOf(false) }
    var testing by remember(source) { mutableStateOf(false) }
    var failure by remember(source) { mutableStateOf<TtsError?>(null) }
    var tested by remember(source) { mutableStateOf(false) }
    var confirmDelete by remember(source) { mutableStateOf(false) }
    val selectedRegion = TtsAzureRegion.from(region)
    val regionReady = source != TtsOnlineSource.Azure || selectedRegion != null
    val scope = rememberCoroutineScope()
    TtsSelectionSheet(stringResource(source.labelRes), onDismiss) {
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.tts_cloud_credentials_hint), style = MaterialTheme.typography.bodyMedium, color = hoshiSurfaces.muted)
            if (source == TtsOnlineSource.Azure) {
                val regions = TtsAzureRegion.entries.map {
                    TtsChoiceOption(it.id, it.id)
                }
                TtsChoice(stringResource(R.string.tts_azure_region),
                    regions.firstOrNull { it.id == region }?.label ?: stringResource(R.string.tts_azure_region_select),
                    region, regions, enabled = !busy, compact = true,
                ) { region = it; tested = false; failure = null }
                Text(stringResource(R.string.tts_azure_region_hint), style = MaterialTheme.typography.bodySmall, color = hoshiSurfaces.muted)
            }
            OutlinedTextField(apiKey, { apiKey = it; tested = false; failure = null }, modifier = Modifier.fillMaxWidth(),
                enabled = !busy, singleLine = true, label = { Text(stringResource(R.string.tts_api_key)) },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = hoshiOutlinedTextFieldColors())
            Text(stringResource(if (config.encryptedKey.isNotBlank()) R.string.tts_key_saved_hint else R.string.tts_key_device_hint),
                style = MaterialTheme.typography.bodySmall, color = hoshiSurfaces.muted)
            if (config.encryptedKey.isNotBlank()) TextButton(enabled = !busy, onClick = { confirmDelete = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(R.string.tts_delete_key))
            }
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TtsConnectionTestControls(!busy && regionReady, testing, tested, failure, onTest = { scope.launch {
                busy = true; testing = true; tested = false; failure = null
                try { failure = onTest(region, apiKey); tested = failure == null }
                finally { busy = false; testing = false }
            } })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(48.dp)) { Text(stringResource(R.string.action_cancel)) }
                HoshiButton(enabled = !busy && regionReady, modifier = Modifier.weight(1f).height(48.dp), onClick = {
                    scope.launch {
                        busy = true; failure = null
                        try { failure = onSave(region, apiKey); if (failure == null) { apiKey = ""; onDismiss() } }
                        finally { busy = false }
                    }
                }) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
    if (confirmDelete) HoshiAlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.tts_delete_key_title)) },
        text = { Text(stringResource(R.string.tts_delete_key_message)) },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                busy = true; tested = false; failure = null
                scope.launch {
                    try { failure = onDelete(); if (failure == null) apiKey = "" }
                    finally { busy = false }
                }
            }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(R.string.action_delete))
            }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } })
}
