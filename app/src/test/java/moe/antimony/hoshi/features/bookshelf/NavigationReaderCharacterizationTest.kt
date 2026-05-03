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
        val pendingImportEffect = bookshelf.substringAfter("LaunchedEffect(pendingImportUri)")
            .substringBefore("if (isReading && book != null)")

        assertTrue(manifest.contains("android:launchMode=\"singleTop\""))
        assertTrue(manifest.contains("android.intent.action.VIEW"))
        assertTrue(manifest.contains("application/epub+zip"))
        assertTrue(mainActivity.contains("private var pendingImportUri by mutableStateOf<Uri?>(null)"))
        assertTrue(mainActivity.contains("pendingImportUri = intent.importUri()"))
        assertTrue(mainActivity.contains("override fun onNewIntent(intent: Intent)"))
        assertTrue(mainActivity.contains("setIntent(intent)"))
        assertTrue(mainActivity.contains("this?.data?.takeIf { action == Intent.ACTION_VIEW }"))
        assertTrue(bookshelf.contains("pendingImportUri: Uri? = null"))
        assertTrue(pendingImportEffect.contains("val uri = pendingImportUri ?: return@LaunchedEffect"))
        assertTrue(pendingImportEffect.contains("onPendingImportConsumed()"))
        assertTrue(pendingImportEffect.contains("importBook(uri)"))
        assertTrue(pendingImportEffect.indexOf("onPendingImportConsumed()") < pendingImportEffect.indexOf("importBook(uri)"))
    }

    @Test
    fun readerEntryRestoresBookmarkAndClosesBackToBookshelfState() {
        val bookshelf = bookshelfSource()
        val readerBranch = bookshelf.substringAfter("if (isReading && book != null)")
            .substringBefore("sasayakiMatchEntry?.let")

        assertTrue(readerBranch.contains("ReaderWebView("))
        assertTrue(readerBranch.contains("book = requireNotNull(book)"))
        assertTrue(readerBranch.contains("bookRoot = selectedBookRoot"))
        assertTrue(readerBranch.contains("initialChapterIndex = bookmark?.chapterIndex ?: 0"))
        assertTrue(readerBranch.contains("initialProgress = bookmark?.progress ?: 0.0"))
        assertTrue(readerBranch.contains("onSaveBookmark = { chapterIndex, progress ->"))
        assertTrue(readerBranch.contains("bookStorage.saveBookmark(file, savedBookmark)"))
        assertTrue(readerBranch.contains("onClose = { isReading = false }"))
        assertTrue(readerBranch.contains("return"))
    }

    @Test
    fun settingsDetailScreensAreExclusiveEarlyReturnDestinations() {
        val bookshelf = bookshelfSource()
        val settingsScaffold = File("src/main/java/moe/antimony/hoshi/features/settings/SettingsDetailScaffold.kt")
            .readText()
        val settingsState = bookshelf.substringAfter("var selectedTab by remember")
            .substringBefore("var bookEntries by remember")
        val tabShell = bookshelf.substringAfter("HoshiMainShell(")
            .substringBefore("settingsDestination?.takeIf")

        assertTrue(settingsState.contains("dictionarySettingsStore.load().dictionaryTabDefault"))
        assertTrue(settingsState.contains("MainTab.Dictionary"))
        assertTrue(settingsState.contains("MainTab.Books"))
        assertTrue(settingsState.contains("var settingsDestination by remember { mutableStateOf<SettingsDestination?>(null) }"))
        assertTrue(tabShell.contains("selectedTab = it"))
        assertTrue(tabShell.contains("settingsDestination = null"))
        assertTrue(bookshelf.contains("if (settingsDestination == SettingsDestination.Dictionaries)"))
        assertTrue(bookshelf.contains("DictionaryView("))
        assertTrue(bookshelf.contains("ReaderAppearanceScreen("))
        assertTrue(bookshelf.contains("ReaderBehaviorScreen("))
        assertTrue(bookshelf.contains("AdvancedSettingsView("))
        assertTrue(bookshelf.contains("DiagnosticsView("))
        assertTrue(settingsScaffold.contains("BackHandler(onBack = onClose)"))
        assertTrue(settingsScaffold.contains("IconButton(onClick = onClose)"))
    }

    @Test
    fun readerBackAndMediaSessionReturnPathsAreCharacterized() {
        val reader = File("src/main/java/moe/antimony/hoshi/features/reader/ReaderWebView.kt").readText()
        val mediaSession = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiMediaSession.kt").readText()
        val contentIntent = mediaSession.substringAfter("private fun contentIntent()")
            .substringBefore("companion object")

        assertTrue(reader.contains("BackHandler(onBack = onClose)"))
        assertTrue(mediaSession.contains("session.setSessionActivity(contentIntent())"))
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
}
