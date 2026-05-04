package moe.antimony.hoshi.navigation

import androidx.navigation3.runtime.NavKey
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.features.bookshelf.SasayakiMatchRequest
import moe.antimony.hoshi.features.dictionary.DictionarySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File

class AppShellCoordinatorTest {
    @Test
    fun dictionaryDefaultRouteAppliesOnceOnlyFromInitialBooksRoute() {
        val stateHolder = AppLaunchRouteStateHolder()
        val backStack = mutableListOf<NavKey>(AppRoute.BooksRoute)

        assertEquals(
            AppRoute.DictionaryRoute,
            stateHolder.defaultRouteAfterSettingsLoad(
                settings = DictionarySettings(dictionaryTabDefault = true),
                hasPendingImport = false,
                backStack = backStack,
            ),
        )
        assertNull(
            stateHolder.defaultRouteAfterSettingsLoad(
                settings = DictionarySettings(dictionaryTabDefault = true),
                hasPendingImport = false,
                backStack = backStack,
            ),
        )
    }

    @Test
    fun dictionaryDefaultRouteDoesNotOverridePendingImportOrNestedRoutes() {
        val pendingImportStateHolder = AppLaunchRouteStateHolder()
        val nestedRouteStateHolder = AppLaunchRouteStateHolder()

        assertNull(
            pendingImportStateHolder.defaultRouteAfterSettingsLoad(
                settings = DictionarySettings(dictionaryTabDefault = true),
                hasPendingImport = true,
                backStack = mutableListOf(AppRoute.BooksRoute),
            ),
        )
        assertNull(
            nestedRouteStateHolder.defaultRouteAfterSettingsLoad(
                settings = DictionarySettings(dictionaryTabDefault = true),
                hasPendingImport = false,
                backStack = mutableListOf(AppRoute.BooksRoute, AppRoute.ReaderRoute("book-a")),
            ),
        )
    }

    @Test
    fun pendingImportCoordinatorRoutesOnlyWhenImportIsPending() {
        val coordinator = PendingImportRouteCoordinator()
        val backStack = mutableListOf<NavKey>(AppRoute.SettingsRoute, AppRoute.ReaderRoute("book-a"))

        coordinator.routePendingImport(hasPendingImport = false, backStack = backStack)
        assertEquals(listOf(AppRoute.SettingsRoute, AppRoute.ReaderRoute("book-a")), backStack)

        coordinator.routePendingImport(hasPendingImport = true, backStack = backStack)
        assertEquals(listOf(AppRoute.BooksRoute), backStack)
    }

    @Test
    fun sasayakiMatchRequestStoreStoresRequestsByBookId() {
        val store = SasayakiMatchRequestStore()
        val request = SasayakiMatchRequest(
            bookId = "book-a",
            bookEntry = BookEntry(
                root = File("book-a"),
                metadata = BookMetadata(
                    id = "book-a",
                    title = "Book A",
                    cover = null,
                    folder = "book-a",
                    lastAccess = 0.0,
                ),
            ),
        )

        store.put(request)

        assertSame(request, store.get("book-a"))
        assertNull(store.get("missing"))
    }
}
