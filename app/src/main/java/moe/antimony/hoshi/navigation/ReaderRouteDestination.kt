package moe.antimony.hoshi.navigation

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.reader.ReaderWebView
import kotlinx.coroutines.launch
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import moe.antimony.hoshi.features.sync.SyncDirection
import moe.antimony.hoshi.features.sync.SyncResult
import moe.antimony.hoshi.features.sync.SyncSettings

@Composable
internal fun ReaderRouteDestination(
    bookId: String,
    stateHolder: ReaderRouteStateHolder,
    readerSettings: ReaderSettings,
    onReaderSettingsChange: (ReaderSettings) -> Unit,
    onReaderKeyEventHandlerChange: (((KeyEvent) -> Boolean)?) -> Unit,
    onBookmarkSaved: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appContainer = LocalHoshiAppContainer.current
    val syncSettings by appContainer.syncSettingsRepository.settings.collectAsState(initial = SyncSettings())
    val sasayakiSettings by appContainer.sasayakiSettingsRepository.settings.collectAsState(initial = SasayakiSettings())
    val bookmarkScope = rememberCoroutineScope()
    var reloadKey by remember(bookId) { mutableIntStateOf(0) }
    var pendingExport by remember(bookId) { mutableStateOf(false) }
    var exportJob by remember(bookId) { mutableStateOf<Job?>(null) }
    var saveJob by remember(bookId) { mutableStateOf<Job?>(null) }
    val systemDarkTheme = isSystemInDarkTheme()
    val readerLoadingBackground = Modifier.background(
        Color(readerSettings.backgroundColor(systemDarkTheme)),
    )
    val routeState by produceState<ReaderRouteLoadState>(
        initialValue = ReaderRouteLoadState.Loading,
        key1 = bookId,
        key2 = stateHolder,
        key3 = reloadKey,
    ) {
        value = stateHolder.load(bookId) { entry ->
            if (syncSettings.isReaderAutoSyncEnabled()) {
                runCatching {
                    appContainer.syncManager.syncBook(
                        entry = entry,
                        direction = null,
                        syncStats = readerSettings.statisticsSyncEnabled,
                        statsSyncMode = readerSettings.statisticsSyncMode,
                        syncAudioBook = sasayakiSettings.enabled && sasayakiSettings.syncEnabled,
                        importOnly = true,
                    )
                }
            }
        }
    }

    fun launchExport(entry: BookEntry) {
        bookmarkScope.launch {
            saveJob?.join()
            runCatching {
                appContainer.syncManager.syncBook(
                    entry = entry,
                    direction = SyncDirection.ExportToTtu,
                    syncStats = readerSettings.statisticsSyncEnabled,
                    statsSyncMode = readerSettings.statisticsSyncMode,
                    syncAudioBook = sasayakiSettings.enabled && sasayakiSettings.syncEnabled,
                )
            }
        }
    }

    fun scheduleExport(entry: BookEntry) {
        if (!syncSettings.isReaderAutoSyncEnabled()) return
        pendingExport = true
        if (exportJob?.isActive == true) return
        exportJob = bookmarkScope.launch {
            delay(ReaderAutoSyncDebounceMillis)
            if (pendingExport) {
                pendingExport = false
                launchExport(entry)
            }
            exportJob = null
        }
    }

    fun flushExport(entry: BookEntry) {
        if (!syncSettings.isReaderAutoSyncEnabled() || !pendingExport) return
        pendingExport = false
        exportJob?.cancel()
        exportJob = null
        launchExport(entry)
    }

    fun importOnForeground(entry: BookEntry) {
        if (!syncSettings.isReaderAutoSyncEnabled()) return
        bookmarkScope.launch {
            val result = runCatching {
                appContainer.syncManager.syncBook(
                    entry = entry,
                    direction = null,
                    syncStats = readerSettings.statisticsSyncEnabled,
                    statsSyncMode = readerSettings.statisticsSyncMode,
                    syncAudioBook = sasayakiSettings.enabled && sasayakiSettings.syncEnabled,
                    importOnly = true,
                )
            }.getOrNull()
            if (result is SyncResult.Imported) {
                reloadKey += 1
            }
        }
    }

    when (val state = routeState) {
        ReaderRouteLoadState.Loading -> Box(
            modifier = modifier
                .fillMaxSize()
                .then(readerLoadingBackground),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        is ReaderRouteLoadState.Error -> Box(
            modifier = modifier
                .fillMaxSize()
                .then(readerLoadingBackground),
            contentAlignment = Alignment.Center,
        ) {
            Text(state.message)
        }
        is ReaderRouteLoadState.Ready -> ReaderWebView(
            book = state.book,
            bookRoot = state.bookRoot,
            initialChapterIndex = state.bookmark?.chapterIndex ?: 0,
            initialProgress = state.bookmark?.progress ?: 0.0,
            readerSettings = readerSettings,
            onReaderSettingsChange = onReaderSettingsChange,
            onReaderKeyEventHandlerChange = onReaderKeyEventHandlerChange,
            onSaveBookmark = { chapterIndex, progress, statistics ->
                val job = bookmarkScope.launch {
                    stateHolder.saveBookmark(
                        state = state,
                        chapterIndex = chapterIndex,
                        progress = progress,
                        statistics = statistics,
                        onBookmarkSaved = onBookmarkSaved,
                    )
                }
                saveJob = job
                scheduleExport(state.entry)
            },
            onFlushAutoSyncExport = { flushExport(state.entry) },
            onForegroundAutoSyncImport = { importOnForeground(state.entry) },
            onClose = onClose,
            modifier = modifier.fillMaxSize(),
        )
    }
}

private fun SyncSettings.isReaderAutoSyncEnabled(): Boolean =
    enabled && autoSyncEnabled

private const val ReaderAutoSyncDebounceMillis = 30_000L
