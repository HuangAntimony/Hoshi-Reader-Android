package moe.antimony.hoshi.features.sync

import android.content.ClipData
import android.content.Context
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.IntentSenderRequest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.security.MessageDigest
import kotlinx.coroutines.launch
import moe.antimony.hoshi.LocalHoshiAppContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsView(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContainer = LocalHoshiAppContainer.current
    val repository = appContainer.syncSettingsRepository
    val authorizer = appContainer.driveAuthorizer
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(SyncSettings()) }
    var authStatus by remember { mutableStateOf<DriveAuthStatus>(DriveAuthStatus.NotConnected) }
    var directionMenuExpanded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var copyMessage by remember { mutableStateOf<String?>(null) }
    var isAuthorizing by remember { mutableStateOf(false) }
    var pendingAuthorizationResolution by remember { mutableStateOf<IntentSenderRequest?>(null) }
    val packageName = remember(context) { context.packageName }
    val sha1 = remember(context) { context.signingCertificateSha1() }
    val connectionActions = syncConnectionActions(authStatus, isAuthorizing)

    fun save(next: SyncSettings) {
        settings = next
        scope.launch {
            repository.update { next }
        }
    }

    fun refreshStatus() {
        scope.launch {
            authStatus = authorizer.status()
        }
    }

    val authorizationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        isAuthorizing = false
        val resolved = resolveDriveAuthorizationActivityResult(
            resultCode = result.resultCode,
            hasData = result.data != null,
            authorizationResult = { authorizer.authorizationResultFromIntent(result.data) },
        )
        authStatus = resolved.status
        message = resolved.message
        resolved.resolutionRequest?.let { request ->
            pendingAuthorizationResolution = request
        }
    }

    LaunchedEffect(pendingAuthorizationResolution) {
        val request = pendingAuthorizationResolution ?: return@LaunchedEffect
        pendingAuthorizationResolution = null
        isAuthorizing = true
        authorizationLauncher.launch(request)
    }

    LaunchedEffect(repository) {
        repository.settings.collect { latest ->
            settings = latest
        }
    }
    LaunchedEffect(authorizer) {
        authStatus = authorizer.status()
    }

    fun connectGoogleDrive() {
        if (isAuthorizing) return
        isAuthorizing = true
        message = null
        copyMessage = null
        scope.launch {
            when (val result = authorizer.authorize()) {
                is DriveAuthorizationResult.Authorized -> {
                    isAuthorizing = false
                    authStatus = DriveAuthStatus.Connected
                }
                is DriveAuthorizationResult.RequiresResolution -> {
                    authorizationLauncher.launch(result.request)
                }
                DriveAuthorizationResult.GooglePlayServicesUnavailable -> {
                    isAuthorizing = false
                    authStatus = DriveAuthStatus.GooglePlayServicesUnavailable
                    message = GmsDriveAuthorizer.GmsRequiredMessage
                }
                is DriveAuthorizationResult.Failed -> {
                    isAuthorizing = false
                    authStatus = DriveAuthStatus.Failed(result.message)
                    message = result.message
                }
            }
        }
    }

    fun signOut() {
        scope.launch {
            authorizer.revokeAccess()
            appContainer.googleDriveClient.clearCache()
            authStatus = DriveAuthStatus.NotConnected
            message = null
            copyMessage = null
        }
    }

    BackHandler(onBack = onClose)
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
                title = { Text("Syncing", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
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
                SettingsCard {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text("Enable") },
                        trailingContent = {
                            Switch(
                                checked = settings.enabled,
                                onCheckedChange = { save(settings.copy(enabled = it)) },
                            )
                        },
                    )
                    SettingsDivider()
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text("Direction") },
                        trailingContent = {
                            Box {
                                TextButton(onClick = { directionMenuExpanded = true }) {
                                    Text(settings.mode.rawValue)
                                }
                                DropdownMenu(
                                    expanded = directionMenuExpanded,
                                    onDismissRequest = { directionMenuExpanded = false },
                                ) {
                                    SyncMode.entries.forEach { mode ->
                                        DropdownMenuItem(
                                            text = { Text(mode.rawValue) },
                                            onClick = {
                                                directionMenuExpanded = false
                                                save(settings.copy(mode = mode))
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
                        headlineContent = { Text("Auto Sync") },
                        trailingContent = {
                            Switch(
                                checked = settings.autoSyncEnabled,
                                onCheckedChange = { save(settings.copy(autoSyncEnabled = it)) },
                            )
                        },
                    )
                }
            }
            item {
                SettingsCard {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text("Google Drive") },
                        supportingContent = { Text(authStatus.label()) },
                    )
                    SettingsDivider()
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = {
                            Text("Package")
                        },
                        supportingContent = { Text(packageName) },
                        trailingContent = {
                            CopyValueButton(
                                label = "package name",
                                value = packageName,
                                onCopied = { copyMessage = "Package name copied" },
                            )
                        },
                    )
                    SettingsDivider()
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text("SHA-1") },
                        supportingContent = { Text(sha1) },
                        trailingContent = {
                            CopyValueButton(
                                label = "SHA-1",
                                value = sha1,
                                onCopied = { copyMessage = "SHA-1 copied" },
                            )
                        },
                    )
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
                        color = colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                    )
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (connectionActions.showConnect) {
                        Button(
                            onClick = ::connectGoogleDrive,
                            enabled = connectionActions.connectEnabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Connect Google Drive")
                        }
                    }
                    if (connectionActions.showSignOut) {
                        OutlinedButton(
                            onClick = ::signOut,
                            enabled = connectionActions.signOutEnabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Sign out")
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
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    SettingsCard {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Google Cloud OAuth setup",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colorScheme.onSurface,
            )
            Text(
                text = GoogleCloudOAuthConfiguration.introduction,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(GoogleCloudOAuthConfiguration.ttuSetupUrl)),
                    )
                },
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text("TTU Google Cloud setup")
            }
            GoogleCloudOAuthConfiguration.instructions.forEachIndexed { index, instruction ->
                Text(
                    text = "${index + 1}. $instruction",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                )
            }
        }
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
        Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy $label")
    }
}

private fun DriveAuthStatus.label(): String =
    when (this) {
        DriveAuthStatus.Connected -> "Connected"
        DriveAuthStatus.NotConnected -> "Not connected"
        DriveAuthStatus.GooglePlayServicesUnavailable -> "Google Play services unavailable"
        is DriveAuthStatus.Failed -> message
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

@Suppress("DEPRECATION")
private fun Context.signingCertificateSha1(): String {
    val signatures = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo
                ?.apkContentsSigners
                .orEmpty()
        } else {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty()
        }
    }.getOrDefault(emptyArray())
    val first = signatures.firstOrNull() ?: return "Unavailable"
    val digest = MessageDigest.getInstance("SHA-1").digest(first.toByteArray())
    return digest.joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }
}
