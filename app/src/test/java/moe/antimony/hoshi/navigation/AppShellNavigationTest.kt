package moe.antimony.hoshi.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppShellNavigationTest {
    @Test
    fun mainActivityMountsAppShellAsTheAppRouteOwner() {
        val mainActivity = File("src/main/java/moe/antimony/hoshi/MainActivity.kt").readText()

        assertTrue(mainActivity.contains("import moe.antimony.hoshi.navigation.AppShell"))
        assertTrue(mainActivity.contains("AppShell("))
        assertFalse(mainActivity.contains("import moe.antimony.hoshi.features.bookshelf.BookshelfView"))
    }

    @Test
    fun appShellOwnsNavigation3BackStackAndAllAppRoutes() {
        val appShell = File("src/main/java/moe/antimony/hoshi/navigation/AppShell.kt").readText()

        assertTrue(appShell.contains("rememberNavBackStack("))
        assertTrue(appShell.contains("NavDisplay("))
        assertTrue(appShell.contains("entryProvider"))
        assertTrue(appShell.contains("rememberSaveableStateHolderNavEntryDecorator()"))
        assertTrue(appShell.contains("AppRoute.BooksRoute"))
        assertTrue(appShell.contains("AppRoute.DictionaryRoute"))
        assertTrue(appShell.contains("AppRoute.SettingsRoute"))
        assertTrue(appShell.contains("is AppRoute.SettingsDetailRoute"))
        assertTrue(appShell.contains("is AppRoute.ReaderRoute"))
        assertTrue(appShell.contains("is AppRoute.SasayakiMatchRoute"))
    }

    @Test
    fun ankiSettingsUsesNavigation3DetailRoute() {
        val appShell = File("src/main/java/moe/antimony/hoshi/navigation/AppShell.kt").readText()
        val appRoute = File("src/main/java/moe/antimony/hoshi/navigation/AppRoute.kt").readText()
        val settingsRoute = appShell.substringAfter("AppRoute.SettingsRoute ->")
            .substringBefore("is AppRoute.SettingsDetailRoute")
        val sectionMapping = appShell.substringAfter("private fun SettingsDestination.toSection()")
            .substringBefore("private fun SettingsDetailSection.placeholderTitle()")

        assertTrue(appRoute.contains("Anki,"))
        assertTrue(settingsRoute.contains("SettingsDestination.Anki -> openSettingsDetail(destination.toSection())"))
        assertTrue(sectionMapping.contains("SettingsDestination.Anki -> SettingsDetailSection.Anki"))
        assertTrue(appShell.contains("SettingsDetailSection.Anki -> AnkiView("))
    }

    @Test
    fun appShellReadsLatestSettingsInsideSavedNavEntries() {
        val appShell = File("src/main/java/moe/antimony/hoshi/navigation/AppShell.kt").readText()

        assertTrue(appShell.contains("rememberUpdatedState(readerSettings)"))
        assertTrue(appShell.contains("readerSettings = currentReaderSettings"))
        assertTrue(appShell.contains("onReaderSettingsChange = currentOnReaderSettingsChange"))
    }

    @Test
    fun mainActivityWaitsForSavedReaderSettingsBeforeRenderingAppShell() {
        val mainActivity = File("src/main/java/moe/antimony/hoshi/MainActivity.kt").readText()

        assertTrue(mainActivity.contains("var readerSettings by remember { mutableStateOf<ReaderSettings?>(null) }"))
        assertTrue(mainActivity.contains("val loadedReaderSettings = readerSettings ?: return@HoshiReaderTheme"))
        assertFalse(mainActivity.contains("mutableStateOf(ReaderSettings())"))
    }

    @Test
    fun appShellDisablesNavigationContentTransitions() {
        val appShell = File("src/main/java/moe/antimony/hoshi/navigation/AppShell.kt").readText()

        assertTrue(appShell.contains("transitionSpec = NoNavContentTransition"))
        assertTrue(appShell.contains("popTransitionSpec = NoNavContentTransition"))
        assertTrue(appShell.contains("predictivePopTransitionSpec = NoPredictiveNavContentTransition"))
        assertFalse(appShell.contains("defaultTransitionSpec"))
        assertFalse(appShell.contains("defaultPopTransitionSpec"))
    }

    @Test
    fun bookshelfViewEmitsAppRouteEventsInsteadOfOwningDisplayRoutes() {
        val bookshelf = File("src/main/java/moe/antimony/hoshi/features/bookshelf/BookshelfView.kt").readText()
        val functionHeader = bookshelf.substringAfter("fun BookshelfView(")
            .substringBefore("modifier: Modifier")

        assertTrue(functionHeader.contains("onOpenReader: (String) -> Unit"))
        assertTrue(functionHeader.contains("onOpenSasayakiMatch: (SasayakiMatchRequest) -> Unit"))
        assertFalse(bookshelf.contains("var selectedTab by remember"))
        assertFalse(bookshelf.contains("var settingsDestination by remember"))
        assertFalse(bookshelf.contains("var isReading by remember"))
        assertFalse(bookshelf.contains("ReaderWebView("))
        assertFalse(bookshelf.contains("SasayakiMatchView("))
    }

    @Test
    fun readerRouteResolvesBookByStableIdInsteadOfSessionPayload() {
        val appShell = File("src/main/java/moe/antimony/hoshi/navigation/AppShell.kt").readText()
        val readerDestination = File("src/main/java/moe/antimony/hoshi/navigation/ReaderRouteDestination.kt").readText()
        val bookshelf = File("src/main/java/moe/antimony/hoshi/features/bookshelf/BookshelfView.kt").readText()
        val readerBranch = appShell.substringAfter("is AppRoute.ReaderRoute ->")
            .substringBefore("is AppRoute.SasayakiMatchRoute")

        assertFalse(appShell.contains("readerSessions"))
        assertFalse(appShell.contains("ReaderOpenRequest"))
        assertFalse(bookshelf.contains("data class ReaderOpenRequest"))
        assertTrue(appShell.contains("ReaderRouteDestination("))
        assertTrue(appShell.contains("appContainer.readerRouteStateHolder()"))
        assertTrue(readerDestination.contains("stateHolder.load(bookId)"))
        assertTrue(readerBranch.contains("bookId = route.bookId"))
    }

    @Test
    fun readerBookmarkRefreshIsCoalescedUntilReaderClose() {
        val state = ReaderBookmarkRefreshState()

        assertFalse(state.consumeDirty())
        state.markDirty()
        state.markDirty()
        assertTrue(state.consumeDirty())
        assertFalse(state.consumeDirty())
    }

    @Test
    fun readerPageBookmarkSavesDoNotRefreshBookshelfDuringReaderSession() {
        val appShell = File("src/main/java/moe/antimony/hoshi/navigation/AppShell.kt").readText()
        val closeReaderRoute = appShell.substringAfter("fun closeReaderRoute()")
            .substringBefore("fun selectTopLevelRoute")
        val readerBranch = appShell.substringAfter("is AppRoute.ReaderRoute ->")
            .substringBefore("is AppRoute.SasayakiMatchRoute")

        assertTrue(appShell.contains("val readerBookmarkRefreshState = remember { ReaderBookmarkRefreshState() }"))
        assertTrue(closeReaderRoute.contains("readerBookmarkRefreshState.consumeDirty()"))
        assertTrue(closeReaderRoute.contains("bookshelfRefreshKey += 1"))
        assertTrue(readerBranch.contains("onBookmarkSaved = readerBookmarkRefreshState::markDirty"))
        assertTrue(readerBranch.contains("onClose = ::closeReaderRoute"))
        assertFalse(readerBranch.contains("bookshelfRefreshKey += 1"))
    }

    @Test
    fun appShellDelegatesNonNavigationStateToFocusedHelpers() {
        val appShell = File("src/main/java/moe/antimony/hoshi/navigation/AppShell.kt").readText()

        assertTrue(appShell.contains("AppLaunchRouteStateHolder"))
        assertTrue(appShell.contains("PendingImportRouteCoordinator"))
        assertTrue(appShell.contains("SasayakiMatchRequestStore"))
        assertFalse(appShell.contains("var dictionarySettings by remember"))
        assertFalse(appShell.contains("var dictionarySettingsLoaded by remember"))
        assertFalse(appShell.contains("var dictionaryDefaultRouteApplied by remember"))
        assertFalse(appShell.contains("var sasayakiMatchRequests by remember"))
    }
}
