package moe.antimony.hoshi.features.tts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.antimony.hoshi.R
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
        onSave = { region, key -> viewModel.configure(source, region, key) }, onDismiss = onDismiss)
}

@Composable
internal fun TtsProviderConfiguration(
    source: TtsOnlineSource, config: TtsOnlineConfig,
    onTest: suspend (String, String) -> TtsFailure?, onSave: suspend (String, String) -> TtsFailure?, onDismiss: () -> Unit,
) {
    var region by remember(source) { mutableStateOf(config.region) }
    // Plaintext credentials stay out of saveable state; saved keys are never decrypted into the field.
    var apiKey by remember(source) { mutableStateOf("") }
    var busy by remember(source) { mutableStateOf(false) }
    var failure by remember(source) { mutableStateOf<TtsFailure?>(null) }
    var tested by remember(source) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    TtsSelectionSheet(stringResource(source.labelRes), onDismiss) {
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.tts_cloud_credentials_hint), style = MaterialTheme.typography.bodyMedium, color = hoshiSurfaces.muted)
            if (source == TtsOnlineSource.Azure) OutlinedTextField(region, { region = it; tested = false; failure = null },
                modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                label = { Text(stringResource(R.string.tts_azure_region)) }, placeholder = { Text(stringResource(R.string.tts_azure_region_example)) },
                colors = hoshiOutlinedTextFieldColors(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
            OutlinedTextField(apiKey, { apiKey = it; tested = false; failure = null }, modifier = Modifier.fillMaxWidth(),
                enabled = !busy, singleLine = true, label = { Text(stringResource(R.string.tts_api_key)) },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = hoshiOutlinedTextFieldColors())
            Text(stringResource(if (config.encryptedKey.isNotBlank()) R.string.tts_key_saved_hint else R.string.tts_key_device_hint),
                style = MaterialTheme.typography.bodySmall, color = hoshiSurfaces.muted)
            HoshiButton(enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp),
                onClick = {
                    scope.launch {
                        busy = true; tested = false; failure = null
                        try { failure = onTest(region, apiKey); tested = failure == null } finally { busy = false }
                    }
                }) {
                if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current); Spacer(Modifier.width(8.dp)) }
                Text(stringResource(R.string.tts_test_connection))
            }
            if (tested) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.CheckCircle, null, Modifier.size(20.dp))
                Text(stringResource(R.string.tts_connection_success), style = MaterialTheme.typography.bodyMedium)
            }
            failure?.let { Text(stringResource(it.messageRes()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(48.dp)) { Text(stringResource(R.string.action_cancel)) }
            HoshiButton(enabled = !busy, modifier = Modifier.weight(1f).height(48.dp), onClick = {
                scope.launch {
                    busy = true; failure = null
                    try { failure = onSave(region, apiKey); if (failure == null) { apiKey = ""; onDismiss() } }
                    finally { busy = false }
                }
            }) { Text(stringResource(R.string.action_save)) }
        }
    }
}
