package moe.antimony.hoshi.features.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.antimony.hoshi.LocalHoshiUiDependencies
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold
import moe.antimony.hoshi.features.settings.collectAsLoadedSettings

@Composable
fun ReaderBehaviorScreen(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appContainer = LocalHoshiUiDependencies.current
    val updateSettings = appContainer.updateSettingsRepository.settings.collectAsLoadedSettings()
    val scope = rememberCoroutineScope()
    SettingsDetailScaffold(
        title = stringResource(R.string.settings_behavior),
        onClose = onClose,
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        ) {
            item {
                val loadedUpdateSettings = updateSettings ?: return@item
                BehaviorSettingsCard {
                    BehaviorSwitchRow(
                        label = stringResource(ReaderBehaviorRow.VolumeKeysTurnPages.labelRes),
                        checked = settings.volumeKeysTurnPages,
                        onCheckedChange = {
                            onSettingsChange(settings.copy(volumeKeysTurnPages = it))
                        },
                    )
                    BehaviorDivider()
                    BehaviorSwitchRow(
                        label = stringResource(ReaderBehaviorRow.VolumeKeysNavigatePopupTerms.labelRes),
                        checked = settings.volumeKeysNavigatePopupTerms,
                        onCheckedChange = {
                            onSettingsChange(settings.copy(volumeKeysNavigatePopupTerms = it))
                        },
                    )
                    readerBehaviorSasayakiRows().forEach { labelRes ->
                        BehaviorDivider()
                        BehaviorSwitchRow(
                            label = stringResource(labelRes),
                            checked = settings.volumeKeysSeekSasayaki,
                            onCheckedChange = {
                                onSettingsChange(settings.copy(volumeKeysSeekSasayaki = it))
                            },
                        )
                    }
                    BehaviorDivider()
                    BehaviorSwitchRow(
                        label = stringResource(ReaderBehaviorRow.ReverseVolumeKeyDirection.labelRes),
                        checked = settings.reverseVolumeKeyDirection,
                        onCheckedChange = {
                            onSettingsChange(settings.copy(reverseVolumeKeyDirection = it))
                        },
                    )
                    BehaviorDivider()
                    BehaviorSwitchRow(
                        label = stringResource(ReaderBehaviorRow.KeepScreenOn.labelRes),
                        checked = settings.keepScreenOnWhileReading,
                        onCheckedChange = {
                            onSettingsChange(settings.copy(keepScreenOnWhileReading = it))
                        },
                    )
                    BehaviorDivider()
                    BehaviorSwitchRow(
                        label = stringResource(ReaderBehaviorRow.LockCurrentOrientation.labelRes),
                        checked = settings.lockCurrentOrientation,
                        onCheckedChange = {
                            onSettingsChange(settings.copy(lockCurrentOrientation = it))
                        },
                    )
                    BehaviorDivider()
                    BehaviorSwitchRow(
                        label = stringResource(ReaderBehaviorRow.OpenLastReadBookOnLaunch.labelRes),
                        checked = settings.openLastReadBookOnLaunch,
                        onCheckedChange = {
                            onSettingsChange(settings.copy(openLastReadBookOnLaunch = it))
                        },
                    )
                    BehaviorDivider()
                    BehaviorSwitchRow(
                        label = stringResource(ReaderBehaviorRow.AutomaticallyCheckForUpdates.labelRes),
                        checked = loadedUpdateSettings.autoCheckUpdates,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                appContainer.updateSettingsRepository.update {
                                    it.copy(autoCheckUpdates = enabled)
                                }
                                if (enabled) {
                                    appContainer.updateScheduler.schedule()
                                    appContainer.updateScheduler.scheduleImmediateCheck()
                                } else {
                                    appContainer.updateScheduler.cancel()
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

internal fun readerBehaviorSasayakiRows(): List<Int> =
    listOf(ReaderBehaviorRow.VolumeKeysSeekSasayaki.labelRes)

internal fun readerBehaviorRows(): List<Int> = ReaderBehaviorRow.entries.map { it.labelRes }

private enum class ReaderBehaviorRow(val labelRes: Int) {
    VolumeKeysTurnPages(R.string.reader_behavior_volume_keys_turn_pages),
    VolumeKeysNavigatePopupTerms(R.string.reader_behavior_volume_keys_navigate_popup_terms),
    VolumeKeysSeekSasayaki(R.string.reader_behavior_volume_keys_seek_sasayaki),
    ReverseVolumeKeyDirection(R.string.reader_behavior_reverse_volume_key_direction),
    KeepScreenOn(R.string.reader_behavior_keep_screen_on),
    LockCurrentOrientation(R.string.reader_behavior_lock_current_orientation),
    OpenLastReadBookOnLaunch(R.string.reader_behavior_open_last_read_book_on_launch),
    AutomaticallyCheckForUpdates(R.string.reader_behavior_auto_check_updates),
}

@Composable
private fun BehaviorSettingsCard(content: @Composable () -> Unit) {
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

@Composable
private fun BehaviorSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        headlineContent = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
    )
}

@Composable
private fun BehaviorDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
