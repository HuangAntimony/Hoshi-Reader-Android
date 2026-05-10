package moe.antimony.hoshi.features.update

import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.BuildConfig
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold
import java.io.File

private const val GitHubRepositoryUrl = "https://github.com/HuangAntimony/Hoshi-Reader-Android"

@Composable
fun AboutScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContainer = LocalHoshiAppContainer.current
    val scope = rememberCoroutineScope()
    val record by appContainer.updateDownloadStore.record.collectAsState(initial = null)
    val actionableRecord = record?.takeIf { it.shouldSurfaceInAbout(BuildConfig.VERSION_NAME) }
    var checkState by remember { mutableStateOf<AboutUpdateCheckState>(AboutUpdateCheckState.Idle) }

    SettingsDetailScaffold(
        title = "About",
        onClose = onClose,
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                AboutCard {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                        headlineContent = { Text("Hoshi Reader") },
                        supportingContent = {
                            Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        },
                    )
                }
            }
            item {
                AboutCard {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = "GitHub",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "If you like this app, consider starring the project on GitHub.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(GitHubRepositoryUrl)),
                                )
                            },
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text("GitHub")
                        }
                    }
                }
            }
            item {
                AboutCard {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = "Updates",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = updateStatusText(checkState, actionableRecord),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        checkState = AboutUpdateCheckState.Checking
                                        checkState = runCatching {
                                            withContext(Dispatchers.IO) {
                                                appContainer.updateCheckService.check(downloadIfAvailable = true)
                                            }
                                        }.fold(
                                            onSuccess = { AboutUpdateCheckState.Result(it) },
                                            onFailure = { error ->
                                                AboutUpdateCheckState.Error(
                                                    error.localizedMessage ?: "Failed to check for updates.",
                                                )
                                            },
                                        )
                                    }
                                },
                                enabled = checkState !is AboutUpdateCheckState.Checking,
                            ) {
                                if (checkState is AboutUpdateCheckState.Checking) {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .padding(end = 8.dp)
                                            .size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                                Text("Check for Updates")
                            }
                            val downloadedFile = actionableRecord
                                ?.takeIf { it.status == UpdateDownloadRecordStatus.Downloaded }
                                ?.let { appContainer.updateDownloadManager.updateFile(it.fileName) }
                                ?.takeIf(File::isFile)
                            if (downloadedFile != null) {
                                OutlinedButton(
                                    onClick = {
                                        openDownloadedUpdate(context, downloadedFile)?.let { message ->
                                            checkState = AboutUpdateCheckState.Error(message)
                                        }
                                    },
                                ) {
                                    androidx.compose.material3.Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 8.dp),
                                    )
                                    Text("Install")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
    ) {
        content()
    }
}

private sealed interface AboutUpdateCheckState {
    data object Idle : AboutUpdateCheckState
    data object Checking : AboutUpdateCheckState
    data class Result(val outcome: UpdateCheckOutcome) : AboutUpdateCheckState
    data class Error(val message: String) : AboutUpdateCheckState
}

private fun updateStatusText(
    checkState: AboutUpdateCheckState,
    record: UpdateDownloadRecord?,
): String =
    when (checkState) {
        AboutUpdateCheckState.Idle -> when (record?.status) {
            UpdateDownloadRecordStatus.Downloading -> "An update is downloading."
            UpdateDownloadRecordStatus.Downloaded -> "Update ${record.versionName} has been downloaded."
            UpdateDownloadRecordStatus.Failed -> "The last update download failed."
            null -> "Check GitHub Releases for a newer Hoshi Reader APK."
        }
        AboutUpdateCheckState.Checking -> "Checking GitHub Releases..."
        is AboutUpdateCheckState.Error -> checkState.message
        is AboutUpdateCheckState.Result -> when (val outcome = checkState.outcome) {
            UpdateCheckOutcome.UpToDate -> "You are running the latest version."
            UpdateCheckOutcome.NoInstallableAsset -> "A newer release was found, but it does not include a single matching APK."
            is UpdateCheckOutcome.Available -> "Update ${outcome.update.versionName} is available."
            is UpdateCheckOutcome.DownloadStarted -> "Downloading update ${outcome.update.versionName}."
            is UpdateCheckOutcome.DownloadInProgress -> "Update ${outcome.update.versionName} is already downloading."
            is UpdateCheckOutcome.DownloadAlreadyFinished -> "Update ${outcome.update.versionName} has already been downloaded."
        }
    }
