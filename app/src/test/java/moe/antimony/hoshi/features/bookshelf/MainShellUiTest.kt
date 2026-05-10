package moe.antimony.hoshi.features.bookshelf

import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookInfo
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.BookShelf
import moe.antimony.hoshi.epub.BookSortOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MainShellUiTest {
    @Test
    fun mainTabsMatchIosOrder() {
        assertEquals(listOf("Books", "Dictionary", "Settings"), MainTab.entries.map { it.label })
    }

    @Test
    fun settingsGroupsIncludeAndroidReaderBehaviorEntry() {
        val groups = settingsGroups()

        assertEquals(
            listOf("Dictionaries", "Anki", "Appearance", "Behavior", "Advanced"),
            groups.first().map { it.label },
        )
        assertEquals(listOf("Report an Issue", "Diagnostics", "About"), groups.last().map { it.label })
    }

    @Test
    fun importedBooksAreDisplayedInUnshelvedSection() {
        val entries = listOf(
            BookEntry(
                root = File("book-a"),
                metadata = BookMetadata(
                    id = "a",
                    title = "屍人荘の殺人",
                    cover = null,
                    folder = "book-a",
                    lastAccess = 1.0,
                ),
            ),
        )

        val sections = bookshelfSections(entries)

        assertEquals(1, sections.size)
        assertEquals("Unshelved", sections.single().title)
        assertEquals(entries, sections.single().books)
    }

    @Test
    fun bookshelfSectionsMatchIosShelvesReadingAndUnshelvedOrder() {
        val unread = bookEntry(id = "unread", title = "Unread", lastAccess = 3.0)
        val reading = bookEntry(id = "reading", title = "Reading", lastAccess = 2.0)
        val shelved = bookEntry(id = "shelved", title = "Shelved", lastAccess = 1.0)
        val entries = listOf(unread, reading, shelved)

        val sections = bookshelfSections(
            entries = entries,
            shelves = listOf(
                BookShelf(name = "Manga", bookIds = listOf("shelved", "missing")),
            ),
            progressById = mapOf(
                "unread" to 0.0,
                "reading" to 0.5,
                "shelved" to 1.0,
            ),
            showReading = true,
            sortOption = BookSortOption.Recent,
        )

        assertEquals(listOf("Reading", "Manga", "Unshelved"), sections.map { it.title })
        assertEquals(true, sections[0].isReading)
        assertEquals(listOf("reading"), sections[0].books.map { it.metadata.id })
        assertEquals(listOf("shelved"), sections[1].books.map { it.metadata.id })
        assertEquals(listOf("unread", "reading"), sections[2].books.map { it.metadata.id })
    }

    @Test
    fun bookshelfSectionsSortEachSectionByTitleWhenRequested() {
        val z = bookEntry(id = "z", title = "Zeta", lastAccess = 2.0)
        val a = bookEntry(id = "a", title = "Alpha", lastAccess = 1.0)

        val sections = bookshelfSections(
            entries = listOf(z, a),
            shelves = emptyList(),
            progressById = emptyMap(),
            showReading = false,
            sortOption = BookSortOption.Title,
        )

        assertEquals(listOf("a", "z"), sections.single().books.map { it.metadata.id })
    }

    @Test
    fun compactWindowsUseBottomNavigationAndTwoBookColumns() {
        val spec = MainShellLayoutSpec.forWidthDp(360)

        assertEquals(MainShellNavigationLayout.BottomBar, spec.navigationLayout)
        assertEquals(64, spec.compactNavigationHeightDp)
        assertEquals(16, spec.pageHorizontalPaddingDp)
        assertEquals(2, spec.bookGridColumns(contentWidthDp = 360))
    }

    @Test
    fun bookshelfHeaderUsesCompactMaterialTypography() {
        val spec = MainShellLayoutSpec.forWidthDp(360)

        assertEquals(MainShellTextStyle.TitleLarge, spec.shelfTitleTextStyle)
        assertEquals(MainShellFontWeight.SemiBold, spec.shelfTitleFontWeight)
        assertEquals(MainShellTextStyle.TitleMedium, spec.shelfCountTextStyle)
        assertEquals(0, spec.shelfHeaderVerticalPaddingDp)
        assertEquals(0, spec.bookGridTopPaddingDp)
        assertEquals(12, spec.bookGridVerticalSpacingDp)
        assertEquals(0, spec.bookGridBottomPaddingDp)
        assertEquals(MainShellTextStyle.BodyLarge, spec.bookTitleTextStyle)
        assertEquals(MainShellFontWeight.Normal, spec.bookTitleFontWeight)
    }

    @Test
    fun mediumWindowsUseNavigationRailAndConstrainedBookGrid() {
        val spec = MainShellLayoutSpec.forWidthDp(700)

        assertEquals(MainShellNavigationLayout.NavigationRail, spec.navigationLayout)
        assertEquals(640, spec.contentMaxWidthDp)
        assertEquals(3, spec.bookGridColumns(contentWidthDp = 640))
    }

    @Test
    fun expandedWindowsUseNavigationRailAndCapBookGridColumns() {
        val spec = MainShellLayoutSpec.forWidthDp(1200)

        assertEquals(MainShellNavigationLayout.NavigationRail, spec.navigationLayout)
        assertEquals(1040, spec.contentMaxWidthDp)
        assertEquals(5, spec.bookGridColumns(contentWidthDp = 1040))
    }

    @Test
    fun bookshelfGridDisablesOverscrollAndKeepsDividerOnContentBoundary() {
        val source = File("src/main/java/moe/antimony/hoshi/features/bookshelf/BookshelfView.kt").readText()
        val repository = File("src/main/java/moe/antimony/hoshi/features/bookshelf/BookshelfRepository.kt").readText()

        assertTrue(source.contains("CompositionLocalProvider(LocalOverscrollFactory provides null)"))
        assertTrue(source.contains(".padding(bottom = innerPadding.calculateBottomPadding())"))
        assertTrue(source.contains("contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp)"))
        assertTrue(source.contains("BookCoverBitmapCache.load(coverFile)"))
        assertTrue(repository.contains("loadBookProgressById(entries, bookRepository)"))
    }

    @Test
    fun bookshelfDoesNotShowEmptyStateBeforeFirstLoadCompletes() {
        val source = File("src/main/java/moe/antimony/hoshi/features/bookshelf/BookshelfView.kt").readText()
        val booksTab = source.substringAfter("private fun BooksTab(")
            .substringBefore("@Composable\n@OptIn(ExperimentalMaterial3Api::class)\nprivate fun BooksTopAppBar")

        assertTrue(booksTab.contains("hasLoadedBooks: Boolean"))
        assertTrue(booksTab.contains("!hasLoadedBooks -> Box(Modifier.fillMaxSize())"))
        assertTrue(booksTab.contains("hasLoadedBooks && bookEntries.isEmpty() -> EmptyBooksView("))
    }

    @Test
    fun sasayakiMatchMenuOpensAdjustableMatchScreen() {
        val source = File("src/main/java/moe/antimony/hoshi/features/bookshelf/BookshelfView.kt").readText()
        val appShell = File("src/main/java/moe/antimony/hoshi/navigation/AppShell.kt").readText()

        assertTrue(source.contains("onOpenSasayakiMatch(SasayakiMatchRequest(entry.metadata.id, entry))"))
        assertTrue(appShell.contains("sasayakiMatchRequestStore.put(request)"))
        assertTrue(appShell.contains("backStack.openSasayakiMatchRoute(request.bookId)"))
        assertTrue(appShell.contains("SasayakiMatchView("))
        assertFalse(source.contains("SasayakiMatcher.match("))
        assertFalse(source.contains("searchWindow = 200"))
        assertFalse(source.contains("sasayakiMatcher.launch"))
    }

    @Test
    fun coverDecodeSampleSizeKeepsCoversNearTargetSize() {
        assertEquals(1, coverDecodeSampleSize(width = 600, height = 800, maxDimensionPx = 900))
        assertEquals(2, coverDecodeSampleSize(width = 1200, height = 1800, maxDimensionPx = 900))
        assertEquals(4, coverDecodeSampleSize(width = 2400, height = 3600, maxDimensionPx = 900))
    }

    @Test
    fun bookCoverPlaceholderUsesBookshelfBackground() {
        val source = File("src/main/java/moe/antimony/hoshi/features/bookshelf/BookshelfView.kt").readText()
        val coverCard = source.substringAfter("private fun BookCoverCard(")
            .substringBefore("private object BookCoverBitmapCache")

        assertTrue(coverCard.contains("MaterialTheme.colorScheme.background"))
        assertTrue(coverCard.contains(".background(coverPlaceholderColor)"))
        assertFalse(coverCard.contains(".background(Color.White)"))
        assertTrue(coverCard.contains("LocalHoshiDarkTheme.current"))
        assertTrue(coverCard.contains("Color.White.copy(alpha = 0.9f)"))
        assertTrue(coverCard.contains("Color.Black.copy(alpha = 0.18f)"))
        assertTrue(coverCard.contains("BorderStroke(1.dp, coverBorderColor)"))
        assertFalse(coverCard.contains("isSystemInDarkTheme()"))
    }

    @Test
    fun bookProgressIsLoadedOnceForShelfEntries() = runBlocking {
        val filesDir = Files.createTempDirectory("hoshi-bookshelf-progress").toFile()
        try {
            val repository = BookRepository(filesDir)
            val root = File(filesDir, "Books/book-a").also { it.mkdirs() }
            repository.saveBookInfo(root, BookInfo(characterCount = 200, chapterInfo = emptyMap()))
            repository.saveBookmark(
                root,
                Bookmark(
                    chapterIndex = 0,
                    progress = 0.0,
                    characterCount = 50,
                ),
            )
            val entry = BookEntry(
                root = root,
                metadata = BookMetadata(
                    id = "a",
                    title = "Book A",
                    cover = null,
                    folder = "book-a",
                    lastAccess = 1.0,
                ),
            )

            assertEquals(0.25, loadBookProgressById(listOf(entry), repository).getValue("a"), 0.0001)
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun mainSurfacesAvoidExpensiveDecorativeEffects() {
        val sourceFiles = listOf(
            "src/main/java/moe/antimony/hoshi/features/bookshelf/BookshelfView.kt",
            "src/main/java/moe/antimony/hoshi/features/dictionary/DictionarySearchView.kt",
            "src/main/java/moe/antimony/hoshi/features/dictionary/LookupPopupView.kt",
            "src/main/java/moe/antimony/hoshi/features/reader/ReaderWebView.kt",
        )
        val source = sourceFiles.joinToString("\n") { File(it).readText() }

        assertFalse(source.contains(".shadow("))
        assertFalse(source.contains("webView.animate()"))
        assertFalse(source.contains("tonalElevation = 8.dp"))
        assertFalse(source.contains("shadowElevation = 8.dp"))
    }

    @Test
    fun bookshelfProgressUsesHighContrastEInkPaletteWhenEnabled() {
        val source = File("src/main/java/moe/antimony/hoshi/features/bookshelf/BookshelfView.kt").readText()
        val progress = source.substringAfter("private fun ReadingProgressPill(")
            .substringBefore("@Composable\n@OptIn(ExperimentalMaterial3Api::class)\ninternal fun SettingsTab")

        assertTrue(progress.contains("LocalHoshiEInkMode.current"))
        assertTrue(progress.contains("progressTrackColor"))
        assertTrue(progress.contains("progressFillColor"))
        assertTrue(progress.contains("progressBorderColor"))
    }

    @Test
    fun bottomTabIconsUseNavigationItemContentColor() {
        val source = File("src/main/java/moe/antimony/hoshi/features/bookshelf/BookshelfView.kt").readText()
        val glyph = source.substringAfter("private fun BottomTabGlyph(")
            .substringBefore("@Composable\nprivate fun SettingsGlyph")

        assertTrue(glyph.contains("LocalContentColor.current"))
        assertFalse(glyph.contains("tint = MaterialTheme.colorScheme.onSurface"))
    }

    @Test
    fun settingsRowsUseMaterialFullWidthDividers() {
        val source = File("src/main/java/moe/antimony/hoshi/features/bookshelf/BookshelfView.kt").readText()

        assertFalse(source.contains("Modifier.padding(start = 72.dp)"))
    }

    @Test
    fun navigationRailDoesNotAddDuplicateContentInset() {
        val source = File("src/main/java/moe/antimony/hoshi/features/bookshelf/BookshelfView.kt").readText()

        assertFalse(source.contains("NavigationRailInset"))
        assertFalse(source.contains(".padding(start = NavigationRailInset)"))
    }

    @Test
    fun bookshelfContentStartsAfterNavigationRailOnLargeScreens() {
        val source = File("src/main/java/moe/antimony/hoshi/features/bookshelf/BookshelfView.kt").readText()

        assertTrue(source.contains("layoutSpec.navigationLayout == MainShellNavigationLayout.NavigationRail"))
        assertTrue(source.contains("Alignment.TopStart"))
        assertTrue(source.contains(".align(bookContentAlignment)"))
    }

    @Test
    fun bookshelfToolbarMatchesIosLeadingAndTrailingActionGrouping() {
        val source = File("src/main/java/moe/antimony/hoshi/features/bookshelf/BookshelfView.kt").readText()
        val topBar = source.substringAfter("private fun BooksTopAppBar(")
            .substringBefore("@Composable\nprivate fun BookshelfSectionHeader")
        val actions = topBar.substringAfter("actions = {")
            .substringBefore("colors = TopAppBarDefaults")

        assertTrue(topBar.contains("SortMenuHeader(text = \"Sorting by...\")"))
        assertFalse(topBar.contains("enabled = false,\n                                onClick = {},"))
        assertTrue(topBar.contains("sortOption: BookSortOption"))
        assertTrue(topBar.contains("HorizontalDivider()"))
        assertTrue(topBar.contains("trailingIcon = selectedSortIcon(BookSortOption.Recent, sortOption)"))
        assertTrue(topBar.contains("trailingIcon = selectedSortIcon(BookSortOption.Title, sortOption)"))
        assertTrue(topBar.contains("navigationIcon = {\n            if (isSelecting)"))
        assertTrue(topBar.contains("Row"))
        assertTrue(topBar.indexOf("contentDescription = \"Select books\"") < topBar.indexOf("actions = {"))
        assertFalse(actions.contains("contentDescription = \"Select books\""))
        assertTrue(actions.contains("contentDescription = \"Manage Shelves\""))
        assertTrue(actions.contains("contentDescription = \"Import EPUB\""))
    }

    @Test
    fun bookContextMenuTargetDistinguishesReadingCopyFromShelfCopy() {
        val book = bookEntry(id = "same-book", title = "Same Book", lastAccess = 1.0)
        val readingSection = BookshelfSectionModel(
            title = "Reading",
            books = listOf(book),
            isReading = true,
        )
        val shelfSection = BookshelfSectionModel(
            title = "Manga",
            books = listOf(book),
            shelfName = "Manga",
        )

        val readingTarget = bookContextMenuTarget(readingSection, book)
        val shelfTarget = bookContextMenuTarget(shelfSection, book)

        assertFalse(readingTarget == shelfTarget)
        assertTrue(isBookContextMenuExpanded(readingTarget, readingSection, book))
        assertFalse(isBookContextMenuExpanded(readingTarget, shelfSection, book))
    }

    @Test
    fun bookContextMenuUsesMoveSubmenuInsteadOfListingShelvesAtTopLevel() {
        val source = File("src/main/java/moe/antimony/hoshi/features/bookshelf/BookshelfView.kt").readText()
        val menu = source.substringAfter("private fun BookContextMenu(")
            .substringBefore("@Composable\nprivate fun ShelfManagementDialog")
        val topLevelMoveBlock = menu.substringAfter("if (!hideMove) {")
            .substringBefore("if (sasayakiEnabled)")

        assertTrue(menu.contains("var moveMenuExpanded by remember"))
        assertTrue(menu.contains("MoveDestinationMenu("))
        assertTrue(topLevelMoveBlock.contains("onClick = { moveMenuExpanded = true }"))
        assertFalse(topLevelMoveBlock.contains("text = { Text(\"None\") }"))
        assertFalse(topLevelMoveBlock.contains("shelves.forEach"))
    }

    @Test
    fun mainShellDoesNotDoubleApplyTopSystemInsets() {
        val source = File("src/main/java/moe/antimony/hoshi/features/bookshelf/BookshelfView.kt").readText()
        val shell = source.substringAfter("internal fun HoshiMainShell(")
            .substringBefore("internal const val CompactNavigationBarTag")

        assertTrue(shell.contains("contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp)"))
    }

    @Test
    fun advancedSettingsUsesCompactHeader() {
        val source = File("src/main/java/moe/antimony/hoshi/features/audio/AudioView.kt").readText()
        val advanced = source.substringAfter("fun AdvancedSettingsView(")
            .substringBefore("@OptIn(ExperimentalMaterial3Api::class)\n@Composable\nfun AudioSettingsView")

        assertTrue(advanced.contains("SettingsDetailScaffold("))
        assertFalse(advanced.contains("LargeTopAppBar("))
    }

    private fun bookEntry(id: String, title: String, lastAccess: Double): BookEntry =
        BookEntry(
            root = File(id),
            metadata = BookMetadata(
                id = id,
                title = title,
                cover = null,
                folder = id,
                lastAccess = lastAccess,
            ),
        )
}
