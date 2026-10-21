package moe.antimony.hoshi.features.sync

import moe.antimony.hoshi.ui.theme.hoshiContainerOutline
import moe.antimony.hoshi.ui.theme.hoshiSurfaces
import moe.antimony.hoshi.ui.theme.hoshiContainerBorder
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.SwapVert
import moe.antimony.hoshi.ui.HoshiAlertDialog as AlertDialog
import moe.antimony.hoshi.ui.HoshiButton as Button
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import moe.antimony.hoshi.ui.HoshiDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.antimony.hoshi.LocalHoshiUiDependencies
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.ErrorDetailsText
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold
import moe.antimony.hoshi.features.settings.SettingsLoadState
import moe.antimony.hoshi.features.settings.rememberSettingsLoader
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.asString
import moe.antimony.hoshi.ui.withErrorDetails
import kotlinx.coroutines.flow.combine
import moe.antimony.hoshi.ui.hoshiOutlinedTextFieldColors
import moe.antimony.hoshi.ui.hoshiSingleLineTextFieldLineLimits
import moe.antimony.hoshi.ui.rememberSyncedTextFieldState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsView(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val appContainer = LocalHoshiUiDependencies.current
    val repository = appContainer.syncSettingsRepository
    val readerSettingsRepository = appContainer.readerSettingsRepository
    val sasayakiSettingsRepository = appContainer.sasayakiSettingsRepository
    val authorizer = appContainer.deviceCodeDriveAuthorizer
    val googleAuth = appContainer.googleDriveAuth
    val hoshiSync = appContainer.googleDriveSyncManager
    val hoshiState by hoshiSync.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val settingsFlow = remember(repository, readerSettingsRepository, sasayakiSettingsRepository) {
        combine(repository.settings, readerSettingsRepository.settings, sasayakiSettingsRepository.settings) { sync, reader, sasayaki ->
            Triple(sync, reader, sasayaki)
        }
    }
    val settingsLoader = settingsFlow.rememberSettingsLoader()
    val confirmed = (settingsLoader.state as? SettingsLoadState.Loaded)?.value
    val settings = confirmed?.first
    val readerSettings = confirmed?.second
    val sasayakiSettings = confirmed?.third
    var authLoadError by remember { mutableStateOf<UiText?>(null) }
    var authAttempt by remember { mutableStateOf(0) }
    var authStatus by remember { mutableStateOf<DriveAuthStatus?>(null) }
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var concurrencyMenuExpanded by remember { mutableStateOf(false) }
    var directionMenuExpanded by remember { mutableStateOf(false) }
    var statisticsModeMenuExpanded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var copyMessage by remember { mutableStateOf<String?>(null) }
    var isAuthorizing by remember { mutableStateOf(false) }
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var devicePrompt by remember { mutableStateOf<DeviceCodePrompt?>(null) }
    var pollIntervalSeconds by remember { mutableStateOf(5L) }
    var operationError by remember { mutableStateOf<UiText?>(null) }
    var showSignOutConfirmation by remember { mutableStateOf(false) }
    var showClearCacheConfirmation by remember { mutableStateOf(false) }
    var showDownloadAllConfirmation by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    val screenState = SyncSettingsScreenState(settings = settings, authStatus = authStatus)
    val currentSettings = settings
    val currentReaderSettings = readerSettings
    val currentSasayakiSettings = sasayakiSettings
    val currentAuthStatus = authStatus
    val connectionActions = currentAuthStatus?.let { syncConnectionActions(it, isAuthorizing) }

    fun launchOperation(summary: UiText, operation: suspend () -> Unit) {
        scope.launch {
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                operationError = summary.withErrorDetails(error)
            }
        }
    }

    fun save(next: SyncSettings) = launchOperation(UiText.Resource(R.string.display_settings_save_failed)) {
        repository.update { next }
    }

    fun saveReaderSettings(next: ReaderSettings) = launchOperation(UiText.Resource(R.string.display_settings_save_failed)) {
        readerSettingsRepository.update { next }
    }

    fun saveSasayakiSettings(update: (SasayakiSettings) -> SasayakiSettings) =
        launchOperation(UiText.Resource(R.string.display_settings_save_failed)) {
            sasayakiSettingsRepository.update(update)
        }

    val authorizationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        scope.launch {
            try {
                if (result.resultCode == Activity.RESULT_CANCELED && result.data == null) return@launch
                runDriveAuthorization({ operationError = it }) {
                    result.data?.let {
                        IntentCompat.getSerializableExtra(it,
                            ActivityResultContracts.StartIntentSenderForResult.EXTRA_SEND_INTENT_EXCEPTION,
                            IntentSender.SendIntentException::class.java)
                    }?.let { throw it }
                    googleAuth.authorizationResult(result.data)
                    hoshiSync.stop()
                    googleAuth.accept()
                    hoshiSync.resetConnection()
                    hoshiSync.start()
                    authStatus = DriveAuthStatus.Connected
                }
            } finally {
                isAuthorizing = false
            }
        }
    }

    val browserAuthorizationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        scope.launch {
            try {
                runDriveAuthorization({ operationError = it }) {
                    if (result.resultCode == Activity.RESULT_CANCELED) {
                        googleAuth.browser.cancelAuthorization()
                        return@runDriveAuthorization
                    }
                    hoshiSync.stop()
                    try {
                        googleAuth.acceptBrowserAuthorization(result.data)
                        hoshiSync.resetConnection()
                        authStatus = DriveAuthStatus.Connected
                    } finally {
                        hoshiSync.start()
                    }
                }
            } finally {
                isAuthorizing = false
            }
        }
    }

    LaunchedEffect(authorizer, authAttempt) {
        authLoadError = null
        try {
            authorizer.configuredClient()?.let { client ->
                clientId = client.clientId
                clientSecret = client.clientSecret
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            authLoadError = UiText.Resource(R.string.settings_load_failed).withErrorDetails(error)
        }
    }

    LaunchedEffect(settings?.provider) {
        settings?.let { authStatus = googleAuth.status(it.provider) }
        message = null
        devicePrompt = null
        isAuthorizing = false
    }

    LaunchedEffect(hoshiState.errorMessage) {
        if (settings?.provider == SyncProvider.Gdrive) authStatus = googleAuth.status(SyncProvider.Gdrive)
    }

    LaunchedEffect(devicePrompt, isAuthorizing) {
        val prompt = devicePrompt ?: return@LaunchedEffect
        if (!isAuthorizing) return@LaunchedEffect
        val expiresAtMillis = System.currentTimeMillis() + prompt.expiresInSeconds * 1000L
        var nextIntervalSeconds = pollIntervalSeconds
        while (isAuthorizing && System.currentTimeMillis() < expiresAtMillis) {
            delay(nextIntervalSeconds * 1000L)
            val result = try {
                authorizer.pollAuthorization(prompt)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                DriveAuthorizationResult.Failed(
                    resources.getString(R.string.sync_google_drive_authorization_failed).withErrorDetails(error),
                )
            }
            when (result) {
                is DriveAuthorizationResult.Authorized -> {
                    googleAuth.clearHoshiLogin()
                    hoshiSync.start()
                    isAuthorizing = false
                    devicePrompt = null
                    authStatus = DriveAuthStatus.Connected
                    message = null
                    break
                }
                DriveAuthorizationResult.Pending -> Unit
                DriveAuthorizationResult.SlowDown -> {
                    nextIntervalSeconds = nextDeviceCodePollIntervalSeconds(nextIntervalSeconds, result)
                    pollIntervalSeconds = nextIntervalSeconds
                }
                DriveAuthorizationResult.TransientNetworkFailure -> {
                    nextIntervalSeconds = nextDeviceCodePollIntervalSeconds(nextIntervalSeconds, result)
                    pollIntervalSeconds = nextIntervalSeconds
                    message = resources.getString(
                        R.string.sync_google_drive_transient_network_format,
                        prompt.verificationUrl,
                        prompt.userCode,
                    )
                }
                is DriveAuthorizationResult.Failed -> {
                    isAuthorizing = false
                    devicePrompt = null
                    authStatus = DriveAuthStatus.Failed(result.message)
                    message = result.message
                    break
                }
            }
        }
        if (isAuthorizing && devicePrompt == prompt) {
            isAuthorizing = false
            devicePrompt = null
            authStatus = DriveAuthStatus.NotConnected
            message = resources.getString(R.string.sync_google_drive_authorization_expired)
        }
    }

    fun connectGoogleDrive() {
        if (isAuthorizing) return
        isAuthorizing = true
        message = null
        copyMessage = null
        devicePrompt = null
        scope.launch {
            if (currentSettings?.provider == SyncProvider.Gdrive) {
                var awaitingResult = false
                try {
                    runDriveAuthorization({ operationError = it }) {
                        if (googleAuth.usesBrowserAuthorization()) {
                            browserAuthorizationLauncher.launch(googleAuth.browser.authorizationIntent())
                            awaitingResult = true
                        } else {
                            val result = googleAuth.authorize(selectAccount = true)
                            if (result.hasResolution()) {
                                authorizationLauncher.launch(IntentSenderRequest.Builder(result.pendingIntent!!).build())
                                awaitingResult = true
                            } else {
                                hoshiSync.stop()
                                googleAuth.accept()
                                hoshiSync.resetConnection()
                                hoshiSync.start()
                                authStatus = DriveAuthStatus.Connected
                            }
                        }
                    }
                } finally {
                    if (!awaitingResult) isAuthorizing = false
                }
                return@launch
            }
            if (clientId.isBlank() || clientSecret.isBlank()) {
                isAuthorizing = false
                authStatus = DriveAuthStatus.MissingConfiguration
                message = DeviceCodeDriveAuthorizer.MissingConfigurationMessage
                return@launch
            }
            runCatching {
                authorizer.saveClient(clientId, clientSecret)
                authorizer.requestDeviceCode()
            }
                .onSuccess { prompt ->
                    pollIntervalSeconds = prompt.intervalSeconds
                    devicePrompt = prompt
                    authStatus = DriveAuthStatus.NotConnected
                    message = resources.getString(
                        R.string.sync_google_drive_open_code_format,
                        prompt.verificationUrl,
                        prompt.userCode,
                    )
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(prompt.verificationUrl)))
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        operationError = UiText.Resource(R.string.sync_google_drive_authorization_failed).withErrorDetails(error)
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    isAuthorizing = false
                    val text = resources.getString(R.string.sync_google_drive_authorization_failed).withErrorDetails(error)
                    authStatus = DriveAuthStatus.Failed(text)
                    message = text
                }
        }
    }

    fun signOut() {
        launchOperation(UiText.Resource(R.string.action_sign_out)) {
            hoshiSync.signOut()
            authStatus = googleAuth.status()
            message = null
            copyMessage = null
            devicePrompt = null
            isAuthorizing = false
        }
    }

    fun clearCache() {
        launchOperation(UiText.Resource(R.string.sync_clear_cache)) {
            hoshiSync.clearCache()
            message = resources.getString(R.string.sync_cache_cleared)
        }
    }

    operationError?.let { error ->
        AlertDialog(
            onDismissRequest = { operationError = null },
            title = { Text(stringResource(R.string.dialog_error_title)) },
            text = { ErrorDetailsText(error.asString()) },
            confirmButton = {
                TextButton(onClick = { operationError = null }) { Text(stringResource(R.string.action_ok)) }
            },
        )
    }

    if (showSignOutConfirmation) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirmation = false },
            title = { Text(stringResource(R.string.sync_sign_out_title)) },
            text = { Text(stringResource(R.string.sync_sign_out_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSignOutConfirmation = false
                        signOut()
                    },
                ) {
                    Text(stringResource(R.string.action_sign_out))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutConfirmation = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    if (showClearCacheConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearCacheConfirmation = false },
            title = { Text(stringResource(R.string.sync_clear_cache_title)) },
            text = { Text(stringResource(R.string.sync_clear_cache_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearCacheConfirmation = false
                        clearCache()
                    },
                ) {
                    Text(stringResource(R.string.sync_clear_cache))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheConfirmation = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    if (showDownloadAllConfirmation) {
        AlertDialog(
            onDismissRequest = { showDownloadAllConfirmation = false },
            title = { Text(stringResource(R.string.sync_download_all_books_title)) },
            text = { Text(pluralStringResource(R.plurals.sync_download_all_books_confirmation, hoshiState.missingBooks, hoshiState.missingBooks)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDownloadAllConfirmation = false
                        scope.launch { hoshiSync.downloadAll() }
                    },
                ) {
                    Text(stringResource(R.string.action_download))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadAllConfirmation = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    if (showQueue) {
        SyncQueueSheet(state = hoshiState, onDismiss = { showQueue = false }, onError = { operationError = it })
    }
    val colorScheme = MaterialTheme.colorScheme
    val screenLoadState: SettingsLoadState<Triple<SyncSettings, ReaderSettings, SasayakiSettings>> = when {
        settingsLoader.state is SettingsLoadState.Failed -> settingsLoader.state
        authLoadError != null -> SettingsLoadState.Failed(requireNotNull(authLoadError))
        authStatus == null -> SettingsLoadState.Loading
        else -> settingsLoader.state
    }
    SettingsDetailScaffold(
        state = screenLoadState,
        title = stringResource(R.string.sync_title),
        onClose = onClose,
        onRetry = { settingsLoader.retry(); authAttempt++ },
        modifier = modifier,
    ) { innerPadding, (currentSettings, currentReaderSettings, currentSasayakiSettings) ->
        val currentAuthStatus = requireNotNull(authStatus)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    SettingsCard {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(stringResource(R.string.action_enable)) },
                            trailingContent = {
                                Switch(
                                    checked = currentSettings.enabled,
                                    onCheckedChange = { save(currentSettings.copy(enabled = it)) },
                                )
                            },
                        )
                        SettingsDivider()
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(stringResource(R.string.sync_provider)) },
                            trailingContent = {
                                Box {
                                    TextButton(onClick = { providerMenuExpanded = true }, enabled = !isAuthorizing) {
                                        Text(stringResource(currentSettings.provider.labelRes))
                                    }
                                    DropdownMenu(expanded = providerMenuExpanded, onDismissRequest = { providerMenuExpanded = false }) {
                                        SyncProvider.entries.forEach { provider ->
                                            DropdownMenuItem(
                                                text = { Text(stringResource(provider.labelRes)) },
                                                onClick = {
                                                    providerMenuExpanded = false
                                                    scope.launch {
                                                        runCatching { hoshiSync.changeProvider(provider) }
                                                            .onFailure { message = resources.getString(R.string.bookshelf_sync_failed) }
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                            },
                        )
                    }
                    Text(
                        stringResource(R.string.sync_hoshi_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    if (currentSettings.enabled && currentSettings.provider == SyncProvider.Ttu && currentAuthStatus == DriveAuthStatus.Connected) {
                        SettingsSectionTitle(stringResource(R.string.sync_section_behaviour))
                        SettingsCard {
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = { Text(stringResource(R.string.sync_direction)) },
                                trailingContent = {
                                    Box {
                                        TextButton(onClick = { directionMenuExpanded = true }) {
                                            Text(stringResource(currentSettings.mode.labelRes))
                                        }
                                        DropdownMenu(
                                            expanded = directionMenuExpanded,
                                            onDismissRequest = { directionMenuExpanded = false },
                                        ) {
                                            SyncMode.entries.forEach { mode ->
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(mode.labelRes)) },
                                                    onClick = {
                                                        directionMenuExpanded = false
                                                        save(currentSettings.copy(mode = mode))
                                                    },
                                                )
                                            }
                                        }
                                    }
                                },
                            )
                            SettingsDivider()
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = { Text(stringResource(R.string.sync_auto_sync)) },
                                trailingContent = {
                                    Switch(
                                        checked = currentSettings.autoSyncEnabled,
                                        onCheckedChange = { save(currentSettings.copy(autoSyncEnabled = it)) },
                                    )
                                },
                            )
                        }
                        SettingsSectionTitle(stringResource(R.string.sync_section_data))
                        SettingsCard {
                            syncSettingsDataRows(
                                syncSettings = currentSettings,
                                readerSettings = currentReaderSettings,
                                sasayakiSettings = currentSasayakiSettings,
                            ).forEachIndexed { index, row ->
                                if (index > 0) {
                                    SettingsDivider()
                                }
                                ListItem(
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    headlineContent = { Text(stringResource(row.titleRes)) },
                                    supportingContent = row.supportingTextRes?.let { labelRes ->
                                        { Text(stringResource(labelRes)) }
                                    },
                                    trailingContent = {
                                        Switch(
                                            checked = row.checked,
                                            onCheckedChange = { checked ->
                                                when (row.kind) {
                                                    SyncSettingsDataRowKind.UploadBooks ->
                                                        save(currentSettings.copy(uploadBooks = checked))
                                                    SyncSettingsDataRowKind.SyncStats ->
                                                        saveReaderSettings(
                                                            currentReaderSettings.copy(statisticsSyncEnabled = checked),
                                                        )
                                                    SyncSettingsDataRowKind.SyncAudiobookProgress ->
                                                        saveSasayakiSettings { current -> current.copy(syncEnabled = checked) }
                                                }
                                            },
                                        )
                                    },
                                )
                                if (row.kind == SyncSettingsDataRowKind.SyncStats) {
                                    SettingsDivider()
                                    ListItem(
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                        headlineContent = { Text(stringResource(R.string.reader_statistics_sync_behaviour)) },
                                        supportingContent = { Text(stringResource(R.string.reader_statistics_sync_behaviour_hint)) },
                                        trailingContent = {
                                            Box {
                                                TextButton(onClick = { statisticsModeMenuExpanded = true }) {
                                                    Text(stringResource(currentReaderSettings.statisticsSyncMode.labelRes))
                                                }
                                                DropdownMenu(expanded = statisticsModeMenuExpanded, onDismissRequest = { statisticsModeMenuExpanded = false }) {
                                                    StatisticsSyncMode.entries.forEach { mode ->
                                                        DropdownMenuItem(
                                                            text = { Text(stringResource(mode.labelRes)) },
                                                            onClick = {
                                                                statisticsModeMenuExpanded = false
                                                                saveReaderSettings(currentReaderSettings.copy(statisticsSyncMode = mode))
                                                            },
                                                        )
                                                    }
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item {
                if (!currentSettings.enabled) {
                    return@item
                }
                SettingsCard {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(stringResource(R.string.sync_google_drive)) },
                        supportingContent = {
                            Text(
                                if (currentSettings.provider == SyncProvider.Gdrive && currentAuthStatus == DriveAuthStatus.Connected && hoshiState.attachPending) {
                                    stringResource(R.string.sync_status_choice_needed)
                                } else {
                                    currentAuthStatus.labelText()
                                },
                            )
                        },
                    )
                    if (currentSettings.provider == SyncProvider.Ttu) {
                        SettingsDivider()
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            val clientIdScrollState = rememberScrollState()
                            val clientIdState = rememberSyncedTextFieldState(
                                value = clientId,
                                onValueChange = { clientId = it },
                                scrollState = clientIdScrollState,
                            )
                            val clientSecretState = rememberSyncedTextFieldState(
                                value = clientSecret,
                                onValueChange = { clientSecret = it },
                            )
                            OutlinedTextField(
                                state = clientIdState,
                                label = { Text(stringResource(R.string.sync_device_client_id)) },
                                lineLimits = hoshiSingleLineTextFieldLineLimits(),
                                scrollState = clientIdScrollState,
                                colors = hoshiOutlinedTextFieldColors(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedSecureTextField(
                                state = clientSecretState,
                                label = { Text(stringResource(R.string.sync_device_client_secret)) },
                                colors = hoshiOutlinedTextFieldColors(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                devicePrompt?.let { prompt ->
                    SettingsCard {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.sync_authorize_google_drive),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(R.string.sync_google_drive_open_link_prompt),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colorScheme.onSurfaceVariant,
                            )
                            SettingsLinkText(
                                text = prompt.verificationUrl,
                                url = prompt.verificationUrl,
                            )
                            Text(
                                text = prompt.userCode,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            OutlinedButton(
                                onClick = {
                                    context.copyTextToClipboard("Google device code", prompt.userCode)
                                    copyMessage = resources.getString(R.string.sync_device_code_copied)
                                },
                            ) {
                                Icon(
                                    Icons.Rounded.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                                Text(stringResource(R.string.action_copy_code))
                            }
                        }
                    }
                }
                copyMessage?.let { text ->
                    Text(
                        text = text,
                        color = colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                    )
                }
                message?.let { text ->
                    Text(
                        text = text,
                        color = if (currentAuthStatus is DriveAuthStatus.Failed) colorScheme.error else colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                    )
                }
            }
            item {
                if (currentSettings?.enabled == true && currentSettings.provider == SyncProvider.Gdrive && currentAuthStatus == DriveAuthStatus.Connected) {
                    SettingsCard {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(stringResource(R.string.sync_transfer_concurrency)) },
                            trailingContent = {
                                Box {
                                    TextButton(onClick = { concurrencyMenuExpanded = true }) { Text(currentSettings.transferConcurrency.toString()) }
                                    DropdownMenu(expanded = concurrencyMenuExpanded, onDismissRequest = { concurrencyMenuExpanded = false }) {
                                        (1..8).forEach { count ->
                                            DropdownMenuItem(text = { Text(count.toString()) }, onClick = {
                                                concurrencyMenuExpanded = false
                                                launchOperation(UiText.Resource(R.string.display_settings_save_failed)) {
                                                    repository.update { it.copy(transferConcurrency = count) }
                                                }
                                            })
                                        }
                                    }
                                }
                            },
                        )
                        SettingsDivider()
                        hoshiState.lastSync?.let { lastSync ->
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = { Text(stringResource(R.string.sync_last_sync)) },
                                trailingContent = { Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(lastSync))) },
                            )
                            SettingsDivider()
                        }
                        val progress = hoshiState.progress
                        val failed = hoshiState.queue.count { it.error != null }
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { showQueue = true },
                            headlineContent = { Text(stringResource(R.string.sync_queue)) },
                            supportingContent = progress?.let {
                                {
                                    LinearProgressIndicator(
                                        progress = { progress.done.toFloat() / progress.total },
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    )
                                }
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    when {
                                        progress != null -> Text(stringResource(R.string.sync_queue_progress, progress.done, progress.total))
                                        failed > 0 -> Text(pluralStringResource(R.plurals.sync_queue_failed, failed, failed), color = colorScheme.error)
                                        hoshiState.queue.isNotEmpty() -> Text(hoshiState.queue.size.toString())
                                        else -> Text(stringResource(R.string.sync_queue_empty))
                                    }
                                    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = colorScheme.onSurfaceVariant)
                                }
                            },
                        )
                        SettingsDivider()
                        TextButton(
                            onClick = {
                                hoshiSync.setAttachDismissed(false)
                                scope.launch { hoshiSync.sync() }
                            },
                            enabled = !hoshiState.isSyncing,
                        ) {
                            Text(stringResource(R.string.sync_now))
                        }
                        SettingsDivider()
                        TextButton(
                            onClick = { showDownloadAllConfirmation = true },
                            enabled = hoshiState.missingBooks > 0,
                        ) {
                            Text(stringResource(R.string.sync_download_all_books))
                        }
                    }
                    hoshiState.errorMessage?.let { error ->
                        TextButton(onClick = { operationError = error }) {
                            Text(stringResource(R.string.sync_error_details), color = colorScheme.error)
                        }
                    }
                }
            }
            item {
                if (!screenState.isContentReady || connectionActions == null || currentSettings?.enabled != true) {
                    return@item
                }
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (connectionActions.showConnect) {
                        Button(
                            onClick = ::connectGoogleDrive,
                            enabled = connectionActions.connectEnabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.sync_connect_google_drive))
                        }
                    }
                    if (connectionActions.showSignOut) {
                        OutlinedButton(
                            onClick = { showSignOutConfirmation = true },
                            enabled = connectionActions.signOutEnabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.action_sign_out))
                        }
                    }
                    if (screenState.showClearCacheAction) {
                        OutlinedButton(
                            onClick = { showClearCacheConfirmation = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.sync_clear_cache))
                        }
                    }
                    if (currentSettings.provider == SyncProvider.Ttu) GoogleCloudOAuthSetupCard()
                }
            }
        }
    }
}

@Composable
private fun GoogleCloudOAuthSetupCard() {
    val colorScheme = MaterialTheme.colorScheme
    val instructions = stringArrayResource(R.array.sync_device_code_instructions)
    SettingsCard {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.sync_device_code_setup),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.sync_device_code_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
            )
            SettingsLinkText(
                text = stringResource(R.string.sync_ttu_google_cloud_setup),
                url = GoogleCloudOAuthConfiguration.ttuSetupUrl,
            )
            instructions.forEachIndexed { index, instruction ->
                GoogleCloudOAuthInstructionText(index = index, instruction = instruction)
            }
        }
    }
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun GoogleCloudOAuthInstructionText(index: Int, instruction: String) {
    val linkColor = MaterialTheme.colorScheme.primary
    val text = buildAnnotatedString {
        append("${index + 1}. ")
        appendTextWithLinks(
            text = instruction,
            links = GoogleCloudOAuthConfiguration.instructionLinks,
            color = linkColor,
        )
    }
    SettingsAnnotatedLinkText(
        text = text,
    )
}

