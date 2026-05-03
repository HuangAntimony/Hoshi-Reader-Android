package moe.antimony.hoshi.navigation

import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import moe.antimony.hoshi.epub.BookStorage
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.features.audio.AdvancedSettingsView
import moe.antimony.hoshi.features.bookshelf.BookshelfView
import moe.antimony.hoshi.features.bookshelf.HoshiMainShell
import moe.antimony.hoshi.features.bookshelf.MainTab
import moe.antimony.hoshi.features.bookshelf.ReaderOpenRequest
import moe.antimony.hoshi.features.bookshelf.SasayakiMatchRequest
import moe.antimony.hoshi.features.bookshelf.SettingsDestination
import moe.antimony.hoshi.features.bookshelf.SettingsTab
import moe.antimony.hoshi.features.diagnostics.DiagnosticsView
import moe.antimony.hoshi.features.dictionary.DictionarySearchView
import moe.antimony.hoshi.features.dictionary.DictionarySettingsStore
import moe.antimony.hoshi.features.dictionary.DictionaryView
import moe.antimony.hoshi.features.reader.ReaderAppearanceScreen
import moe.antimony.hoshi.features.reader.ReaderBehaviorScreen
import moe.antimony.hoshi.features.reader.ReaderFontManager
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.reader.ReaderWebView
import moe.antimony.hoshi.features.sasayaki.SasayakiMatchView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val ReportIssueUrl = "https://github.com/HuangAntimony/Hoshi-Reader-Android/issues"

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
    val initialRoute = remember {
        if (DictionarySettingsStore(context).load().dictionaryTabDefault) {
            AppRoute.DictionaryRoute
        } else {
            AppRoute.BooksRoute
        }
    }
    val backStack = rememberNavBackStack(initialRoute)
    val scope = rememberCoroutineScope()
    val bookStorage = remember { BookStorage(context.filesDir) }
    val readerFontManager = remember { ReaderFontManager(context.filesDir) }
    var readerSessions by remember { mutableStateOf<Map<String, ReaderOpenRequest>>(emptyMap()) }
    var sasayakiMatchRequests by remember { mutableStateOf<Map<String, SasayakiMatchRequest>>(emptyMap()) }
    var sasayakiSettingsReloadKey by remember { mutableIntStateOf(0) }

    fun popRoute() {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    fun selectTopLevelRoute(route: AppRoute) {
        if (backStack.size == 1 && backStack.lastOrNull() == route) {
            return
        }
        backStack.clear()
        backStack.add(route)
    }

    fun openSettingsDetail(section: SettingsDetailSection) {
        backStack.add(AppRoute.SettingsDetailRoute(section))
    }

    fun openReader(request: ReaderOpenRequest) {
        readerSessions = readerSessions + (request.bookId to request)
        selectTopLevelRoute(AppRoute.BooksRoute)
        backStack.add(AppRoute.ReaderRoute(request.bookId))
    }

    fun openSasayakiMatch(request: SasayakiMatchRequest) {
        sasayakiMatchRequests = sasayakiMatchRequests + (request.bookId to request)
        selectTopLevelRoute(AppRoute.BooksRoute)
        backStack.add(AppRoute.SasayakiMatchRoute(request.bookId))
    }

    LaunchedEffect(pendingImportUri) {
        if (pendingImportUri != null) {
            selectTopLevelRoute(AppRoute.BooksRoute)
        }
    }

    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = ::popRoute,
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
        entryProvider = { key ->
            val route = key as AppRoute
            NavEntry(route) {
                when (route) {
                    AppRoute.BooksRoute -> TopLevelRouteContent(
                        selectedTab = MainTab.Books,
                        pendingImportUri = pendingImportUri,
                        onPendingImportConsumed = onPendingImportConsumed,
                        readerSettings = readerSettings,
                        onReaderSettingsChange = onReaderSettingsChange,
                        onOpenReader = ::openReader,
                        onOpenSasayakiMatch = ::openSasayakiMatch,
                        sasayakiSettingsReloadKey = sasayakiSettingsReloadKey,
                        onSelectedTabChange = { selectTopLevelRoute(it.toRoute()) },
                    )
                    AppRoute.DictionaryRoute -> TopLevelRouteContent(
                        selectedTab = MainTab.Dictionary,
                        pendingImportUri = pendingImportUri,
                        onPendingImportConsumed = onPendingImportConsumed,
                        readerSettings = readerSettings,
                        onReaderSettingsChange = onReaderSettingsChange,
                        onOpenReader = ::openReader,
                        onOpenSasayakiMatch = ::openSasayakiMatch,
                        sasayakiSettingsReloadKey = sasayakiSettingsReloadKey,
                        onSelectedTabChange = { selectTopLevelRoute(it.toRoute()) },
                    )
                    AppRoute.SettingsRoute -> TopLevelRouteContent(
                        selectedTab = MainTab.Settings,
                        pendingImportUri = pendingImportUri,
                        onPendingImportConsumed = onPendingImportConsumed,
                        readerSettings = readerSettings,
                        onReaderSettingsChange = onReaderSettingsChange,
                        onOpenReader = ::openReader,
                        onOpenSasayakiMatch = ::openSasayakiMatch,
                        sasayakiSettingsReloadKey = sasayakiSettingsReloadKey,
                        onSelectedTabChange = { selectTopLevelRoute(it.toRoute()) },
                        onSettingsDestination = { destination ->
                            when (destination) {
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
                        readerSettings = readerSettings,
                        onReaderSettingsChange = onReaderSettingsChange,
                        readerFontManager = readerFontManager,
                        onClose = {
                            if (route.section == SettingsDetailSection.Advanced) {
                                sasayakiSettingsReloadKey += 1
                            }
                            popRoute()
                        },
                        onSelectedTabChange = { selectTopLevelRoute(it.toRoute()) },
                    )
                    is AppRoute.ReaderRoute -> {
                        val session = readerSessions[route.bookId]
                        if (session != null) {
                            ReaderWebView(
                                book = session.book,
                                bookRoot = session.bookRoot,
                                initialChapterIndex = session.bookmark?.chapterIndex ?: 0,
                                initialProgress = session.bookmark?.progress ?: 0.0,
                                readerSettings = readerSettings,
                                onReaderSettingsChange = onReaderSettingsChange,
                                onReaderKeyEventHandlerChange = onReaderKeyEventHandlerChange,
                                onSaveBookmark = { chapterIndex, progress ->
                                    val savedBookmark = Bookmark(
                                        chapterIndex = chapterIndex,
                                        progress = progress,
                                        characterCount = session.book.characterCountAt(chapterIndex, progress),
                                        lastModified = bookStorage.currentAppleReferenceDateSeconds(),
                                    )
                                    val total = session.book.bookInfo.characterCount
                                    val readingProgress = if (total > 0) {
                                        savedBookmark.characterCount.toDouble()
                                            .div(total.toDouble())
                                            .coerceIn(0.0, 1.0)
                                    } else {
                                        0.0
                                    }
                                    session.onBookmarkChanged(savedBookmark, readingProgress)
                                    scope.launch(Dispatchers.IO) {
                                        bookStorage.saveBookmark(session.bookRoot, savedBookmark)
                                    }
                                },
                                onClose = ::popRoute,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            MissingRouteRedirect {
                                selectTopLevelRoute(AppRoute.BooksRoute)
                            }
                        }
                    }
                    is AppRoute.SasayakiMatchRoute -> {
                        val request = sasayakiMatchRequests[route.bookId]
                        if (request != null) {
                            SasayakiMatchView(
                                bookEntry = request.bookEntry,
                                bookStorage = bookStorage,
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
                        readerSettings = readerSettings,
                        onReaderSettingsChange = onReaderSettingsChange,
                        onOpenReader = ::openReader,
                        onOpenSasayakiMatch = ::openSasayakiMatch,
                        sasayakiSettingsReloadKey = sasayakiSettingsReloadKey,
                        onSelectedTabChange = { selectTopLevelRoute(it.toRoute()) },
                    )
                }
            }
        },
    )
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
    onOpenReader: (ReaderOpenRequest) -> Unit,
    onOpenSasayakiMatch: (SasayakiMatchRequest) -> Unit,
    sasayakiSettingsReloadKey: Int,
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
                sasayakiSettingsReloadKey = sasayakiSettingsReloadKey,
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
    onSelectedTabChange: (MainTab) -> Unit,
) {
    when (route.section) {
        SettingsDetailSection.Dictionaries -> DictionaryView(
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
            modifier = Modifier.fillMaxSize(),
        )
        SettingsDetailSection.Diagnostics -> DiagnosticsView(
            onClose = onClose,
            modifier = Modifier.fillMaxSize(),
        )
        SettingsDetailSection.Anki,
        SettingsDetailSection.About,
        -> {
            TopLevelRouteContent(
                selectedTab = MainTab.Settings,
                pendingImportUri = null,
                onPendingImportConsumed = {},
                readerSettings = readerSettings,
                onReaderSettingsChange = onReaderSettingsChange,
                onOpenReader = {},
                onOpenSasayakiMatch = {},
                sasayakiSettingsReloadKey = 0,
                onSelectedTabChange = onSelectedTabChange,
            )
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text(route.section.placeholderTitle()) },
                text = { Text("This settings page is not implemented yet.") },
                confirmButton = {
                    TextButton(onClick = onClose) {
                        Text("OK")
                    }
                },
            )
        }
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

private fun SettingsDetailSection.placeholderTitle(): String = when (this) {
    SettingsDetailSection.Anki -> "Anki"
    SettingsDetailSection.About -> "About"
    SettingsDetailSection.Appearance -> "Appearance"
    SettingsDetailSection.Behavior -> "Behavior"
    SettingsDetailSection.Advanced -> "Advanced"
    SettingsDetailSection.Diagnostics -> "Diagnostics"
    SettingsDetailSection.Dictionaries -> "Dictionaries"
}
