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
    fun bookshelfViewEmitsAppRouteEventsInsteadOfOwningDisplayRoutes() {
        val bookshelf = File("src/main/java/moe/antimony/hoshi/features/bookshelf/BookshelfView.kt").readText()
        val functionHeader = bookshelf.substringAfter("fun BookshelfView(")
            .substringBefore("modifier: Modifier")

        assertTrue(functionHeader.contains("onOpenReader: (ReaderOpenRequest) -> Unit"))
        assertTrue(functionHeader.contains("onOpenSasayakiMatch: (SasayakiMatchRequest) -> Unit"))
        assertFalse(bookshelf.contains("var selectedTab by remember"))
        assertFalse(bookshelf.contains("var settingsDestination by remember"))
        assertFalse(bookshelf.contains("var isReading by remember"))
        assertFalse(bookshelf.contains("ReaderWebView("))
        assertFalse(bookshelf.contains("SasayakiMatchView("))
    }
}