@Composable
private fun SettingsLinkText(
    text: String,
    url: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier = modifier.clickable {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        },
    )
}

@Composable
private fun SettingsAnnotatedLinkText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = modifier,
    )
}

private fun AnnotatedString.Builder.appendTextWithLinks(
    text: String,
    links: Map<String, String>,
    color: Color,
) {
    var cursor = 0
    while (cursor < text.length) {
        val nextLink = links.keys
            .mapNotNull { label ->
                val start = text.indexOf(label, startIndex = cursor)
                if (start >= 0) label to start else null
            }
            .minByOrNull { it.second }

        if (nextLink == null) {
            append(text.substring(cursor))
            break
        }

        val (label, start) = nextLink
        append(text.substring(cursor, start))
        appendLink(text = label, url = links.getValue(label), color = color)
        cursor = start + label.length
    }
}

private fun AnnotatedString.Builder.appendLink(text: String, url: String, color: Color) {
    withLink(
        LinkAnnotation.Url(
            url = url,
            styles = TextLinkStyles(
                style = SpanStyle(
                    color = color,
                    textDecoration = TextDecoration.Underline,
                ),
            ),
        ),
    ) {
        append(text)
    }
}

@Composable
private fun CopyValueButton(label: String, value: String, onCopied: () -> Unit) {
    val context = LocalContext.current
    IconButton(
        onClick = {
            context.copyTextToClipboard(label, value)
            onCopied()
        },
    ) {
        Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.action_copy_code))
    }
}

