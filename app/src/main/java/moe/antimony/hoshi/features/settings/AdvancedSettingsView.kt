package moe.antimony.hoshi.features.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.anki.AnkiConnectView
import moe.antimony.hoshi.features.audio.AudioSettingsView
import moe.antimony.hoshi.features.backup.BackupSettingsView
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.reader.ReaderStatisticsSettingsView
import moe.antimony.hoshi.features.sasayaki.SasayakiSettingsView
import moe.antimony.hoshi.features.sync.SyncSettingsView
import moe.antimony.hoshi.features.wallpaper.BookCoverWallpaperSettingsView

@Composable
fun AdvancedSettingsView(
    readerSettings: ReaderSettings,
    onReaderSettingsChange: (ReaderSettings) -> Unit,
    onClose: () -> Unit,
    onBooksRestored: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var destination by remember { mutableStateOf<AdvancedDestination?>(null) }
    if (destination == AdvancedDestination.Audio) {
        AudioSettingsView(
            onClose = { destination = null },
            modifier = modifier,
        )
        return
    }
    if (destination == AdvancedDestination.Statistics) {
        ReaderStatisticsSettingsView(
            settings = readerSettings,
            onSettingsChange = onReaderSettingsChange,
            onClose = { destination = null },
            modifier = modifier,
        )
        return
    }
    if (destination == AdvancedDestination.Sasayaki) {
        SasayakiSettingsView(
            onClose = { destination = null },
            modifier = modifier,
        )
        return
    }
    if (destination == AdvancedDestination.Backup) {
        BackupSettingsView(
            onClose = { destination = null },
            onBooksRestored = onBooksRestored,
            modifier = modifier,
        )
        return
    }
    if (destination == AdvancedDestination.Syncing) {
        SyncSettingsView(
            onClose = { destination = null },
            modifier = modifier,
        )
        return
    }
    if (destination == AdvancedDestination.AnkiConnect) {
        AnkiConnectView(
            onClose = { destination = null },
            modifier = modifier,
        )
        return
    }
    if (destination == AdvancedDestination.BookCoverWallpaper) {
        BookCoverWallpaperSettingsView(
            onClose = { destination = null },
            modifier = modifier,
        )
        return
    }

    val colorScheme = MaterialTheme.colorScheme
    SettingsDetailScaffold(
        title = stringResource(R.string.settings_advanced),
        onClose = onClose,
        modifier = modifier.fillMaxSize(),
        containerColor = colorScheme.background,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            advancedSettingsSections().forEach { section ->
                item {
                    GroupCard {
                        section.rows.forEachIndexed { index, row ->
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                leadingContent = { Icon(row.icon.imageVector(), contentDescription = null) },
                                headlineContent = { Text(stringResource(row.titleRes)) },
                                supportingContent = row.subtitleRes?.let { subtitleRes -> { Text(stringResource(subtitleRes)) } },
                                modifier = Modifier.clickable { destination = row.destination },
                            )
                            if (index != section.rows.lastIndex) {
                                GroupDivider()
                            }
                        }
                    }
                }
            }
            if (isAppLanguagePickerSupported()) {
                item {
                    val context = LocalContext.current
                    GroupCard {
                        AppLanguageListItem(
                            selected = context.currentAppLanguageMode(),
                            onSelected = { mode -> context.setAppLanguageMode(mode) },
                        )
                    }
                }
            }
        }
    }
}

internal enum class AdvancedDestination {
    Audio,
    Statistics,
    Sasayaki,
    Backup,
    Syncing,
    AnkiConnect,
    BookCoverWallpaper,
}

internal enum class AdvancedSettingsIcon {
    Speaker,
    Chart,
    Waveform,
    Cloud,
    AnkiConnect,
    ExternalDrive,
    Wallpaper,
}

internal data class AdvancedSettingsRow(
    @param:StringRes val titleRes: Int,
    val destination: AdvancedDestination,
    val icon: AdvancedSettingsIcon,
    @param:StringRes val subtitleRes: Int? = null,
)

internal data class AdvancedSettingsSection(
    val rows: List<AdvancedSettingsRow>,
)

internal fun advancedSettingsSections(): List<AdvancedSettingsSection> =
    listOf(
        AdvancedSettingsSection(
            rows = listOf(
                AdvancedSettingsRow(
                    titleRes = R.string.advanced_audio,
                    destination = AdvancedDestination.Audio,
                    icon = AdvancedSettingsIcon.Speaker,
                ),
                AdvancedSettingsRow(
                    titleRes = R.string.advanced_statistics,
                    destination = AdvancedDestination.Statistics,
                    icon = AdvancedSettingsIcon.Chart,
                    subtitleRes = R.string.advanced_statistics_subtitle,
                ),
                AdvancedSettingsRow(
                    titleRes = R.string.advanced_sasayaki_audiobooks,
                    destination = AdvancedDestination.Sasayaki,
                    icon = AdvancedSettingsIcon.Waveform,
                    subtitleRes = R.string.advanced_sasayaki_subtitle,
                ),
            ),
        ),
        AdvancedSettingsSection(
            rows = listOf(
                AdvancedSettingsRow(
                    titleRes = R.string.sync_ttu_sync,
                    destination = AdvancedDestination.Syncing,
                    icon = AdvancedSettingsIcon.Cloud,
                ),
                AdvancedSettingsRow(
                    titleRes = R.string.anki_connect_use,
                    destination = AdvancedDestination.AnkiConnect,
                    icon = AdvancedSettingsIcon.AnkiConnect,
                ),
            ),
        ),
        AdvancedSettingsSection(
            rows = listOf(
                AdvancedSettingsRow(
                    titleRes = R.string.settings_book_cover_wallpaper,
                    destination = AdvancedDestination.BookCoverWallpaper,
                    icon = AdvancedSettingsIcon.Wallpaper,
                    subtitleRes = R.string.settings_book_cover_wallpaper_subtitle,
                ),
            ),
        ),
        AdvancedSettingsSection(
            rows = listOf(
                AdvancedSettingsRow(
                    titleRes = R.string.settings_backup,
                    destination = AdvancedDestination.Backup,
                    icon = AdvancedSettingsIcon.ExternalDrive,
                ),
            ),
        ),
    )

private fun AdvancedSettingsIcon.imageVector(): ImageVector =
    when (this) {
        AdvancedSettingsIcon.Speaker -> Icons.AutoMirrored.Rounded.VolumeUp
        AdvancedSettingsIcon.Chart -> Icons.AutoMirrored.Rounded.ShowChart
        AdvancedSettingsIcon.Waveform -> Icons.Rounded.GraphicEq
        AdvancedSettingsIcon.Cloud -> Icons.Rounded.Cloud
        AdvancedSettingsIcon.AnkiConnect -> Icons.Rounded.Link
        AdvancedSettingsIcon.ExternalDrive -> Icons.Rounded.Storage
        AdvancedSettingsIcon.Wallpaper -> Icons.Rounded.Wallpaper
    }
