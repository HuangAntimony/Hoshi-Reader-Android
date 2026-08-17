package moe.antimony.hoshi.navigation

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.features.bookshelf.MainTab
import moe.antimony.hoshi.features.dictionary.DictionarySettings
import moe.antimony.hoshi.features.reader.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        assertEquals(
            MainTab.entries,
            appShellVisibleMainTabs(
                ReaderSettings(enableStatistics = true, showStatisticsTab = true),
            ),
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
    fun mainShellPolicyAppliesOnlyToTopLevelRootRoutes() {
        assertTrue(appRouteUsesMainShell(AppRoute.MainRoute))
        assertTrue(appRouteUsesMainShell(AppRoute.BooksRoute))
        assertTrue(appRouteUsesMainShell(AppRoute.DictionaryRoute))
        assertTrue(appRouteUsesMainShell(AppRoute.StatisticsRoute))
        assertTrue(appRouteUsesMainShell(AppRoute.SettingsRoute))
        assertFalse(appRouteUsesMainShell(AppRoute.ReaderRoute("book-a")))
        assertFalse(appRouteUsesMainShell(AppRoute.SettingsDetailRoute(SettingsDetailSection.About)))
    }

    @Test
    fun mainShellRouteUsesOnlyCurrentSceneTopRoute() {
        assertEquals(
            AppRoute.StatisticsRoute,
            appShellMainShellRoute(
                listOf(
                    testNavEntry(AppRoute.StatisticsRoute),
                ),
            ),
        )
        assertEquals(
            AppRoute.SettingsRoute,
            appShellMainShellRoute(
                listOf(
                    testNavEntry(AppRoute.SettingsRoute),
                ),
            ),
        )
        assertNull(
            appShellMainShellRoute(
                listOf(
                    testNavEntry(AppRoute.SettingsRoute),
                    testNavEntry(AppRoute.SettingsDetailRoute(SettingsDetailSection.About)),
                ),
            ),
        )
        assertNull(
            appShellMainShellRoute(
                listOf(
                    testNavEntry(AppRoute.BooksRoute),
                    testNavEntry(AppRoute.ReaderRoute("book-a")),
                ),
            ),
        )
    }

    @Test
    fun mainShellRouteMetadataKeepsContentKeySaveable() {
        val entry = testNavEntry(AppRoute.StatisticsRoute)

        assertTrue(entry.contentKey is String)
        assertEquals(AppRoute.StatisticsRoute, entry.metadata[AppShellRouteMetadataKey])
        assertEquals(AppRoute.StatisticsRoute, appShellMainShellRoute(listOf(entry)))
    }

    @Test
    fun mainShellSceneKeyIncludesDelegateSceneClass() {
        val firstScene = FirstTestScene(key = "same")
        val secondScene = SecondTestScene(key = "same")

        assertNotEquals(
            appShellMainShellSceneKey(firstScene),
            appShellMainShellSceneKey(secondScene),
        )
    }

    @Test
    fun hiddenStatisticsTabClearsStatisticsReaderRoutes() {
        val backStack = mutableListOf<NavKey>(
            AppRoute.StatisticsRoute,
            AppRoute.ReaderRoute("book-a"),
        )
        var readerRouteRemoved = false

        normalizeStatisticsBackStackForVisibleTabs(
            visibleTabs = listOf(MainTab.Books, MainTab.Dictionary, MainTab.Settings),
            statisticsBackStack = backStack,
            onReaderRouteRemoved = { readerRouteRemoved = true },
        )

        assertEquals(listOf(AppRoute.StatisticsRoute), backStack)
        assertTrue(readerRouteRemoved)
    }

    @Test
    fun visibleStatisticsTabKeepsStatisticsReaderRoutes() {
        val backStack = mutableListOf<NavKey>(
            AppRoute.StatisticsRoute,
            AppRoute.ReaderRoute("book-a"),
        )
        var readerRouteRemoved = false

        normalizeStatisticsBackStackForVisibleTabs(
            visibleTabs = MainTab.entries,
            statisticsBackStack = backStack,
            onReaderRouteRemoved = { readerRouteRemoved = true },
        )

        assertEquals(listOf(AppRoute.StatisticsRoute, AppRoute.ReaderRoute("book-a")), backStack)
        assertFalse(readerRouteRemoved)
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
    fun launchDefaultRouteDoesNotOverridePendingDictionaryLookup() = runBlocking {
        val stateHolder = AppLaunchRouteStateHolder()
        var recentBookQueries = 0

        assertNull(
            stateHolder.defaultRouteAfterSettingsLoad(
                readerSettings = ReaderSettings(openLastReadBookOnLaunch = true),
                dictionarySettings = DictionarySettings(dictionaryTabDefault = true),
                hasPendingImport = false,
                hasPendingDictionaryLookup = true,
                isBooksTabSelected = true,
                backStack = mutableListOf(AppRoute.BooksRoute),
                recentBookIdProvider = {
                    recentBookQueries += 1
                    "book-a"
                },
            ),
        )
        assertEquals(0, recentBookQueries)
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

private fun testNavEntry(route: AppRoute): NavEntry<NavKey> =
    NavEntry(route, metadata = appShellNavEntryMetadata(route)) {}

private class FirstTestScene(
    override val key: Any,
) : Scene<NavKey> {
    override val entries: List<NavEntry<NavKey>> = listOf(testNavEntry(AppRoute.BooksRoute))
    override val previousEntries: List<NavEntry<NavKey>> = emptyList()
    override val content: @Composable () -> Unit = {}
}

private class SecondTestScene(
    override val key: Any,
) : Scene<NavKey> {
    override val entries: List<NavEntry<NavKey>> = listOf(testNavEntry(AppRoute.BooksRoute))
    override val previousEntries: List<NavEntry<NavKey>> = emptyList()
    override val content: @Composable () -> Unit = {}
}
