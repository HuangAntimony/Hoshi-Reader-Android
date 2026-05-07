package moe.antimony.hoshi.features.bookshelf

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NavigationReaderCharacterizationTest {
    @Test
    fun externalViewIntentIsConsumedAsOnePendingBookshelfImport() {
        val mainActivity = File("src/main/java/moe/antimony/hoshi/MainActivity.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val bookshelf = bookshelfSource()
        val appShell = appShellSource()
        val pendingImportEffect = bookshelf.substringAfter("LaunchedEffect(pendingImportUri)")
            .substringBefore("BooksTab(")

        assertTrue(manifest.contains("android:launchMode=\"singleTop\""))
        assertTrue(manifest.contains("android.intent.action.VIEW"))
        assertTrue(manifest.contains("application/epub+zip"))
        assertTrue(mainActivity.contains("private var pendingImportUri by mutableStateOf<Uri?>(null)"))
        assertTrue(mainActivity.contains("pendingImportUri = intent.importUri()"))
        assertTrue(mainActivity.contains("override fun onNewIntent(intent: Intent)"))
        assertTrue(mainActivity.contains("setIntent(intent)"))
        assertTrue(mainActivity.contains("intent.importUri()?.let { pendingImportUri = it }"))
        assertTrue(mainActivity.contains("this?.data?.takeIf { action == Intent.ACTION_VIEW }"))
        assertTrue(appShell.contains("LaunchedEffect(pendingImportUri)"))
        assertTrue(appShell.contains("selectTopLevelRoute(AppRoute.BooksRoute)"))
        assertTrue(bookshelf.contains("pendingImportUri: Uri? = null"))
        assertTrue(pendingImportEffect.contains("val uri = pendingImportUri ?: return@LaunchedEffect"))
        assertTrue(pendingImportEffect.contains("onPendingImportConsumed()"))
        assertTrue(pendingImportEffect.contains("importBook(uri)"))
        assertTrue(pendingImportEffect.indexOf("onPendingImportConsumed()") < pendingImportEffect.indexOf("importBook(uri)"))
    }

    @Test
    fun readerEntryRestoresBookmarkAndClosesBackToBookshelfState() {
        val bookshelf = bookshelfSource()
        val bookshelfViewModel = File("src/main/java/moe/antimony/hoshi/features/bookshelf/BookshelfViewModel.kt").readText()
        val bookshelfRepository = File("src/main/java/moe/antimony/hoshi/features/bookshelf/BookshelfRepository.kt").readText()
        val appShell = appShellSource()
        val readerDestination = File("src/main/java/moe/antimony/hoshi/navigation/ReaderRouteDestination.kt").readText()
        val readerBranch = appShell.substringAfter("is AppRoute.ReaderRoute ->")
            .substringBefore("is AppRoute.SasayakiMatchRoute")

        assertTrue(bookshelf.contains("onOpenBook = booksViewModel::openBook"))
        assertTrue(bookshelf.contains("onOpenReader(bookId)"))
        assertTrue(bookshelfViewModel.contains("openReaderBookId = bookId"))
        assertTrue(bookshelfRepository.contains("override suspend fun openBook(entry: BookEntry): String"))
        assertTrue(bookshelfRepository.contains("override suspend fun importBook(uri: Uri): String"))
        assertTrue(appShell.contains("fun openReader(bookId: String)"))
        assertTrue(appShell.contains("backStack.openReaderRoute(bookId)"))
        assertTrue(readerDestination.contains("ReaderWebView("))
        assertTrue(readerBranch.contains("bookId = route.bookId"))
        assertTrue(readerDestination.contains("stateHolder.load(bookId)"))
        assertTrue(readerDestination.contains("initialChapterIndex = state.bookmark?.chapterIndex ?: 0"))
        assertTrue(readerDestination.contains("initialProgress = state.bookmark?.progress ?: 0.0"))
        assertTrue(readerDestination.contains("onSaveBookmark = { chapterIndex, progress ->"))
        assertTrue(readerDestination.contains("stateHolder.saveBookmark("))
        assertTrue(appShell.contains("onBookmarkSaved = readerBookmarkRefreshState::markDirty"))
        assertTrue(appShell.contains("onClose = ::closeReaderRoute"))
        assertTrue(readerDestination.contains("onClose = onClose"))
    }

    @Test
    fun settingsDetailScreensAreExclusiveEarlyReturnDestinations() {
        val bookshelf = bookshelfSource()
        val appShell = appShellSource()
        val launchRouteStateHolder = File("src/main/java/moe/antimony/hoshi/navigation/AppLaunchRouteStateHolder.kt")
            .readText()
        val settingsScaffold = File("src/main/java/moe/antimony/hoshi/features/settings/SettingsDetailScaffold.kt")
            .readText()
        val routeBranch = appShell.substringAfter("is AppRoute.SettingsDetailRoute ->")
            .substringBefore("is AppRoute.ReaderRoute")
        val settingsRoute = appShell.substringAfter("AppRoute.SettingsRoute ->")
            .substringBefore("is AppRoute.SettingsDetailRoute")

        assertTrue(appShell.contains("dictionarySettingsRepository.settings.collect"))
        assertTrue(launchRouteStateHolder.contains("settings.dictionaryTabDefault"))
        assertTrue(appShell.contains("rememberNavBackStack(initialRoute)"))
        assertTrue(settingsRoute.contains("openSettingsDetail(destination.toSection())"))
        assertTrue(routeBranch.contains("SettingsDetailDestination("))
        assertTrue(appShell.contains("SettingsDetailSection.Dictionaries -> DictionaryView("))
        assertTrue(appShell.contains("SettingsDetailSection.Appearance -> ReaderAppearanceScreen("))
        assertTrue(appShell.contains("SettingsDetailSection.Behavior -> ReaderBehaviorScreen("))
        assertTrue(appShell.contains("SettingsDetailSection.Advanced -> AdvancedSettingsView("))
        assertTrue(appShell.contains("SettingsDetailSection.Diagnostics -> DiagnosticsView("))
        assertTrue(bookshelf.contains("onDestination: (SettingsDestination) -> Unit"))
        assertTrue(settingsScaffold.contains("BackHandler(onBack = onClose)"))
        assertTrue(settingsScaffold.contains("IconButton(onClick = onClose)"))
    }

    @Test
    fun readerBackAndMediaSessionReturnPathsAreCharacterized() {
        val reader = File("src/main/java/moe/antimony/hoshi/features/reader/ReaderWebView.kt").readText()
        val mediaSession = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiMediaSession.kt").readText()
        val contentIntent = mediaSession.substringAfter("private fun contentIntent()")
            .substringBefore("companion object")

        assertTrue(reader.contains("fun closeReader()"))
        assertTrue(reader.contains("BackHandler(onBack = ::closeReader)"))
        assertTrue(mediaSession.contains("contentIntent()?.let { setSessionActivity(it) }"))
        assertTrue(contentIntent.contains("getLaunchIntentForPackage(appContext.packageName)"))
        assertTrue(contentIntent.contains("Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT"))
        assertTrue(contentIntent.contains("PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE"))
    }

    @Test
    fun manualNavigationReaderChecklistDocumentsEmulatorOnlyBaseline() {
        val checklist = File("../docs/navigation-reader-entry-characterization.md")

        assertTrue("R-000 manual navigation checklist is missing.", checklist.isFile)
        val text = checklist.readText()
        listOf(
            "Top-level tabs",
            "Settings detail return",
            "Reader open and close",
            "Android Back from reader",
            "External EPUB open",
            "Bookmark restoration",
            "Sasayaki media-session return",
        ).forEach { heading ->
            assertTrue("Checklist must cover $heading.", text.contains(heading))
        }
    }

    private fun bookshelfSource(): String =
        File("src/main/java/moe/antimony/hoshi/features/bookshelf/BookshelfView.kt").readText()

    private fun appShellSource(): String =
        File("src/main/java/moe/antimony/hoshi/navigation/AppShell.kt").readText()
}
