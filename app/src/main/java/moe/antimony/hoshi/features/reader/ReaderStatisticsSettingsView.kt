package moe.antimony.hoshi.features.reader

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.antimony.hoshi.LocalHoshiUiDependencies
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.settings.collectAsLoadedSettings
import moe.antimony.hoshi.features.sync.StatisticsSyncMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderStatisticsSettingsView(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appContainer = LocalHoshiUiDependencies.current
    val syncSettings = appContainer.syncSettingsRepository.settings.collectAsLoadedSettings()
    var autostartMenuExpanded by remember { mutableStateOf(false) }
    var syncModeMenuExpanded by remember { mutableStateOf(false) }
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
                title = { Text(stringResource(R.string.reader_statistics), fontWeight = FontWeight.SemiBold) },
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
        ) {
            item {
                val loadedSyncSettings = syncSettings ?: return@item
                StatisticsSettingsCard {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(stringResource(R.string.action_enable)) },
                        trailingContent = {
                            Switch(
                                checked = settings.enableStatistics,
                                onCheckedChange = { enabled ->
                                    onSettingsChange(settings.withStatisticsEnabled(enabled))
                                },
                            )
                        },
                    )
                    if (settings.enableStatistics) {
                        StatisticsSettingsDivider()
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(stringResource(R.string.reader_statistics_show_tab)) },
                            trailingContent = {
                                Switch(
                                    checked = settings.showStatisticsTab,
                                    onCheckedChange = {
                                        onSettingsChange(settings.copy(showStatisticsTab = it))
                                    },
                                )
                            },
                        )
                        StatisticsSettingsDivider()
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(stringResource(R.string.reader_statistics_autostart)) },
                            trailingContent = {
                                Box {
                                    TextButton(onClick = { autostartMenuExpanded = true }) {
                                        Text(stringResource(settings.statisticsAutostartMode.labelRes))
                                    }
                                    DropdownMenu(
                                        expanded = autostartMenuExpanded,
                                        onDismissRequest = { autostartMenuExpanded = false },
                                    ) {
                                        StatisticsAutostartMode.entries.forEach { mode ->
                                            DropdownMenuItem(
                                                text = { Text(stringResource(mode.labelRes)) },
                                                onClick = {
                                                    autostartMenuExpanded = false
                                                    onSettingsChange(settings.copy(statisticsAutostartMode = mode))
                                                },
                                            )
                                        }
                                    }
                                }
                            },
                        )
                        if (loadedSyncSettings.enabled) {
                            StatisticsSettingsDivider()
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = { Text(stringResource(R.string.sync_ttu_sync)) },
                                trailingContent = {
                                    Switch(
                                        checked = settings.statisticsSyncEnabled,
                                        onCheckedChange = {
                                            onSettingsChange(settings.copy(statisticsSyncEnabled = it))
                                        },
                                    )
                                },
                            )
                            StatisticsSettingsDivider()
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = { Text(stringResource(R.string.reader_statistics_sync_behaviour)) },
                                trailingContent = {
                                    Box {
                                        TextButton(onClick = { syncModeMenuExpanded = true }) {
                                            Text(stringResource(settings.statisticsSyncMode.labelRes))
                                        }
                                        DropdownMenu(
                                            expanded = syncModeMenuExpanded,
                                            onDismissRequest = { syncModeMenuExpanded = false },
                                        ) {
                                            StatisticsSyncMode.entries.forEach { mode ->
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(mode.labelRes)) },
                                                    onClick = {
                                                        syncModeMenuExpanded = false
                                                        onSettingsChange(settings.copy(statisticsSyncMode = mode))
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
                Text(
                    text = stringResource(R.string.reader_statistics_settings_hint),
                    color = colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun StatisticsSettingsCard(content: @Composable () -> Unit) {
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
private fun StatisticsSettingsDivider() {
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
