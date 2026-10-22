package moe.antimony.hoshi.navigation

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Test

class AppRouteBackStackTest {
    @Test fun queueRestoresAndReturnsThroughSyncSettings() {
        val routes = listOf(
            AppRoute.SettingsRoute,
            AppRoute.SettingsDetailRoute(SettingsDetailSection.Advanced),
            AppRoute.SyncSettingsRoute,
            AppRoute.SyncQueueRoute,
        )
        val restored = routes.mapTo(mutableListOf<NavKey>()) {
            kotlinx.serialization.json.Json.decodeFromString(AppRoute.serializer(),
                kotlinx.serialization.json.Json.encodeToString(AppRoute.serializer(), it))
        }
        assertEquals(routes, restored)
        org.junit.Assert.assertFalse(appRouteUsesMainShell(AppRoute.SyncQueueRoute))
        org.junit.Assert.assertFalse(appRouteUsesMainShell(AppRoute.SyncSettingsRoute))
        restored.popAppRoute()
        assertEquals(AppRoute.SyncSettingsRoute, restored.last())
        restored.popAppRoute()
        assertEquals(AppRoute.SettingsDetailRoute(SettingsDetailSection.Advanced), restored.last())
    }

    @Test fun ttsSectionsRestoreAndPopBackToTtsHome() {
        listOf(AppRoute.TtsVoiceSettingsRoute, AppRoute.TtsReplacementsSettingsRoute, AppRoute.TtsReadingSettingsRoute).forEach { route ->
            val serialized = kotlinx.serialization.json.Json.encodeToString(AppRoute.serializer(), route)
            assertEquals(route, kotlinx.serialization.json.Json.decodeFromString(AppRoute.serializer(), serialized))
            val stack = mutableListOf<NavKey>(AppRoute.SettingsRoute, AppRoute.TtsSettingsRoute, route)
            stack.popAppRoute(onReaderRouteRemoved = { error("TTS settings must not remove a reader") })
            assertEquals(listOf(AppRoute.SettingsRoute, AppRoute.TtsSettingsRoute), stack)
        }
    }

    @Test
    fun statisticsDetailsPopBackToTheirExistingDashboardEntry() {
        listOf(AppRoute.StatisticsSettingsRoute, AppRoute.StatisticsBookRoute("folder")).forEach { route ->
            val backStack = mutableListOf<NavKey>(AppRoute.StatisticsRoute, route)
            backStack.popAppRoute(onReaderRouteRemoved = { error("Statistics must not remove a reader") })
            assertEquals(listOf(AppRoute.StatisticsRoute), backStack)
        }
    }

    @Test
    fun duplicateCompletionReturnsFromTheSourceFormatToTheFormatList() {
        val backStack = mutableListOf<NavKey>(
            AppRoute.SettingsRoute,
            AppRoute.SettingsDetailRoute(SettingsDetailSection.Anki),
            AppRoute.AnkiCardFormatRoute("source"),
        )

        backStack.returnFromAnkiFormatDuplicate()

        assertEquals(
            listOf(
                AppRoute.SettingsRoute,
                AppRoute.SettingsDetailRoute(SettingsDetailSection.Anki),
            ),
            backStack,
        )
    }

    @Test
    fun externalBookImportReturnsToBooksBeforeTheBookshelfConsumesTheUri() {
        val backStack = mutableListOf<NavKey>(
            AppRoute.BooksRoute,
            AppRoute.ReaderRoute("book-a"),
        )
        var readerRouteRemoved = false

        backStack.routeExternalBookImport(onReaderRouteRemoved = { readerRouteRemoved = true })

        assertEquals(listOf(AppRoute.BooksRoute), backStack)
        assertEquals(true, readerRouteRemoved)
    }

    @Test
    fun readerOpenKeepsBooksAsTheSingleReturnDestination() {
        val backStack = mutableListOf<NavKey>(
            AppRoute.SettingsRoute,
            AppRoute.SettingsDetailRoute(SettingsDetailSection.Appearance),
        )

        backStack.openReaderRoute("book-a")

        assertEquals(
            listOf(AppRoute.BooksRoute, AppRoute.ReaderRoute("book-a")),
            backStack,
        )
    }

    @Test
    fun removingReaderRoutesKeepsStatisticsTopLevelRoute() {
        val backStack = mutableListOf<NavKey>(
            AppRoute.StatisticsRoute,
            AppRoute.ReaderRoute("book-a"),
        )
        var readerRouteRemoved = false

        backStack.removeReaderRoutes(onReaderRouteRemoved = { readerRouteRemoved = true })

        assertEquals(listOf(AppRoute.StatisticsRoute), backStack)
        assertEquals(true, readerRouteRemoved)
    }

    @Test
    fun backPopClearsReaderProfileWhenReaderRouteIsRemoved() {
        val backStack = mutableListOf<NavKey>(
            AppRoute.BooksRoute,
            AppRoute.ReaderRoute("book-a"),
        )
        var readerRouteRemoved = false

        backStack.popAppRoute(onReaderRouteRemoved = { readerRouteRemoved = true })

        assertEquals(listOf(AppRoute.BooksRoute), backStack)
        assertEquals(true, readerRouteRemoved)
    }

    @Test
    fun mediaSessionReturnDoesNotNeedAnAppRouteMutation() {
        val backStack = mutableListOf<NavKey>(
            AppRoute.BooksRoute,
            AppRoute.ReaderRoute("book-a"),
        )

        backStack.returnFromMediaSession("book-a")

        assertEquals(
            listOf(AppRoute.BooksRoute, AppRoute.ReaderRoute("book-a")),
            backStack,
        )
    }

    @Test
    fun mediaSessionReturnOpensActiveBookReaderFromBookshelf() {
        val backStack = mutableListOf<NavKey>(AppRoute.BooksRoute)

        backStack.returnFromMediaSession("book-a")

        assertEquals(
            listOf(AppRoute.BooksRoute, AppRoute.ReaderRoute("book-a")),
            backStack,
        )
    }
}
