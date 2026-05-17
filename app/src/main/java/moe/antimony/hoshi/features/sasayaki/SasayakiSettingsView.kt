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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.antimony.hoshi.LocalHoshiAppContainer
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
    var mediaControlsMenuExpanded by remember { mutableStateOf(false) }

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
                title = { Text("Sasayaki", fontWeight = FontWeight.SemiBold) },
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
        ) {
            item {
                val loadedSettings = settings ?: return@item
                val loadedSyncSettings = syncSettings ?: return@item
                SettingsCard {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text("Enable") },
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
                            headlineContent = { Text("Copy Audiobook to App Storage") },
                            supportingContent = { Text("Keep a private copy instead of linking to the selected external media file") },
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
                                headlineContent = { Text("ッツ Sync") },
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
                            headlineContent = { Text("Show Skip Buttons") },
                            supportingContent = { Text("Add rewind and fast-forward buttons to the bottom of the reader.") },
                            trailingContent = {
                                Switch(
                                    checked = loadedSettings.showReaderSkipButtons,
                                    onCheckedChange = { save(loadedSettings.copy(showReaderSkipButtons = it)) },
                                )
                            },
                        )
                        SettingsDivider()
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text("System Media Controls") },
                            supportingContent = { Text("Use Auto unless media notifications cause device-specific system issues.") },
                            trailingContent = {
                                Box {
                                    TextButton(onClick = { mediaControlsMenuExpanded = true }) {
                                        Text(loadedSettings.systemMediaControls.label)
                                    }
                                    DropdownMenu(
                                        expanded = mediaControlsMenuExpanded,
                                        onDismissRequest = { mediaControlsMenuExpanded = false },
                                    ) {
                                        SasayakiSystemMediaControlsMode.entries.forEach { mode ->
                                            DropdownMenuItem(
                                                text = { Text(mode.label) },
                                                onClick = {
                                                    mediaControlsMenuExpanded = false
                                                    save(loadedSettings.copy(systemMediaControls = mode))
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
                            headlineContent = { Text("Skip Action") },
                            trailingContent = {
                                Box {
                                    TextButton(onClick = { skipActionMenuExpanded = true }) {
                                        Text(loadedSettings.readerSkipButtonAction.label)
                                    }
                                    DropdownMenu(
                                        expanded = skipActionMenuExpanded,
                                        onDismissRequest = { skipActionMenuExpanded = false },
                                    ) {
                                        SasayakiReaderSkipButtonAction.entries.forEach { action ->
                                            DropdownMenuItem(
                                                text = { Text(action.label) },
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
                            headlineContent = { Text("Auto-Scroll") },
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
                            headlineContent = { Text("Auto-Pause on Lookup") },
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
                    text = "Sasayaki syncs an audiobook with reader text. Long press a book and choose Match Sasayaki, select the matching .srt file, then open the reader and load an .mp3 or .m4b audiobook.",
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
