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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import moe.antimony.hoshi.LocalHoshiUiDependencies
import moe.antimony.hoshi.epub.BookSortOption
import moe.antimony.hoshi.features.anki.AnkiView
import moe.antimony.hoshi.features.bookshelf.BookshelfView
import moe.antimony.hoshi.features.bookshelf.HoshiMainShell
import moe.antimony.hoshi.features.bookshelf.MainTab
import moe.antimony.hoshi.features.bookshelf.SettingsDestination
import moe.antimony.hoshi.features.bookshelf.SettingsTab
import moe.antimony.hoshi.features.diagnostics.DiagnosticsView
import moe.antimony.hoshi.features.dictionary.DictionarySearchView
import moe.antimony.hoshi.features.dictionary.DictionaryView
import moe.antimony.hoshi.features.reader.ReaderAppearanceScreen
import moe.antimony.hoshi.features.reader.ReaderBehaviorScreen
import moe.antimony.hoshi.features.reader.ReaderFontManager
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.profiles.ProfilesView
import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import moe.antimony.hoshi.features.settings.AdvancedSettingsView
import moe.antimony.hoshi.features.statistics.StatisticsView
import moe.antimony.hoshi.features.update.AboutScreen
import kotlinx.coroutines.launch

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
    pendingSasayakiReaderBookId: String? = null,
    onPendingSasayakiReaderConsumed: () -> Unit = {},
    readerSettings: ReaderSettings,
    onReaderSettingsChange: (ReaderSettings) -> Unit,
    onReaderKeyEventHandlerChange: (((KeyEvent) -> Boolean)?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContainer = LocalHoshiUiDependencies.current
    val dictionarySettingsRepository = appContainer.dictionarySettingsRepository
    val launchRouteStateHolder = remember { AppLaunchRouteStateHolder() }
    val pendingImportRouteCoordinator = remember { PendingImportRouteCoordinator() }
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.Books) }
    val booksBackStack = rememberNavBackStack(AppRoute.BooksRoute)
    val dictionaryBackStack = rememberNavBackStack(AppRoute.DictionaryRoute)
    val statisticsBackStack = rememberNavBackStack(AppRoute.StatisticsRoute)
    val settingsBackStack = rememberNavBackStack(AppRoute.SettingsRoute)
    val bookRepository = appContainer.bookRepository
    val epubBookParser = appContainer.epubBookParser
    val readerRouteStateHolder = remember(bookRepository, epubBookParser) {
        ReaderRouteStateHolder(bookRepository, DefaultReaderRouteEpubParser(epubBookParser))
    }
    val readerFontManager = appContainer.readerFontManager
    val sasayakiSettingsRepository = appContainer.sasayakiSettingsRepository
    val scope = rememberCoroutineScope()
    val currentReaderSettings by rememberUpdatedState(readerSettings)
    val currentOnPendingImportConsumed by rememberUpdatedState(onPendingImportConsumed)
    val currentOnPendingSasayakiReaderConsumed by rememberUpdatedState(onPendingSasayakiReaderConsumed)
    val currentOnReaderSettingsChange by rememberUpdatedState(onReaderSettingsChange)
    val currentOnReaderKeyEventHandlerChange by rememberUpdatedState(onReaderKeyEventHandlerChange)
    val currentPendingImportUri by rememberUpdatedState(pendingImportUri)
    val currentPendingSasayakiReaderBookId by rememberUpdatedState(pendingSasayakiReaderBookId)
    val readerBookmarkRefreshState = remember { ReaderBookmarkRefreshState() }
    var bookshelfRefreshKey by remember { mutableIntStateOf(0) }
    var dictionaryFocusRequestKey by rememberSaveable { mutableIntStateOf(0) }
    var sasayakiSettings by remember { mutableStateOf(SasayakiSettings()) }

    LaunchedEffect(sasayakiSettingsRepository) {
        sasayakiSettingsRepository.settings.collect { settings ->
            sasayakiSettings = settings
        }
    }

    fun updateSasayakiSettings(settings: SasayakiSettings) {
        sasayakiSettings = settings
        scope.launch {
            sasayakiSettingsRepository.update { settings }
        }
    }

    fun selectedBackStack(): MutableList<NavKey> = when (selectedTab) {
        MainTab.Books -> booksBackStack
        MainTab.Dictionary -> dictionaryBackStack
        MainTab.Statistics -> statisticsBackStack
        MainTab.Settings -> settingsBackStack
    }

    fun selectTopLevelRoute(route: AppRoute) {
        selectedTab = route.toMainTab()
    }

    fun selectMainTab(tab: MainTab) {
        dictionaryFocusRequestKey = nextDictionaryFocusRequestKey(
            selectedTab = selectedTab,
            requestedTab = tab,
            currentKey = dictionaryFocusRequestKey,
        )
        selectedTab = tab
    }

    fun clearLoadedReaderProfile() {
        appContainer.profileActivationService.clearLoadedProfile()
    }

    fun clearReaderRoutesOutsideBooks() {
        statisticsBackStack.removeReaderRoutes(onReaderRouteRemoved = ::clearLoadedReaderProfile)
    }

    LaunchedEffect(dictionarySettingsRepository) {
        dictionarySettingsRepository.settings.collect { settings ->
            launchRouteStateHolder.defaultRouteAfterSettingsLoad(
                readerSettings = currentReaderSettings,
                dictionarySettings = settings,
                hasPendingImport = currentPendingImportUri != null || currentPendingSasayakiReaderBookId != null,
                isBooksTabSelected = selectedTab == MainTab.Books,
                backStack = booksBackStack,
                recentBookIdProvider = {
                    runCatching {
                        bookRepository.loadBookEntries(BookSortOption.Recent)
                            .firstOrNull()
                            ?.metadata
                            ?.id
                    }.getOrNull()
                },
            )?.let { route ->
                when (route) {
                    is AppRoute.ReaderRoute -> {
                        clearReaderRoutesOutsideBooks()
                        selectedTab = MainTab.Books
                        booksBackStack.openReaderRoute(route.bookId)
                    }
                    else -> selectTopLevelRoute(route)
                }
            }
        }
    }

    fun popRoute() {
        selectedBackStack().popAppRoute(onReaderRouteRemoved = ::clearLoadedReaderProfile)
    }

    fun closeReaderRoute() {
        if (readerBookmarkRefreshState.consumeDirty()) {
            bookshelfRefreshKey += 1
        }
        selectedBackStack().popAppRoute(onReaderRouteRemoved = ::clearLoadedReaderProfile)
    }

    fun openSettingsDetail(section: SettingsDetailSection) {
        selectedTab = MainTab.Settings
        settingsBackStack.add(AppRoute.SettingsDetailRoute(section))
    }

    fun openReader(bookId: String) {
        clearReaderRoutesOutsideBooks()
        selectedTab = MainTab.Books
        booksBackStack.openReaderRoute(bookId)
    }

    fun returnToSasayakiReader(bookId: String) {
        clearReaderRoutesOutsideBooks()
        selectedTab = MainTab.Books
        booksBackStack.returnFromMediaSession(
            bookId = bookId,
            onReaderRouteRemoved = ::clearLoadedReaderProfile,
        )
    }

    LaunchedEffect(pendingImportUri) {
        val hasPendingImport = pendingImportUri != null
        if (hasPendingImport) {
            clearReaderRoutesOutsideBooks()
        }
        pendingImportRouteCoordinator.routePendingImport(
            hasPendingImport = hasPendingImport,
            backStack = booksBackStack,
            onReaderRouteRemoved = ::clearLoadedReaderProfile,
        )
        if (hasPendingImport) {
            selectedTab = MainTab.Books
        }
    }

    LaunchedEffect(pendingSasayakiReaderBookId) {
        val bookId = pendingSasayakiReaderBookId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        returnToSasayakiReader(bookId)
        currentOnPendingSasayakiReaderConsumed()
    }

    val entryProvider: (NavKey) -> NavEntry<NavKey> = { key ->
        val route = key as AppRoute
        NavEntry(route) {
            when (route) {
                AppRoute.BooksRoute -> TopLevelRouteContent(
                    selectedTab = MainTab.Books,
                    pendingImportUri = currentPendingImportUri,
                    onPendingImportConsumed = currentOnPendingImportConsumed,
                    readerSettings = currentReaderSettings,
                    onReaderSettingsChange = currentOnReaderSettingsChange,
                    onOpenReader = ::openReader,
                    bookshelfRefreshKey = bookshelfRefreshKey,
                    dictionaryFocusRequestKey = dictionaryFocusRequestKey,
                    onSelectedTabChange = ::selectMainTab,
                )
                AppRoute.DictionaryRoute -> TopLevelRouteContent(
                    selectedTab = MainTab.Dictionary,
                    pendingImportUri = currentPendingImportUri,
                    onPendingImportConsumed = currentOnPendingImportConsumed,
                    readerSettings = currentReaderSettings,
                    onReaderSettingsChange = currentOnReaderSettingsChange,
                    onOpenReader = ::openReader,
                    bookshelfRefreshKey = bookshelfRefreshKey,
                    dictionaryFocusRequestKey = dictionaryFocusRequestKey,
                    onSelectedTabChange = ::selectMainTab,
                )
                AppRoute.StatisticsRoute -> TopLevelRouteContent(
                    selectedTab = MainTab.Statistics,
                    pendingImportUri = currentPendingImportUri,
                    onPendingImportConsumed = currentOnPendingImportConsumed,
                    readerSettings = currentReaderSettings,
                    onReaderSettingsChange = currentOnReaderSettingsChange,
                    onOpenReader = ::openReader,
                    bookshelfRefreshKey = bookshelfRefreshKey,
                    dictionaryFocusRequestKey = dictionaryFocusRequestKey,
                    onSelectedTabChange = ::selectMainTab,
                )
                AppRoute.SettingsRoute -> TopLevelRouteContent(
                    selectedTab = MainTab.Settings,
                    pendingImportUri = currentPendingImportUri,
                    onPendingImportConsumed = currentOnPendingImportConsumed,
                    readerSettings = currentReaderSettings,
                    onReaderSettingsChange = currentOnReaderSettingsChange,
                    onOpenReader = ::openReader,
                    bookshelfRefreshKey = bookshelfRefreshKey,
                    dictionaryFocusRequestKey = dictionaryFocusRequestKey,
                    onSelectedTabChange = ::selectMainTab,
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
                    sasayakiSettings = sasayakiSettings,
                    onSasayakiSettingsChange = ::updateSasayakiSettings,
                    readerFontManager = readerFontManager,
                    onClose = ::popRoute,
                    onBooksRestored = { bookshelfRefreshKey += 1 },
                    onSelectedTabChange = ::selectMainTab,
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
                AppRoute.MainRoute -> TopLevelRouteContent(
                    selectedTab = MainTab.Books,
                    pendingImportUri = currentPendingImportUri,
                    onPendingImportConsumed = currentOnPendingImportConsumed,
                    readerSettings = currentReaderSettings,
                    onReaderSettingsChange = currentOnReaderSettingsChange,
                    onOpenReader = ::openReader,
                    bookshelfRefreshKey = bookshelfRefreshKey,
                    dictionaryFocusRequestKey = dictionaryFocusRequestKey,
                    onSelectedTabChange = ::selectMainTab,
                )
            }
        }
    }
    val booksEntries = rememberDecoratedNavEntries(
        backStack = booksBackStack,
        entryDecorators = rememberAppNavEntryDecorators(),
        entryProvider = entryProvider,
    )
    val dictionaryEntries = rememberDecoratedNavEntries(
        backStack = dictionaryBackStack,
        entryDecorators = rememberAppNavEntryDecorators(),
        entryProvider = entryProvider,
    )
    val settingsEntries = rememberDecoratedNavEntries(
        backStack = settingsBackStack,
        entryDecorators = rememberAppNavEntryDecorators(),
        entryProvider = entryProvider,
    )
    val statisticsEntries = rememberDecoratedNavEntries(
        backStack = statisticsBackStack,
        entryDecorators = rememberAppNavEntryDecorators(),
        entryProvider = entryProvider,
    )
    val currentEntries = when (selectedTab) {
        MainTab.Books -> booksEntries
        MainTab.Dictionary -> dictionaryEntries
        MainTab.Statistics -> statisticsEntries
        MainTab.Settings -> settingsEntries
    }

    NavDisplay(
        entries = currentEntries,
        modifier = modifier,
        onBack = ::popRoute,
        transitionSpec = NoNavContentTransition,
        popTransitionSpec = NoNavContentTransition,
        predictivePopTransitionSpec = NoPredictiveNavContentTransition,
    )
}

