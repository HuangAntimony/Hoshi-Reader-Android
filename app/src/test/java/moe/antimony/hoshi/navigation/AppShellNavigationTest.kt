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
    fun ankiSettingsPlaceholderStaysOutsideNavigation3DetailRoutes() {
        val appShell = File("src/main/java/moe/antimony/hoshi/navigation/AppShell.kt").readText()
        val appRoute = File("src/main/java/moe/antimony/hoshi/navigation/AppRoute.kt").readText()
        val settingsRoute = appShell.substringAfter("AppRoute.SettingsRoute ->")
            .substringBefore("is AppRoute.SettingsDetailRoute")
        val sectionMapping = appShell.substringAfter("private fun SettingsDestination.toSection()")
            .substringBefore("private fun SettingsDetailSection.placeholderTitle()")

        assertFalse(appRoute.contains("Anki,"))
        assertTrue(settingsRoute.contains("SettingsDestination.Anki -> showAnkiPlaceholder = true"))
        assertTrue(
            sectionMapping.contains(
                "SettingsDestination.Anki -> error(\"Anki placeholder is handled outside Navigation3.\")",
            ),
        )
    }

    @Test
    fun appShellReadsLatestSettingsInsideSavedNavEntries() {
        val appShell = File("src/main/java/moe/antimony/hoshi/navigation/AppShell.kt").readText()

        assertTrue(appShell.contains("rememberUpdatedState(readerSettings)"))
        assertTrue(appShell.contains("readerSettings = currentReaderSettings"))
        assertTrue(appShell.contains("onReaderSettingsChange = currentOnReaderSettingsChange"))
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
        assertTrue(appShell.contains("ReaderRouteStateHolder(bookRepository)"))
        assertTrue(readerDestination.contains("stateHolder.load(bookId)"))
        assertTrue(readerBranch.contains("bookId = route.bookId"))
    }
}
