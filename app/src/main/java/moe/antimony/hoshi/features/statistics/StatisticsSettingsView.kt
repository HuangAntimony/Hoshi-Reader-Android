package moe.antimony.hoshi.features.statistics

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.sync.StatisticsSyncMode
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import androidx.compose.runtime.DisposableEffect
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.antimony.hoshi.ui.asString

@Composable
internal fun StatisticsSettingsView(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StatisticsSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = StatisticsLifecycleReloader(viewModel::reload)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    state.settings?.let { settings ->
        StatisticsSettingsContent(
            settings = settings,
            syncEnabled = state.syncEnabled,
            archivedBookCount = state.archivedBookCount,
            isWorking = state.isWorking,
            onSettingsChange = viewModel::update,
            onClearArchive = viewModel::clearArchive,
            onClose = onClose,
            modifier = modifier,
        )
    }
    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            text = { Text(error.asString()) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissError) { Text(stringResource(R.string.action_ok)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatisticsSettingsContent(
    settings: ReaderSettings,
    onSettingsChange: ((ReaderSettings) -> ReaderSettings) -> Unit,
    syncEnabled: Boolean,
    archivedBookCount: Int,
    isWorking: Boolean,
    onClearArchive: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showClearArchiveConfirmation by remember { mutableStateOf(false) }
    var syncModeMenuExpanded by remember { mutableStateOf(false) }
    var showResetTimePicker by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val is24Hour = DateFormat.is24HourFormat(context)
    val resetTimeText = remember(settings.statisticsResetMinutes, locale, is24Hour) {
        val pattern = DateFormat.getBestDateTimePattern(
            locale,
            if (is24Hour) "Hm" else "hm",
        )
        LocalTime.of(
            settings.statisticsResetMinutes / 60,
            settings.statisticsResetMinutes % 60,
        ).format(DateTimeFormatter.ofPattern(pattern, locale))
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
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item(key = "autostart") {
                StatisticsSettingsSection(footer = stringResource(R.string.reader_statistics_settings_hint)) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = {
                            Text(stringResource(R.string.reader_statistics_autostart_on_book_open))
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.statisticsAutostartOnBookOpen,
                                onCheckedChange = {
                                    onSettingsChange { current -> current.copy(statisticsAutostartOnBookOpen = it) }
                                },
                            )
                        },
                    )
                    StatisticsSettingsDivider()
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = {
                            Text(stringResource(R.string.reader_statistics_autostart_on_page_turn))
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.statisticsAutostartOnPageTurn,
                                onCheckedChange = {
                                    onSettingsChange { current -> current.copy(statisticsAutostartOnPageTurn = it) }
                                },
                            )
                        },
                    )
                }
            }
            item(key = "reset_time") {
                StatisticsSettingsSection {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(stringResource(R.string.reader_statistics_reset_time)) },
                        trailingContent = {
                            TextButton(onClick = { showResetTimePicker = true }) {
                                Text(resetTimeText)
                            }
                        },
                        modifier = Modifier.clickable { showResetTimePicker = true },
                    )
                }
            }
            if (syncEnabled) {
                item(key = "sync") {
                    StatisticsSettingsSection(
                        title = stringResource(R.string.statistics_sync_heading),
                        footer = stringResource(R.string.reader_statistics_sync_behaviour_hint),
                    ) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(stringResource(R.string.sync_ttu_sync)) },
                            trailingContent = {
                                Switch(
                                    checked = settings.statisticsSyncEnabled,
                                    onCheckedChange = {
                                        onSettingsChange { current -> current.copy(statisticsSyncEnabled = it) }
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
                                                    onSettingsChange { current -> current.copy(statisticsSyncMode = mode) }
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
            if (archivedBookCount > 0) {
                item(key = "archive") {
                    StatisticsSettingsSection(
                        title = stringResource(R.string.statistics_archive_heading),
                        footer = pluralStringResource(R.plurals.statistics_archived_book_count, archivedBookCount, archivedBookCount),
                    ) {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.statistics_clear_archive)) },
                            modifier = Modifier.clickable(enabled = !isWorking) { showClearArchiveConfirmation = true },
                            colors = ListItemDefaults.colors(
                                containerColor = Color.Transparent,
                                headlineColor = colorScheme.error,
                            ),
                        )
                    }
                }
            }
        }
    }
    if (showClearArchiveConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearArchiveConfirmation = false },
            title = { Text(stringResource(R.string.statistics_clear_archive)) },
            text = { Text(stringResource(R.string.statistics_clear_archive_confirmation, archivedBookCount)) },
            confirmButton = {
                TextButton(enabled = !isWorking, onClick = {
                    showClearArchiveConfirmation = false
                    onClearArchive()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearArchiveConfirmation = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    if (showResetTimePicker) {
        StatisticsResetTimePickerDialog(
            resetMinutes = settings.statisticsResetMinutes,
            onConfirm = { resetMinutes ->
                showResetTimePicker = false
                onSettingsChange { current -> current.copy(statisticsResetMinutes = resetMinutes) }
            },
            onDismiss = { showResetTimePicker = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatisticsResetTimePickerDialog(
    resetMinutes: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val timePickerState = rememberTimePickerState(
        initialHour = resetMinutes / 60,
        initialMinute = resetMinutes % 60,
        is24Hour = DateFormat.is24HourFormat(LocalContext.current),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(timePickerState.hour * 60 + timePickerState.minute) }) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        text = { TimePicker(state = timePickerState) },
    )
}

@Composable
private fun StatisticsSettingsSection(
    title: String? = null,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        title?.let { StatisticsSectionHeading(it, Modifier.padding(horizontal = 16.dp)) }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 0.dp,
        ) {
            Column(content = { content() })
        }
        footer?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
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