@Composable
private fun rememberAppNavEntryDecorators(): List<NavEntryDecorator<NavKey>> = listOf(
    rememberSaveableStateHolderNavEntryDecorator(),
    rememberViewModelStoreNavEntryDecorator(),
)

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
private fun TopLevelRouteContent(
    selectedTab: MainTab,
    pendingImportUri: Uri?,
    onPendingImportConsumed: () -> Unit,
    readerSettings: ReaderSettings,
    onReaderSettingsChange: (ReaderSettings) -> Unit,
    onOpenReader: (String) -> Unit,
    bookshelfRefreshKey: Int,
    dictionaryFocusRequestKey: Int,
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
                refreshKey = bookshelfRefreshKey,
                layoutSpec = layoutSpec,
                modifier = contentModifier,
            )
            MainTab.Dictionary -> DictionarySearchView(
                readerSettings = readerSettings,
                focusRequestKey = dictionaryFocusRequestKey,
                modifier = contentModifier.fillMaxSize(),
            )
            MainTab.Statistics -> StatisticsView(
                layoutSpec = layoutSpec,
                modifier = contentModifier,
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
    sasayakiSettings: SasayakiSettings,
    onSasayakiSettingsChange: (SasayakiSettings) -> Unit,
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
        SettingsDetailSection.Profiles -> ProfilesView(
            onClose = onClose,
            modifier = Modifier.fillMaxSize(),
        )
        SettingsDetailSection.Appearance -> ReaderAppearanceScreen(
            settings = readerSettings,
            onSettingsChange = onReaderSettingsChange,
            sasayakiSettings = sasayakiSettings,
            onSasayakiSettingsChange = onSasayakiSettingsChange,
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
            readerSettings = readerSettings,
            onReaderSettingsChange = onReaderSettingsChange,
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
    MainTab.Statistics -> AppRoute.StatisticsRoute
    MainTab.Settings -> AppRoute.SettingsRoute
}

internal fun nextDictionaryFocusRequestKey(
    selectedTab: MainTab,
    requestedTab: MainTab,
    currentKey: Int,
): Int =
    if (selectedTab == MainTab.Dictionary && requestedTab == MainTab.Dictionary) {
        currentKey + 1
    } else {
        currentKey
    }

private fun AppRoute.toMainTab(): MainTab = when (this) {
    AppRoute.MainRoute, AppRoute.BooksRoute -> MainTab.Books
    AppRoute.DictionaryRoute -> MainTab.Dictionary
    AppRoute.StatisticsRoute -> MainTab.Statistics
    AppRoute.SettingsRoute -> MainTab.Settings
    is AppRoute.ReaderRoute -> MainTab.Books
    is AppRoute.SettingsDetailRoute -> MainTab.Settings
}

private fun SettingsDestination.toSection(): SettingsDetailSection = when (this) {
    SettingsDestination.Dictionaries -> SettingsDetailSection.Dictionaries
    SettingsDestination.Anki -> SettingsDetailSection.Anki
    SettingsDestination.Profiles -> SettingsDetailSection.Profiles
    SettingsDestination.Appearance -> SettingsDetailSection.Appearance
    SettingsDestination.Behavior -> SettingsDetailSection.Behavior
    SettingsDestination.Advanced -> SettingsDetailSection.Advanced
    SettingsDestination.Diagnostics -> SettingsDetailSection.Diagnostics
    SettingsDestination.About -> SettingsDetailSection.About
    SettingsDestination.ReportIssue -> error("Report issue is handled outside Navigation3.")
}
