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

        backStack.routeExternalBookImport()

        assertEquals(listOf(AppRoute.BooksRoute), backStack)
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
    fun mediaSessionReturnDoesNotNeedAnAppRouteMutation() {
        val backStack = mutableListOf<NavKey>(
            AppRoute.BooksRoute,
            AppRoute.ReaderRoute("book-a"),
        )

        backStack.returnFromMediaSession()

        assertEquals(
            listOf(AppRoute.BooksRoute, AppRoute.ReaderRoute("book-a")),
            backStack,
        )
    }
}
