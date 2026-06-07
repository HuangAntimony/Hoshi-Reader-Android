package moe.antimony.hoshi.features.sync

import android.content.ClipData
import android.content.Context
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.antimony.hoshi.LocalHoshiUiDependencies
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import moe.antimony.hoshi.features.settings.collectAsLoadedSettings
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
    val scope = rememberCoroutineScope()
    val settings = repository.settings.collectAsLoadedSettings()
    val readerSettings = readerSettingsRepository.settings.collectAsLoadedSettings()
    val sasayakiSettings = sasayakiSettingsRepository.settings.collectAsLoadedSettings()
    var authStatus by remember { mutableStateOf<DriveAuthStatus?>(null) }
    var directionMenuExpanded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var copyMessage by remember { mutableStateOf<String?>(null) }
    var isAuthorizing by remember { mutableStateOf(false) }
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var devicePrompt by remember { mutableStateOf<DeviceCodePrompt?>(null) }
    var pollIntervalSeconds by remember { mutableStateOf(5L) }
    var showSignOutConfirmation by remember { mutableStateOf(false) }
    var showClearCacheConfirmation by remember { mutableStateOf(false) }
    val screenState = SyncSettingsScreenState(settings = settings, authStatus = authStatus)
    val currentSettings = settings
    val currentReaderSettings = readerSettings
    val currentSasayakiSettings = sasayakiSettings
    val currentAuthStatus = authStatus
    val connectionActions = currentAuthStatus?.let { syncConnectionActions(it, isAuthorizing) }

    fun save(next: SyncSettings) {
        scope.launch {
            repository.update { next }
        }
    }

    fun saveReaderSettings(next: ReaderSettings) {
        scope.launch {
            readerSettingsRepository.update { next }
        }
    }

    fun saveSasayakiSettings(next: SasayakiSettings) {
        scope.launch {
            sasayakiSettingsRepository.update { next }
        }
    }

    LaunchedEffect(authorizer) {
        authorizer.configuredClient()?.let { client ->
            clientId = client.clientId
            clientSecret = client.clientSecret
        }
        authStatus = authorizer.status()
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
                    error.message ?: resources.getString(R.string.sync_google_drive_authorization_failed),
                )
            }
            when (result) {
                is DriveAuthorizationResult.Authorized -> {
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
            if (clientId.isBlank() || clientSecret.isBlank()) {
                isAuthorizing = false
                authStatus = DriveAuthStatus.MissingConfiguration
                message = DeviceCodeDriveAuthorizer.MissingConfigurationMessage
                return@launch
            }
            authorizer.saveClient(clientId, clientSecret)
            runCatching { authorizer.requestDeviceCode() }
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
                    }
                }
                .onFailure { error ->
                    isAuthorizing = false
                    val text = error.message ?: resources.getString(R.string.sync_google_drive_authorization_failed)
                    authStatus = DriveAuthStatus.Failed(text)
                    message = text
                }
        }
    }

    fun signOut() {
        scope.launch {
            authorizer.revokeAccess()
            repository.clearGoogleDriveCache()
            authStatus = authorizer.status()
            message = null
            copyMessage = null
            devicePrompt = null
            isAuthorizing = false
        }
    }

    fun clearCache() {
        scope.launch {
            repository.clearGoogleDriveCache()
            message = resources.getString(R.string.sync_cache_cleared)
        }
    }

    BackHandler(onBack = onClose)
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
    val colorScheme = MaterialTheme.colorScheme
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colorScheme.background,
                    scrolledContainerColor = colorScheme.background,
                ),
                title = { Text(stringResource(R.string.sync_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                if (
                    !screenState.isContentReady ||
                    currentSettings == null ||
                    currentReaderSettings == null ||
                    currentSasayakiSettings == null ||
                    currentAuthStatus == null
                ) {
                    return@item
                }
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
                    }
                    if (currentSettings.enabled) {
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
                                                        saveSasayakiSettings(
                                                            currentSasayakiSettings.copy(syncEnabled = checked),
                                                        )
                                                }
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
            item {
                if (!screenState.isContentReady || currentAuthStatus == null) {
                    return@item
                }
                SettingsCard {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(stringResource(R.string.sync_google_drive)) },
                        supportingContent = { Text(currentAuthStatus.labelText()) },
                    )
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
                if (!screenState.isContentReady || connectionActions == null) {
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
                    GoogleCloudOAuthSetupCard()
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
    if (readerSettings.enableStatistics) {
        add(
            SyncSettingsDataRow(
                kind = SyncSettingsDataRowKind.SyncStats,
                titleRes = R.string.sync_stats,
                checked = readerSettings.statisticsSyncEnabled,
            ),
        )
    }
    if (sasayakiSettings.enabled) {
        add(
            SyncSettingsDataRow(
                kind = SyncSettingsDataRowKind.SyncAudiobookProgress,
                titleRes = R.string.sync_audiobook_progress,
                checked = sasayakiSettings.syncEnabled,
            ),
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
