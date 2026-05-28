package moe.antimony.hoshi.features.sasayaki

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.settings.collectAsLoadedSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SasayakiSettingsView(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appContainer = LocalHoshiAppContainer.current
    val scope = rememberCoroutineScope()
    val repository = appContainer.sasayakiSettingsRepository
    val syncSettings = appContainer.syncSettingsRepository.settings.collectAsLoadedSettings()
    val settings = repository.settings.collectAsLoadedSettings()
    var skipActionMenuExpanded by remember { mutableStateOf(false) }

    fun save(next: SasayakiSettings) {
        scope.launch {
            repository.update { next }
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
                title = { Text(stringResource(R.string.sasayaki_title), fontWeight = FontWeight.SemiBold) },
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
        ) {
            item {
                val loadedSettings = settings ?: return@item
                val loadedSyncSettings = syncSettings ?: return@item
                SettingsCard {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(stringResource(R.string.action_enable)) },
                        trailingContent = {
                            Switch(
                                checked = loadedSettings.enabled,
                                onCheckedChange = { save(loadedSettings.copy(enabled = it)) },
                            )
                        },
                    )
                    if (loadedSettings.enabled) {
                        SettingsDivider()
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(stringResource(R.string.sasayaki_copy_audiobook_to_storage)) },
                            supportingContent = { Text(stringResource(R.string.sasayaki_copy_audiobook_to_storage_help)) },
                            trailingContent = {
                                Switch(
                                    checked = loadedSettings.copyAudiobookToPrivateStorage,
                                    onCheckedChange = { save(loadedSettings.copy(copyAudiobookToPrivateStorage = it)) },
                                )
                            },
                        )
                        if (loadedSettings.enabled && loadedSyncSettings.enabled) {
                            SettingsDivider()
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = { Text(stringResource(R.string.sync_ttu_sync)) },
                                trailingContent = {
                                    Switch(
                                        checked = loadedSettings.syncEnabled,
                                        onCheckedChange = { save(loadedSettings.copy(syncEnabled = it)) },
                                    )
                                },
                            )
                        }
                        SettingsDivider()
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(stringResource(R.string.sasayaki_show_bottom_playback_controls)) },
                            supportingContent = { Text(stringResource(R.string.sasayaki_show_bottom_playback_controls_help)) },
                            trailingContent = {
                                Switch(
                                    checked = loadedSettings.showReaderBottomPlaybackControls,
                                    onCheckedChange = {
                                        save(loadedSettings.copy(showReaderBottomPlaybackControls = it))
                                    },
                                )
                            },
                        )
                        if (loadedSettings.showReaderBottomPlaybackControls) {
                            SettingsDivider()
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = { Text(stringResource(R.string.sasayaki_reverse_vertical_skip_buttons)) },
                                supportingContent = { Text(stringResource(R.string.sasayaki_reverse_vertical_skip_buttons_help)) },
                                trailingContent = {
                                    Switch(
                                        checked = loadedSettings.reverseVerticalReaderSkipButtons,
                                        onCheckedChange = {
                                            save(loadedSettings.copy(reverseVerticalReaderSkipButtons = it))
                                        },
                                    )
                                },
                            )
                        }
                        SettingsDivider()
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(stringResource(R.string.sasayaki_skip_action)) },
                            trailingContent = {
                                Box {
                                    TextButton(onClick = { skipActionMenuExpanded = true }) {
                                        Text(loadedSettings.readerSkipButtonAction.labelText())
                                    }
                                    DropdownMenu(
                                        expanded = skipActionMenuExpanded,
                                        onDismissRequest = { skipActionMenuExpanded = false },
                                    ) {
                                        SasayakiReaderSkipButtonAction.entries.forEach { action ->
                                            DropdownMenuItem(
                                                text = { Text(action.labelText()) },
                                                onClick = {
                                                    skipActionMenuExpanded = false
                                                    save(loadedSettings.copy(readerSkipButtonAction = action))
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
                            headlineContent = { Text(stringResource(R.string.sasayaki_auto_scroll)) },
                            trailingContent = {
                                Switch(
                                    checked = loadedSettings.autoScroll,
                                    onCheckedChange = { save(loadedSettings.copy(autoScroll = it)) },
                                )
                            },
                        )
                        SettingsDivider()
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(stringResource(R.string.sasayaki_auto_pause_on_lookup)) },
                            trailingContent = {
                                Switch(
                                    checked = loadedSettings.autoPause,
                                    onCheckedChange = { save(loadedSettings.copy(autoPause = it)) },
                                )
                            },
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.sasayaki_settings_description),
                    color = colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                )
            }
        }
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

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
