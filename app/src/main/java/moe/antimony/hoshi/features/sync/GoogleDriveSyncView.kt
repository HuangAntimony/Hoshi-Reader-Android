package moe.antimony.hoshi.features.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun GoogleDriveSyncView(
    settings: DriveSyncSettings,
    authState: GoogleDriveAuthState,
    isSignedIn: Boolean,
    accountEmail: String?,
    onSettingsChange: (DriveSyncSettings) -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onSyncAll: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        TextButton(onClick = onClose) {
            Text("Done")
        }
        Text(
            text = "Google Drive Sync",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = statusText(authState, isSignedIn, accountEmail),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onSignIn,
                enabled = authState == GoogleDriveAuthState.Configured,
            ) {
                Text("Sign in")
            }
            TextButton(
                onClick = onSignOut,
                enabled = isSignedIn,
            ) {
                Text("Sign out")
            }
        }
        SyncToggleRow(
            label = "Enable sync",
            checked = settings.isEnabled,
            onCheckedChange = { onSettingsChange(settings.copy(isEnabled = it)) },
        )
        SyncToggleRow(
            label = "Sync when opening a book",
            checked = settings.autoSyncOnOpen,
            enabled = settings.isEnabled,
            onCheckedChange = { onSettingsChange(settings.copy(autoSyncOnOpen = it)) },
        )
        SyncToggleRow(
            label = "Sync after saving progress",
            checked = settings.autoSyncOnBookmark,
            enabled = settings.isEnabled,
            onCheckedChange = { onSettingsChange(settings.copy(autoSyncOnBookmark = it)) },
        )
        Button(
            onClick = onSyncAll,
            enabled = settings.isEnabled && isSignedIn,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Sync all books")
        }
        settings.lastStatus?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SyncToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

private fun statusText(
    authState: GoogleDriveAuthState,
    isSignedIn: Boolean,
    accountEmail: String?,
): String =
    when {
        authState == GoogleDriveAuthState.MissingClientId -> "Google client id is not configured."
        authState == GoogleDriveAuthState.InvalidClientId -> "Google client id is invalid."
        isSignedIn -> "Signed in as ${accountEmail.orEmpty()}"
        else -> "Sign in to sync reading progress through Google Drive."
    }
