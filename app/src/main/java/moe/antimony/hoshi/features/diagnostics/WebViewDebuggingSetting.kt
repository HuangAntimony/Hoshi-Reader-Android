package moe.antimony.hoshi.features.diagnostics

import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.settings.SettingsContent
import moe.antimony.hoshi.features.settings.SettingsLoadState
import moe.antimony.hoshi.ui.ErrorDetailsText
import moe.antimony.hoshi.ui.HoshiAlertDialog
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.asString
import moe.antimony.hoshi.ui.withErrorDetails

@HiltViewModel
internal class WebViewDebuggingViewModel @Inject constructor(
    private val settings: WebViewDebuggingSettings,
) : ViewModel() {
    val state = settings.state
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    private val _error = MutableStateFlow<UiText?>(null)
    val error = _error.asStateFlow()

    init { retry() }

    fun retry() { viewModelScope.launch { settings.initialize() } }

    fun setEnabled(enabled: Boolean) {
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                settings.setEnabled(enabled)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _error.value = UiText.Resource(R.string.diagnostics_webview_debugging_failed).withErrorDetails(error)
            } finally { _saving.value = false }
        }
    }

    fun dismissError() { _error.value = null }
}

@Composable
internal fun WebViewDebuggingSetting() {
    val model: WebViewDebuggingViewModel = hiltViewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val saving by model.saving.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    error?.let { message ->
        HoshiAlertDialog(
            onDismissRequest = model::dismissError,
            text = { ErrorDetailsText(message.asString()) },
            confirmButton = {
                TextButton(onClick = model::dismissError) { Text(stringResource(R.string.action_ok)) }
            },
        )
    }
    WebViewDebuggingSetting(state, saving, model::setEnabled, model::retry)
}

@Composable
internal fun WebViewDebuggingSetting(
    state: SettingsLoadState<Boolean>,
    saving: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onRetry: () -> Unit,
) {
    SettingsContent(state, onRetry = onRetry) { enabled ->
        ListItem(
            modifier = Modifier.toggleable(value = enabled, enabled = !saving, role = Role.Switch, onValueChange = onEnabledChange),
            headlineContent = { Text(stringResource(R.string.diagnostics_webview_debugging)) },
            supportingContent = { Text(stringResource(R.string.diagnostics_webview_debugging_help)) },
            trailingContent = { Switch(checked = enabled, onCheckedChange = null, enabled = !saving) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}
