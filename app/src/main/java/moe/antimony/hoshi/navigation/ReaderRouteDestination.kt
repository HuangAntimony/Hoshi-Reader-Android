package moe.antimony.hoshi.navigation

import android.util.Log
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import moe.antimony.hoshi.R
import moe.antimony.hoshi.content.ContentLanguageProfile
import moe.antimony.hoshi.features.reader.ReaderLoadingPage
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.reader.ReaderWebView
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import moe.antimony.hoshi.LocalHoshiUiDependencies
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.features.settings.collectAsLoadedSettings
import moe.antimony.hoshi.features.sync.SyncDirection
import moe.antimony.hoshi.features.sync.SyncResult
import moe.antimony.hoshi.features.wallpaper.BookCoverWallpaperViewModel

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
    val appContainer = LocalHoshiUiDependencies.current
    val bookCoverWallpaperViewModel: BookCoverWallpaperViewModel = hiltViewModel()
    val bookCoverSnackbarHostState = remember { SnackbarHostState() }
    val bookCoverPublishFailedMessage = stringResource(R.string.book_cover_wallpaper_publish_failed)
    val syncSettings = appContainer.syncSettingsRepository.settings.collectAsLoadedSettings()
    val sasayakiSettings = appContainer.sasayakiSettingsRepository.settings.collectAsLoadedSettings()
    val autoSyncState = ReaderRouteAutoSyncState(
        syncSettings = syncSettings,
        sasayakiSettings = sasayakiSettings,
    )
    val bookmarkScope = rememberCoroutineScope()
    var reloadKey by remember(bookId) { mutableIntStateOf(0) }
    val autoSyncExportController = remember(bookId, appContainer) {
        ReaderAutoSyncExportController(appContainer.appScope)
    }
    val systemDarkTheme = isSystemInDarkTheme()
    val readerLoadingBackground = Modifier.background(
        Color(readerSettings.backgroundColor(systemDarkTheme)),
    )
    val routeState by produceState<ReaderRouteRenderState>(
        ReaderRouteRenderState.Loading,
        bookId,
        stateHolder,
        reloadKey,
    ) {
        val loaded = stateHolder.load(bookId) { entry ->
            val initialAutoSyncState = ReaderRouteAutoSyncState(
                syncSettings = syncSettings ?: appContainer.syncSettingsRepository.settings.first(),
                sasayakiSettings = sasayakiSettings ?: appContainer.sasayakiSettingsRepository.settings.first(),
            )
            if (initialAutoSyncState.shouldSyncOnOpen) {
                runCatching {
                    appContainer.syncManager.syncBook(
                        entry = entry,
                        direction = null,
                        syncStats = readerSettings.statisticsSyncEnabled,
                        statsSyncMode = readerSettings.statisticsSyncMode,
                        syncAudioBook = initialAutoSyncState.shouldSyncAudioBook,
                        importOnly = true,
                    )
                }
            }
        }
        value = loaded.activateProfileAndPrepareRender(
            activateForBook = { metadata ->
                appContainer.profileActivationService.activateForBook(metadata).let { profile ->
                    ContentLanguageProfile.fromDictionaryLanguageId(profile.dictionaryLanguageId)
                        ?: ContentLanguageProfile.Default
                }
            },
            clearLoadedProfile = appContainer.profileActivationService::clearLoadedProfile,
            loadReaderSettings = { appContainer.readerSettingsRepository.settings.first() },
            loadGeneration = reloadKey,
        )
    }
    val bookCoverPublicationCoordinator = remember { ReaderBookCoverPublicationCoordinator() }
    val bookCoverPublicationEvent = when (val state = routeState) {
        ReaderRouteRenderState.Loading,
        is ReaderRouteRenderState.Error,
        -> ReaderBookCoverPublicationEvent.NotReady
        is ReaderRouteRenderState.Ready -> ReaderBookCoverPublicationEvent.Ready(
            bookId = state.loadState.entry.metadata.id,
            coverPath = state.loadState.bookCoverFile?.absolutePath,
            loadGeneration = state.loadGeneration,
        )
    }
    LaunchedEffect(bookCoverPublicationEvent) {
        val readyState = (routeState as? ReaderRouteRenderState.Ready)?.loadState
        if (readyState != null && bookCoverPublicationCoordinator.shouldPublish(bookCoverPublicationEvent)) {
            val result = bookCoverWallpaperViewModel.publishCurrentCover(readyState.bookCoverFile)
            if (result.hasFailures) {
                bookCoverSnackbarHostState.showSnackbar(bookCoverPublishFailedMessage)
            }
        } else if (readyState == null) {
            bookCoverPublicationCoordinator.shouldPublish(ReaderBookCoverPublicationEvent.NotReady)
        }
    }

    suspend fun exportBook(entry: BookEntry) {
        runCatching {
            val currentSyncSettings = syncSettings ?: appContainer.syncSettingsRepository.settings.first()
            appContainer.syncManager.syncBook(
                entry = entry,
                direction = SyncDirection.ExportToTtu,
                syncStats = readerSettings.statisticsSyncEnabled,
                statsSyncMode = readerSettings.statisticsSyncMode,
                syncAudioBook = autoSyncState.shouldSyncAudioBook,
                syncBookData = currentSyncSettings.uploadBooks,
            )
        }.onSuccess { result ->
            Log.d(ReaderAutoSyncLogTag, "Reader auto export finished: ${result::class.java.simpleName}")
        }.onFailure { error ->
            Log.w(ReaderAutoSyncLogTag, "Reader auto export failed.", error)
        }
    }

    fun scheduleExport(entry: BookEntry) {
        autoSyncExportController.scheduleExport(autoSyncState.isReaderAutoSyncEnabled) {
            exportBook(entry)
        }
    }

    fun flushExport() {
        autoSyncExportController.flushExport(autoSyncState.isReaderAutoSyncEnabled)
    }

    fun importOnForeground(entry: BookEntry) {
        if (!autoSyncState.isReaderAutoSyncEnabled) return
        bookmarkScope.launch {
            val result = runCatching {
                appContainer.syncManager.syncBook(
                    entry = entry,
                    direction = null,
                    syncStats = readerSettings.statisticsSyncEnabled,
                    statsSyncMode = readerSettings.statisticsSyncMode,
                    syncAudioBook = autoSyncState.shouldSyncAudioBook,
                    importOnly = true,
                )
            }.getOrNull()
            if (result is SyncResult.Imported) {
                reloadKey += 1
            }
        }
    }

    when (val state = routeState) {
        ReaderRouteRenderState.Loading -> ReaderLoadingPage(
            backgroundColor = Color(readerSettings.backgroundColor(systemDarkTheme)),
            modifier = modifier.fillMaxSize(),
        )
        is ReaderRouteRenderState.Error -> Box(
            modifier = modifier
                .fillMaxSize()
                .then(readerLoadingBackground),
            contentAlignment = Alignment.Center,
        ) {
            Text(state.message)
        }
        is ReaderRouteRenderState.Ready -> {
            val readyState = state.loadState
            var routeReaderSettings by remember(readyState.entry.metadata.id, state.readerSettings) {
                mutableStateOf(state.readerSettings)
            }
            LaunchedEffect(appContainer.readerSettingsRepository, readyState.entry.metadata.id, state.readerSettings) {
                appContainer.readerSettingsRepository.settings.collect { settings ->
                    routeReaderSettings = settings
                }
            }
            Box(modifier = modifier.fillMaxSize()) {
                ReaderWebView(
                    bookId = bookId,
                    book = readyState.book,
                    bookEntry = readyState.entry,
                    bookRoot = readyState.bookRoot,
                    bookCoverFile = readyState.bookCoverFile,
                    initialChapterIndex = readyState.bookmark?.chapterIndex ?: 0,
                    initialProgress = readyState.bookmark?.progress ?: 0.0,
                    readerSettings = routeReaderSettings,
                    onReaderSettingsChange = { settings ->
                        routeReaderSettings = settings
                        onReaderSettingsChange(settings)
                    },
                    onReaderKeyEventHandlerChange = onReaderKeyEventHandlerChange,
                    onSaveBookmark = { chapterIndex, progress, statistics ->
                        autoSyncExportController.launchSave {
                            stateHolder.saveBookmark(
                                state = readyState,
                                chapterIndex = chapterIndex,
                                progress = progress,
                                statistics = statistics,
                                onBookmarkSaved = onBookmarkSaved,
                            )
                        }
                        scheduleExport(readyState.entry)
                    },
                    onFlushAutoSyncExport = ::flushExport,
                    onForegroundAutoSyncImport = { importOnForeground(readyState.entry) },
                    contentLanguageProfile = state.contentLanguageProfile,
                    onClose = onClose,
                    modifier = Modifier.fillMaxSize(),
                )
                SnackbarHost(
                    hostState = bookCoverSnackbarHostState,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

internal sealed interface ReaderRouteRenderState {
    data object Loading : ReaderRouteRenderState

    data class Ready(
        val loadState: ReaderRouteLoadState.Ready,
        val readerSettings: ReaderSettings,
        val contentLanguageProfile: ContentLanguageProfile,
        val loadGeneration: Int = 0,
    ) : ReaderRouteRenderState

    data class Error(
        val message: String,
    ) : ReaderRouteRenderState
}

internal suspend fun ReaderRouteLoadState.activateProfileAndPrepareRender(
    activateForBook: (BookMetadata) -> ContentLanguageProfile,
    clearLoadedProfile: () -> Unit,
    loadReaderSettings: suspend () -> ReaderSettings,
    loadGeneration: Int = 0,
): ReaderRouteRenderState =
    when (this) {
        is ReaderRouteLoadState.Ready -> {
            val contentLanguageProfile = activateForBook(entry.metadata)
            ReaderRouteRenderState.Ready(
                loadState = this,
                readerSettings = loadReaderSettings(),
                contentLanguageProfile = contentLanguageProfile,
                loadGeneration = loadGeneration,
            )
        }
        is ReaderRouteLoadState.Error -> {
            clearLoadedProfile()
            ReaderRouteRenderState.Error(message)
        }
        ReaderRouteLoadState.Loading -> ReaderRouteRenderState.Loading
    }

private const val ReaderAutoSyncLogTag = "HoshiReaderSync"
