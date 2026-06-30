package moe.antimony.hoshi.navigation

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Test

class AppRouteBackStackTest {
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
