package moe.antimony.hoshi.features.bookshelf

import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookInfo
import moe.antimony.hoshi.epub.BookStorage
import moe.antimony.hoshi.epub.Bookmark
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
    fun settingsGroupsMatchIosSettingsEntryOrder() {
        val groups = settingsGroups()

        assertEquals(
            listOf("Dictionaries", "Anki", "Appearance", "Advanced"),
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

        assertTrue(source.contains("CompositionLocalProvider(LocalOverscrollFactory provides null)"))
        assertTrue(source.contains(".padding(bottom = innerPadding.calculateBottomPadding())"))
        assertTrue(source.contains("contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp)"))
        assertTrue(source.contains("BookCoverBitmapCache.load(coverFile)"))
        assertTrue(source.contains("loadBookProgressById(entries, bookStorage)"))
    }

    @Test
    fun sasayakiMatchMenuOpensAdjustableMatchScreen() {
        val source = File("src/main/java/moe/antimony/hoshi/features/bookshelf/BookshelfView.kt").readText()

        assertTrue(source.contains("var sasayakiMatchEntry by remember { mutableStateOf<BookEntry?>(null) }"))
        assertTrue(source.contains("SasayakiMatchView("))
        assertTrue(source.contains("sasayakiMatchEntry = entry"))
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
    fun bookProgressIsLoadedOnceForShelfEntries() {
        val filesDir = Files.createTempDirectory("hoshi-bookshelf-progress").toFile()
        try {
            val storage = BookStorage(filesDir)
            val root = File(filesDir, "Books/book-a").also { it.mkdirs() }
            storage.saveBookInfo(root, BookInfo(characterCount = 200, chapterInfo = emptyMap()))
            storage.saveBookmark(
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

            assertEquals(0.25, loadBookProgressById(listOf(entry), storage).getValue("a"), 0.0001)
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

        assertTrue(advanced.contains("CenterAlignedTopAppBar("))
        assertFalse(advanced.contains("LargeTopAppBar("))
    }
}
