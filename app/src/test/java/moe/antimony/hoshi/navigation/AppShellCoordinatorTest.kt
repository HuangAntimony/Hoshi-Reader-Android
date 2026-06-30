package moe.antimony.hoshi.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.features.bookshelf.MainTab
import moe.antimony.hoshi.features.dictionary.DictionarySettings
import moe.antimony.hoshi.features.reader.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppShellCoordinatorTest {
    @Test
    fun visibleMainTabsRequireStatisticsAndStatisticsTabSwitch() {
        assertFalse(
            appShellVisibleMainTabs(
                ReaderSettings(enableStatistics = false, showStatisticsTab = true),
            ).contains(MainTab.Statistics),
        )
        assertFalse(
            appShellVisibleMainTabs(
                ReaderSettings(enableStatistics = true, showStatisticsTab = false),
            ).contains(MainTab.Statistics),
        )
        assertTrue(
            appShellVisibleMainTabs(
                ReaderSettings(enableStatistics = true, showStatisticsTab = true),
            ).contains(MainTab.Statistics),
        )
    }

    @Test
    fun hiddenSelectedStatisticsTabFallsBackToBooks() {
        assertEquals(
            MainTab.Books,
            coerceAvailableMainTab(
                requestedTab = MainTab.Statistics,
                visibleTabs = appShellVisibleMainTabs(
                    ReaderSettings(enableStatistics = true, showStatisticsTab = false),
                ),
            ),
        )
    }

    @Test
    fun dictionaryDefaultRouteAppliesOnceOnlyFromInitialBooksRoute() = runBlocking {
        val stateHolder = AppLaunchRouteStateHolder()
        val backStack = mutableListOf<NavKey>(AppRoute.BooksRoute)

        assertEquals(
            AppRoute.DictionaryRoute,
            stateHolder.defaultRouteAfterSettingsLoad(
                readerSettings = ReaderSettings(openLastReadBookOnLaunch = false),
                dictionarySettings = DictionarySettings(dictionaryTabDefault = true),
                hasPendingImport = false,
                isBooksTabSelected = true,
                backStack = backStack,
                recentBookIdProvider = { "book-a" },
            ),
        )
        assertNull(
            stateHolder.defaultRouteAfterSettingsLoad(
                readerSettings = ReaderSettings(openLastReadBookOnLaunch = false),
                dictionarySettings = DictionarySettings(dictionaryTabDefault = true),
                hasPendingImport = false,
                isBooksTabSelected = true,
                backStack = backStack,
                recentBookIdProvider = { "book-a" },
            ),
        )
    }

    @Test
    fun openLastReadBookRouteTakesPriorityOverDictionaryDefaultRoute() = runBlocking {
        val stateHolder = AppLaunchRouteStateHolder()

        assertEquals(
            AppRoute.ReaderRoute("book-a"),
            stateHolder.defaultRouteAfterSettingsLoad(
                readerSettings = ReaderSettings(openLastReadBookOnLaunch = true),
                dictionarySettings = DictionarySettings(dictionaryTabDefault = true),
                hasPendingImport = false,
                isBooksTabSelected = true,
                backStack = mutableListOf(AppRoute.BooksRoute),
                recentBookIdProvider = { "book-a" },
            ),
        )
    }

    @Test
    fun openLastReadBookRouteFallsBackToDictionaryDefaultWhenNoRecentBookExists() = runBlocking {
        val stateHolder = AppLaunchRouteStateHolder()

        assertEquals(
            AppRoute.DictionaryRoute,
            stateHolder.defaultRouteAfterSettingsLoad(
                readerSettings = ReaderSettings(openLastReadBookOnLaunch = true),
                dictionarySettings = DictionarySettings(dictionaryTabDefault = true),
                hasPendingImport = false,
                isBooksTabSelected = true,
                backStack = mutableListOf(AppRoute.BooksRoute),
                recentBookIdProvider = { null },
            ),
        )
    }

    @Test
    fun launchDefaultRouteDoesNotQueryRecentBookWhenInitialRouteIsProtected() = runBlocking {
        val pendingImportStateHolder = AppLaunchRouteStateHolder()
        val nestedRouteStateHolder = AppLaunchRouteStateHolder()
        var recentBookQueries = 0

        assertNull(
            pendingImportStateHolder.defaultRouteAfterSettingsLoad(
                readerSettings = ReaderSettings(openLastReadBookOnLaunch = true),
                dictionarySettings = DictionarySettings(dictionaryTabDefault = true),
                hasPendingImport = true,
                isBooksTabSelected = true,
                backStack = mutableListOf(AppRoute.BooksRoute),
                recentBookIdProvider = {
                    recentBookQueries += 1
                    "book-a"
                },
            ),
        )
        assertNull(
            nestedRouteStateHolder.defaultRouteAfterSettingsLoad(
                readerSettings = ReaderSettings(openLastReadBookOnLaunch = true),
                dictionarySettings = DictionarySettings(dictionaryTabDefault = true),
                hasPendingImport = false,
                isBooksTabSelected = true,
                backStack = mutableListOf(AppRoute.BooksRoute, AppRoute.ReaderRoute("book-a")),
                recentBookIdProvider = {
                    recentBookQueries += 1
                    "book-a"
                },
            ),
        )
        assertEquals(0, recentBookQueries)
    }

    @Test
    fun dictionaryDefaultRouteDoesNotOverridePendingImportOrNestedRoutes() = runBlocking {
        val pendingImportStateHolder = AppLaunchRouteStateHolder()
        val nestedRouteStateHolder = AppLaunchRouteStateHolder()

        assertNull(
            pendingImportStateHolder.defaultRouteAfterSettingsLoad(
                readerSettings = ReaderSettings(openLastReadBookOnLaunch = false),
                dictionarySettings = DictionarySettings(dictionaryTabDefault = true),
                hasPendingImport = true,
                isBooksTabSelected = true,
                backStack = mutableListOf(AppRoute.BooksRoute),
                recentBookIdProvider = { "book-a" },
            ),
        )
        assertNull(
            nestedRouteStateHolder.defaultRouteAfterSettingsLoad(
                readerSettings = ReaderSettings(openLastReadBookOnLaunch = false),
                dictionarySettings = DictionarySettings(dictionaryTabDefault = true),
                hasPendingImport = false,
                isBooksTabSelected = true,
                backStack = mutableListOf(AppRoute.BooksRoute, AppRoute.ReaderRoute("book-a")),
                recentBookIdProvider = { "book-a" },
            ),
        )
    }

    @Test
    fun dictionaryDefaultRouteDoesNotOverrideRestoredNonBooksTab() = runBlocking {
        val stateHolder = AppLaunchRouteStateHolder()

        assertNull(
            stateHolder.defaultRouteAfterSettingsLoad(
                readerSettings = ReaderSettings(openLastReadBookOnLaunch = true),
                dictionarySettings = DictionarySettings(dictionaryTabDefault = true),
                hasPendingImport = false,
                isBooksTabSelected = false,
                backStack = mutableListOf(AppRoute.BooksRoute),
                recentBookIdProvider = { "book-a" },
            ),
        )
    }

    @Test
    fun pendingImportCoordinatorRoutesOnlyWhenImportIsPending() {
        val coordinator = PendingImportRouteCoordinator()
        val backStack = mutableListOf<NavKey>(AppRoute.SettingsRoute, AppRoute.ReaderRoute("book-a"))
        var readerRouteRemovedCount = 0

        coordinator.routePendingImport(hasPendingImport = false, backStack = backStack)
        assertEquals(listOf(AppRoute.SettingsRoute, AppRoute.ReaderRoute("book-a")), backStack)
        assertEquals(0, readerRouteRemovedCount)

        coordinator.routePendingImport(
            hasPendingImport = true,
            backStack = backStack,
            onReaderRouteRemoved = { readerRouteRemovedCount += 1 },
        )
        assertEquals(listOf(AppRoute.BooksRoute), backStack)
        assertEquals(1, readerRouteRemovedCount)
    }

}
