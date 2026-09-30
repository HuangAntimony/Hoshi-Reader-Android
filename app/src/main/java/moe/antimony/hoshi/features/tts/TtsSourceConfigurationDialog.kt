package moe.antimony.hoshi.features.tts

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
import moe.antimony.hoshi.ui.hoshiOutlinedTextFieldColors

@Composable
internal fun TtsSourceConfigurationDialog(
    source: TtsOnlineSource, config: TtsOnlineConfig, viewModel: TtsViewModel, onDismiss: () -> Unit,
) {
    var region by remember(source) { mutableStateOf(config.region) }
    // Credentials deliberately stay out of saveable state and are never read back into the field.
    var key by remember(source) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<TtsFailure?>(null) }
    var tested by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sample = stringResource(R.string.tts_sample)
    HoshiAlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(source.labelRes)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (source.needsKey) R.string.tts_cloud_credentials_hint else R.string.tts_unofficial_hint))
                if (source == TtsOnlineSource.Azure) OutlinedTextField(region, { region = it; tested = false },
                    enabled = !busy, singleLine = true, label = { Text(stringResource(R.string.tts_azure_region)) },
                    placeholder = { Text(stringResource(R.string.tts_azure_region_example)) }, colors = hoshiOutlinedTextFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                if (source.needsKey) {
                    OutlinedTextField(key, { key = it; tested = false }, enabled = !busy, singleLine = true,
                        label = { Text(stringResource(R.string.tts_api_key)) }, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), colors = hoshiOutlinedTextFieldColors())
                    if (config.encryptedKey.isNotBlank()) Text(stringResource(R.string.tts_key_saved_hint), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true; tested = false; failure = null
                        try { failure = viewModel.testConfiguration(source, region, key, sample); tested = failure == null }
                        finally { busy = false }
                    }
                }) { Text(stringResource(R.string.tts_test_connection)) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (tested) Text(stringResource(R.string.tts_connection_success))
                failure?.let { Text(stringResource(it.messageRes()), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true; failure = null
                    try { failure = viewModel.configure(source, region, key); if (failure == null) onDismiss() }
                    finally { busy = false }
                }
            }) { Text(stringResource(R.string.action_save)) }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}
