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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.antimony.hoshi.R

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
            Text(stringResource(R.string.common_done))
        }
        Text(
            text = stringResource(R.string.google_drive_sync_title),
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
                Text(stringResource(R.string.google_drive_sync_sign_in))
            }
            TextButton(
                onClick = onSignOut,
                enabled = isSignedIn,
            ) {
                Text(stringResource(R.string.google_drive_sync_sign_out))
            }
        }
        SyncToggleRow(
            label = stringResource(R.string.google_drive_sync_enable),
            checked = settings.isEnabled,
            onCheckedChange = { onSettingsChange(settings.copy(isEnabled = it)) },
        )
        SyncToggleRow(
            label = stringResource(R.string.google_drive_sync_on_open),
            checked = settings.autoSyncOnOpen,
            enabled = settings.isEnabled,
            onCheckedChange = { onSettingsChange(settings.copy(autoSyncOnOpen = it)) },
        )
        SyncToggleRow(
            label = stringResource(R.string.google_drive_sync_on_bookmark),
            checked = settings.autoSyncOnBookmark,
            enabled = settings.isEnabled,
            onCheckedChange = { onSettingsChange(settings.copy(autoSyncOnBookmark = it)) },
        )
        Button(
            onClick = onSyncAll,
            enabled = settings.isEnabled && isSignedIn,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.google_drive_sync_all_books))
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

@Composable
private fun statusText(
    authState: GoogleDriveAuthState,
    isSignedIn: Boolean,
    accountEmail: String?,
): String =
    when {
        authState == GoogleDriveAuthState.MissingClientId -> stringResource(R.string.google_drive_sync_missing_client_id)
        authState == GoogleDriveAuthState.InvalidClientId -> stringResource(R.string.google_drive_sync_invalid_client_id)
        isSignedIn -> stringResource(R.string.google_drive_sync_signed_in_as, accountEmail.orEmpty())
        else -> stringResource(R.string.google_drive_sync_sign_in_description)
    }
