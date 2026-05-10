package moe.antimony.hoshi.navigation

import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.features.audio.AdvancedSettingsView
import moe.antimony.hoshi.features.anki.AnkiView
import moe.antimony.hoshi.features.bookshelf.BookshelfView
import moe.antimony.hoshi.features.bookshelf.HoshiMainShell
import moe.antimony.hoshi.features.bookshelf.MainTab
import moe.antimony.hoshi.features.bookshelf.SasayakiMatchRequest
import moe.antimony.hoshi.features.bookshelf.SettingsDestination
import moe.antimony.hoshi.features.bookshelf.SettingsTab
import moe.antimony.hoshi.features.diagnostics.DiagnosticsView
import moe.antimony.hoshi.features.dictionary.DictionarySearchView
import moe.antimony.hoshi.features.dictionary.DictionaryView
import moe.antimony.hoshi.features.reader.ReaderAppearanceScreen
import moe.antimony.hoshi.features.reader.ReaderBehaviorScreen
import moe.antimony.hoshi.features.reader.ReaderFontManager
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.sasayaki.SasayakiMatchView
import moe.antimony.hoshi.features.update.AboutScreen

private const val ReportIssueUrl = "https://github.com/HuangAntimony/Hoshi-Reader-Android/issues"

private val NoNavContentTransition: AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform = {
    EnterTransition.None togetherWith ExitTransition.None
}

private val NoPredictiveNavContentTransition:
    AnimatedContentTransitionScope<Scene<NavKey>>.(Int) -> ContentTransform = { _ ->
        EnterTransition.None togetherWith ExitTransition.None
    }

