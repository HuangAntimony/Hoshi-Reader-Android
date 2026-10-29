package moe.antimony.hoshi.navigation

import android.util.Log
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import moe.antimony.hoshi.ui.theme.LocalSystemDarkTheme
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.ErrorDetailsText
import moe.antimony.hoshi.ui.HoshiAlertDialog
import androidx.compose.material3.TextButton
import moe.antimony.hoshi.ui.withErrorDetails
import moe.antimony.hoshi.content.ContentLanguageProfile
import moe.antimony.hoshi.features.reader.ReaderLoadingPage
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.reader.ReaderWebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import moe.antimony.hoshi.LocalHoshiUiDependencies
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.features.settings.SettingsLoadState
import moe.antimony.hoshi.features.settings.collectAsSettingsLoadState
import moe.antimony.hoshi.features.sync.SyncProvider
import moe.antimony.hoshi.features.sync.SyncDirection
import moe.antimony.hoshi.features.sync.SyncResult
import moe.antimony.hoshi.features.wallpaper.BookCoverWallpaperViewModel

@Composable
internal fun ReaderRouteDestination(
    bookId: String,
    skipSyncOnOpen: Boolean = false,
    stateHolder: ReaderRouteStateHolder,
    readerSettings: ReaderSettings,
    onReaderSettingsChange: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onReaderKeyEventHandlerChange: (((KeyEvent) -> Boolean)?) -> Unit,
    onBookmarkSaved: () -> Unit,
    onBookLoaded: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appContainer = LocalHoshiUiDependencies.current
    val bookCoverWallpaperViewModel: BookCoverWallpaperViewModel = hiltViewModel()
    var operationError by remember { mutableStateOf<String?>(null) }
    operationError?.let { message ->
        HoshiAlertDialog(
            onDismissRequest = { operationError = null },
            text = { ErrorDetailsText(message) },
            confirmButton = {
                TextButton(onClick = { operationError = null }) { Text(stringResource(R.string.action_ok)) }
            },
        )
    }
    val statisticsSaveFailedMessage = stringResource(R.string.statistics_operation_failed)
    val bookCoverPublishFailedMessage = stringResource(R.string.book_cover_wallpaper_publish_failed)
    val iReaderNotSelectedMessage =
        stringResource(R.string.book_cover_wallpaper_ireader_not_selected_error)
    val syncSettings = (appContainer.syncSettingsRepository.settings.collectAsSettingsLoadState()
        as? SettingsLoadState.Loaded)?.value
    val sasayakiSettings = (appContainer.sasayakiSettingsRepository.settings.collectAsSettingsLoadState()
        as? SettingsLoadState.Loaded)?.value
    val autoSyncState = ReaderRouteAutoSyncState(
        syncSettings = syncSettings,
        sasayakiSettings = sasayakiSettings,
    )
    val bookmarkScope = rememberCoroutineScope()
    var reloadKey by remember(bookId) { mutableIntStateOf(0) }
    val autoSyncExportController = remember(bookId, appContainer) {
        ReaderAutoSyncExportController(appContainer.appScope)
    }
    val systemDarkTheme = LocalSystemDarkTheme.current
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
            if (initialAutoSyncState.syncSettings?.let { it.enabled && it.provider == SyncProvider.Gdrive } == true) {
                if (!skipSyncOnOpen) appContainer.googleDriveSyncManager.sync(entry.metadata)
                if (appContainer.bookRepository.loadMetadata(entry.root)?.epub == null) {
                    withContext(Dispatchers.Main.immediate) { onClose() }
                    error("Book was deleted.")
                }
            } else if (initialAutoSyncState.shouldSyncOnOpen) {
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
            beforeProfileActivation = onBookLoaded,
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
                val message = when (bookCoverPublishFailureMessageRes(result)) {
                    R.string.book_cover_wallpaper_ireader_not_selected_error ->
                        iReaderNotSelectedMessage
                    else -> bookCoverPublishFailedMessage
                }
                operationError = bookCoverPublishFailureMessage(result, message)
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
        if (syncSettings?.provider == SyncProvider.Gdrive) {
            appContainer.appScope.launch {
                autoSyncExportController.flushSaves()
                appContainer.googleDriveSyncManager.sync()
            }
        } else autoSyncExportController.flushExport(autoSyncState.isReaderAutoSyncEnabled)
    }

    fun importOnForeground(entry: BookEntry) {
        if (syncSettings?.provider == SyncProvider.Gdrive) {
            bookmarkScope.launch { appContainer.googleDriveSyncManager.sync(entry.metadata) }
            return
        }
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
        is ReaderRouteRenderState.Error -> ReaderOpenFailurePage(
            error = state.cause,
            onClose = onClose,
            modifier = modifier,
        )
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
                    onReaderSettingsChange = { transform ->
                        routeReaderSettings = transform(routeReaderSettings)
                        onReaderSettingsChange(transform)
                    },
                    onReaderKeyEventHandlerChange = onReaderKeyEventHandlerChange,
                    onSaveBookmark = { chapterIndex, progress, statistics ->
                        autoSyncExportController.launchSave {
                            try {
                                stateHolder.saveBookmark(
                                    state = readyState,
                                    chapterIndex = chapterIndex,
                                    progress = progress,
                                    statistics = statistics,
                                    onBookmarkSaved = onBookmarkSaved,
                                )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                bookmarkScope.launch { operationError = statisticsSaveFailedMessage.withErrorDetails(error) }
                            }
                        }.also { appContainer.bookRepository.trackReaderSave(readyState.bookRoot, it) }
                        scheduleExport(readyState.entry)
                    },
                    onSaveStatistics = { statistics ->
                        autoSyncExportController.launchSave {
                            try {
                                appContainer.bookRepository.saveTrackedSessions(readyState.bookRoot, statistics)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                bookmarkScope.launch { operationError = statisticsSaveFailedMessage.withErrorDetails(error) }
                            }
                        }.also { appContainer.bookRepository.trackReaderSave(readyState.bookRoot, it) }
                        if (readerSettings.statisticsSyncEnabled) scheduleExport(readyState.entry)
                    },
                    onFlushBookmarkSaves = autoSyncExportController::flushSaves,
                    syncProvider = syncSettings?.provider ?: SyncProvider.Gdrive,
                    onFlushAutoSyncExport = ::flushExport,
                    onForegroundAutoSyncImport = { importOnForeground(readyState.entry) },
                    contentLanguageProfile = state.contentLanguageProfile,
                    onClose = onClose,
                    modifier = Modifier.fillMaxSize(),
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

    data class Error(val cause: Throwable) : ReaderRouteRenderState
}

@Composable
internal fun ReaderOpenFailurePage(
    error: Throwable,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.reader_open_failed).withErrorDetails(error),
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onClose) {
            Text(stringResource(R.string.action_close))
        }
    }
}

internal suspend fun ReaderRouteLoadState.activateProfileAndPrepareRender(
    activateForBook: (BookMetadata) -> ContentLanguageProfile,
    clearLoadedProfile: () -> Unit,
    loadReaderSettings: suspend () -> ReaderSettings,
    loadGeneration: Int = 0,
    beforeProfileActivation: () -> Unit = {},
): ReaderRouteRenderState =
    when (this) {
        is ReaderRouteLoadState.Ready -> {
            try {
                beforeProfileActivation()
                val contentLanguageProfile = activateForBook(entry.metadata)
                ReaderRouteRenderState.Ready(
                    loadState = this,
                    readerSettings = loadReaderSettings(),
                    contentLanguageProfile = contentLanguageProfile,
                    loadGeneration = loadGeneration,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                clearLoadedProfile()
                ReaderRouteRenderState.Error(error)
            }
        }
        is ReaderRouteLoadState.Error -> {
            clearLoadedProfile()
            ReaderRouteRenderState.Error(cause)
        }
        ReaderRouteLoadState.Loading -> ReaderRouteRenderState.Loading
    }

private const val ReaderAutoSyncLogTag = "HoshiReaderSync"