@Composable
private fun DriveAuthStatus.labelText(): String =
    when (this) {
        DriveAuthStatus.Connected -> stringResource(R.string.sync_status_connected)
        DriveAuthStatus.NotConnected -> stringResource(R.string.sync_status_not_connected)
        DriveAuthStatus.MissingConfiguration -> stringResource(R.string.sync_status_missing_configuration)
        is DriveAuthStatus.Failed -> message
    }

@get:StringRes
private val SyncProvider.labelRes: Int
    get() = when (this) {
        SyncProvider.Gdrive -> R.string.sync_google_drive
        SyncProvider.Ttu -> R.string.sync_provider_ttu
    }

@get:StringRes
private val SyncMode.labelRes: Int
    get() = when (this) {
        SyncMode.Auto -> R.string.sync_mode_auto
        SyncMode.Manual -> R.string.sync_mode_manual
    }

internal enum class SyncSettingsDataRowKind {
    UploadBooks,
    SyncStats,
    SyncAudiobookProgress,
}

internal data class SyncSettingsDataRow(
    val kind: SyncSettingsDataRowKind,
    @param:StringRes val titleRes: Int,
    @param:StringRes val supportingTextRes: Int? = null,
    val checked: Boolean,
)

internal fun syncSettingsDataRows(
    syncSettings: SyncSettings,
    readerSettings: ReaderSettings,
    sasayakiSettings: SasayakiSettings,
): List<SyncSettingsDataRow> = buildList {
    add(
        SyncSettingsDataRow(
            kind = SyncSettingsDataRowKind.UploadBooks,
            titleRes = R.string.sync_upload_books,
            supportingTextRes = R.string.sync_upload_books_description,
            checked = syncSettings.uploadBooks,
        ),
    )
    add(
        SyncSettingsDataRow(
            kind = SyncSettingsDataRowKind.SyncStats,
            titleRes = R.string.sync_stats,
            checked = readerSettings.statisticsSyncEnabled,
        ),
    )
    add(
        SyncSettingsDataRow(
            kind = SyncSettingsDataRowKind.SyncAudiobookProgress,
            titleRes = R.string.sync_audiobook_progress,
            checked = sasayakiSettings.syncEnabled,
        ),
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = hoshiSurfaces.group,
        border = hoshiContainerBorder(),
        tonalElevation = 0.dp,
    ) {
        Column(content = { content() })
    }
}

private fun Context.copyTextToClipboard(label: String, value: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@get:StringRes
private val StatisticsSyncMode.labelRes: Int
    get() = when (this) {
        StatisticsSyncMode.Merge -> R.string.reader_statistics_sync_mode_merge
        StatisticsSyncMode.Replace -> R.string.reader_statistics_sync_mode_replace
    }