@Composable
fun AppShell(
    pendingImportUri: Uri? = null,
    onPendingImportConsumed: () -> Unit = {},
    readerSettings: ReaderSettings,
    onReaderSettingsChange: (ReaderSettings) -> Unit,
    onReaderKeyEventHandlerChange: (((KeyEvent) -> Boolean)?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContainer = LocalHoshiAppContainer.current
    val dictionarySettingsRepository = appContainer.dictionarySettingsRepository
    val launchRouteStateHolder = remember { AppLaunchRouteStateHolder() }
    val pendingImportRouteCoordinator = remember { PendingImportRouteCoordinator() }
    val sasayakiMatchRequestStore = remember { SasayakiMatchRequestStore() }
    val initialRoute = AppRoute.BooksRoute
    val backStack = rememberNavBackStack(initialRoute)
    val bookRepository = appContainer.bookRepository
    val readerRouteStateHolder = remember(appContainer) { appContainer.readerRouteStateHolder() }
    val readerFontManager = appContainer.readerFontManager
    val currentReaderSettings by rememberUpdatedState(readerSettings)
    val currentOnReaderSettingsChange by rememberUpdatedState(onReaderSettingsChange)
    val currentOnReaderKeyEventHandlerChange by rememberUpdatedState(onReaderKeyEventHandlerChange)
    val currentPendingImportUri by rememberUpdatedState(pendingImportUri)
    val readerBookmarkRefreshState = remember { ReaderBookmarkRefreshState() }
    var bookshelfRefreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(dictionarySettingsRepository) {
        dictionarySettingsRepository.settings.collect { settings ->
            launchRouteStateHolder.defaultRouteAfterSettingsLoad(
                settings = settings,
                hasPendingImport = currentPendingImportUri != null,
                backStack = backStack,
            )?.let(backStack::selectTopLevelRoute)
        }
    }

    fun popRoute() {
        backStack.popAppRoute()
    }

    fun closeReaderRoute() {
        if (readerBookmarkRefreshState.consumeDirty()) {
            bookshelfRefreshKey += 1
        }
        popRoute()
    }

    fun selectTopLevelRoute(route: AppRoute) {
        backStack.selectTopLevelRoute(route)
    }

    fun openSettingsDetail(section: SettingsDetailSection) {
        backStack.add(AppRoute.SettingsDetailRoute(section))
    }

    fun openReader(bookId: String) {
        backStack.openReaderRoute(bookId)
    }

    fun openSasayakiMatch(request: SasayakiMatchRequest) {
        sasayakiMatchRequestStore.put(request)
        backStack.openSasayakiMatchRoute(request.bookId)
    }

    LaunchedEffect(pendingImportUri) {
        pendingImportRouteCoordinator.routePendingImport(
            hasPendingImport = pendingImportUri != null,
            backStack = backStack,
        )
    }

    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = ::popRoute,
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
        transitionSpec = NoNavContentTransition,
        popTransitionSpec = NoNavContentTransition,
        predictivePopTransitionSpec = NoPredictiveNavContentTransition,
        entryProvider = { key ->
            val route = key as AppRoute
            NavEntry(route) {
                when (route) {
                    AppRoute.BooksRoute -> TopLevelRouteContent(
                        selectedTab = MainTab.Books,
                        pendingImportUri = pendingImportUri,
                        onPendingImportConsumed = onPendingImportConsumed,
                        readerSettings = currentReaderSettings,
                        onReaderSettingsChange = currentOnReaderSettingsChange,
                        onOpenReader = ::openReader,
                        onOpenSasayakiMatch = ::openSasayakiMatch,
                        bookshelfRefreshKey = bookshelfRefreshKey,
                        onSelectedTabChange = { selectTopLevelRoute(it.toRoute()) },
                    )
                    AppRoute.DictionaryRoute -> TopLevelRouteContent(
                        selectedTab = MainTab.Dictionary,
                        pendingImportUri = pendingImportUri,
                        onPendingImportConsumed = onPendingImportConsumed,
                        readerSettings = currentReaderSettings,
                        onReaderSettingsChange = currentOnReaderSettingsChange,
                        onOpenReader = ::openReader,
                        onOpenSasayakiMatch = ::openSasayakiMatch,
                        bookshelfRefreshKey = bookshelfRefreshKey,
                        onSelectedTabChange = { selectTopLevelRoute(it.toRoute()) },
                    )
                    AppRoute.SettingsRoute -> TopLevelRouteContent(
                        selectedTab = MainTab.Settings,
                        pendingImportUri = pendingImportUri,
                        onPendingImportConsumed = onPendingImportConsumed,
                        readerSettings = currentReaderSettings,
                        onReaderSettingsChange = currentOnReaderSettingsChange,
                        onOpenReader = ::openReader,
                        onOpenSasayakiMatch = ::openSasayakiMatch,
                        bookshelfRefreshKey = bookshelfRefreshKey,
                        onSelectedTabChange = { selectTopLevelRoute(it.toRoute()) },
                        onSettingsDestination = { destination ->
                            when (destination) {
                                SettingsDestination.Anki -> openSettingsDetail(destination.toSection())
                                SettingsDestination.ReportIssue -> context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse(ReportIssueUrl),
                                    ),
                                )
                                else -> openSettingsDetail(destination.toSection())
                            }
                        },
                    )
                    is AppRoute.SettingsDetailRoute -> SettingsDetailDestination(
                        route = route,
                        readerSettings = currentReaderSettings,
                        onReaderSettingsChange = currentOnReaderSettingsChange,
                        readerFontManager = readerFontManager,
                        onClose = ::popRoute,
                        onBooksRestored = { bookshelfRefreshKey += 1 },
                        onSelectedTabChange = { selectTopLevelRoute(it.toRoute()) },
                    )
                    is AppRoute.ReaderRoute -> {
                        ReaderRouteDestination(
                            bookId = route.bookId,
                            stateHolder = readerRouteStateHolder,
                            readerSettings = currentReaderSettings,
                            onReaderSettingsChange = currentOnReaderSettingsChange,
                            onReaderKeyEventHandlerChange = currentOnReaderKeyEventHandlerChange,
                            onBookmarkSaved = readerBookmarkRefreshState::markDirty,
                            onClose = ::closeReaderRoute,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    is AppRoute.SasayakiMatchRoute -> {
                        val request = sasayakiMatchRequestStore.get(route.bookId)
                        if (request != null) {
                            SasayakiMatchView(
                                bookEntry = request.bookEntry,
                                bookRepository = bookRepository,
                                onClose = ::popRoute,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            MissingRouteRedirect {
                                selectTopLevelRoute(AppRoute.BooksRoute)
                            }
                        }
                    }
                    AppRoute.MainRoute -> TopLevelRouteContent(
                        selectedTab = MainTab.Books,
                        pendingImportUri = pendingImportUri,
                        onPendingImportConsumed = onPendingImportConsumed,
                        readerSettings = currentReaderSettings,
                        onReaderSettingsChange = currentOnReaderSettingsChange,
                        onOpenReader = ::openReader,
                        onOpenSasayakiMatch = ::openSasayakiMatch,
                        bookshelfRefreshKey = bookshelfRefreshKey,
                        onSelectedTabChange = { selectTopLevelRoute(it.toRoute()) },
                    )
                }
            }
        },
    )
}

internal class ReaderBookmarkRefreshState {
    private var dirty = false

    fun markDirty() {
        dirty = true
    }

    fun consumeDirty(): Boolean {
        val wasDirty = dirty
        dirty = false
        return wasDirty
    }
}

@Composable
private fun MissingRouteRedirect(onRedirect: () -> Unit) {
    LaunchedEffect(Unit) {
        onRedirect()
    }
}

@Composable
private fun TopLevelRouteContent(
    selectedTab: MainTab,
    pendingImportUri: Uri?,
    onPendingImportConsumed: () -> Unit,
    readerSettings: ReaderSettings,
    onReaderSettingsChange: (ReaderSettings) -> Unit,
    onOpenReader: (String) -> Unit,
    onOpenSasayakiMatch: (SasayakiMatchRequest) -> Unit,
    bookshelfRefreshKey: Int,
    onSelectedTabChange: (MainTab) -> Unit,
    onSettingsDestination: (SettingsDestination) -> Unit = {},
) {
    HoshiMainShell(
        selectedTab = selectedTab,
        onSelectedTabChange = onSelectedTabChange,
    ) { contentModifier, layoutSpec ->
        when (selectedTab) {
            MainTab.Books -> BookshelfView(
                pendingImportUri = pendingImportUri,
                onPendingImportConsumed = onPendingImportConsumed,
                onOpenReader = onOpenReader,
                onOpenSasayakiMatch = onOpenSasayakiMatch,
                refreshKey = bookshelfRefreshKey,
                layoutSpec = layoutSpec,
                modifier = contentModifier,
            )
            MainTab.Dictionary -> DictionarySearchView(
                readerSettings = readerSettings,
                modifier = contentModifier.fillMaxSize(),
            )
            MainTab.Settings -> SettingsTab(
                modifier = contentModifier,
                layoutSpec = layoutSpec,
                onDestination = onSettingsDestination,
            )
        }
    }
}

@Composable
private fun SettingsDetailDestination(
    route: AppRoute.SettingsDetailRoute,
    readerSettings: ReaderSettings,
    onReaderSettingsChange: (ReaderSettings) -> Unit,
    readerFontManager: ReaderFontManager,
    onClose: () -> Unit,
    onBooksRestored: () -> Unit,
    onSelectedTabChange: (MainTab) -> Unit,
) {
    when (route.section) {
        SettingsDetailSection.Dictionaries -> DictionaryView(
            onClose = onClose,
            modifier = Modifier.fillMaxSize(),
        )
        SettingsDetailSection.Anki -> AnkiView(
            onClose = onClose,
            modifier = Modifier.fillMaxSize(),
        )
        SettingsDetailSection.Appearance -> ReaderAppearanceScreen(
            settings = readerSettings,
            onSettingsChange = onReaderSettingsChange,
            fontManager = readerFontManager,
            onClose = onClose,
            modifier = Modifier.fillMaxSize(),
        )
        SettingsDetailSection.Behavior -> ReaderBehaviorScreen(
            settings = readerSettings,
            onSettingsChange = onReaderSettingsChange,
            onClose = onClose,
            modifier = Modifier.fillMaxSize(),
        )
        SettingsDetailSection.Advanced -> AdvancedSettingsView(
            onClose = onClose,
            onBooksRestored = onBooksRestored,
            modifier = Modifier.fillMaxSize(),
        )
        SettingsDetailSection.Diagnostics -> DiagnosticsView(
            onClose = onClose,
            modifier = Modifier.fillMaxSize(),
        )
        SettingsDetailSection.About -> AboutScreen(
            onClose = onClose,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun MainTab.toRoute(): AppRoute = when (this) {
    MainTab.Books -> AppRoute.BooksRoute
    MainTab.Dictionary -> AppRoute.DictionaryRoute
    MainTab.Settings -> AppRoute.SettingsRoute
}

private fun SettingsDestination.toSection(): SettingsDetailSection = when (this) {
    SettingsDestination.Dictionaries -> SettingsDetailSection.Dictionaries
    SettingsDestination.Anki -> SettingsDetailSection.Anki
    SettingsDestination.Appearance -> SettingsDetailSection.Appearance
    SettingsDestination.Behavior -> SettingsDetailSection.Behavior
    SettingsDestination.Advanced -> SettingsDetailSection.Advanced
    SettingsDestination.Diagnostics -> SettingsDetailSection.Diagnostics
    SettingsDestination.About -> SettingsDetailSection.About
    SettingsDestination.ReportIssue -> error("Report issue is handled outside Navigation3.")
}
