package moe.antimony.hoshi.features.bookshelf

import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookInfo
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.BookShelf
import moe.antimony.hoshi.epub.BookSortOption
import moe.antimony.hoshi.features.sync.DriveAuthStatus
import moe.antimony.hoshi.features.sync.DriveFile
import moe.antimony.hoshi.features.sync.DriveSyncFiles
import moe.antimony.hoshi.features.sync.SyncSettings
import moe.antimony.hoshi.profiles.HoshiProfile
import moe.antimony.hoshi.profiles.ProfileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MainShellUiTest {
    @Test
    fun mainTabsMatchIosOrder() {
        assertEquals(listOf(MainTab.Books, MainTab.Dictionary, MainTab.Statistics, MainTab.Settings), MainTab.entries)
        assertEquals(
            listOf(
                R.string.main_tab_books,
                R.string.main_tab_dictionary,
                R.string.main_tab_statistics,
                R.string.main_tab_settings,
            ),
            MainTab.entries.map { it.labelRes },
        )
    }

    @Test
    fun settingsGroupsIncludeAndroidReaderBehaviorEntry() {
        val groups = settingsGroups()

        assertEquals(
            listOf(
                R.string.settings_profiles,
                R.string.settings_dictionaries,
                R.string.settings_anki,
                R.string.settings_appearance,
                R.string.settings_behavior,
                R.string.settings_advanced,
            ),
            groups.first().map { it.labelRes },
        )
        assertEquals(
            listOf(R.string.settings_report_issue, R.string.settings_diagnostics, R.string.settings_about),
            groups.last().map { it.labelRes },
        )
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
        assertTrue(sections[0].isCollapsible)
        assertEquals(listOf("reading"), sections[0].books.map { it.metadata.id })
        assertEquals(listOf("shelved"), sections[1].books.map { it.metadata.id })
        assertEquals(listOf("unread", "reading"), sections[2].books.map { it.metadata.id })
    }

    @Test
    fun googleDriveSectionIsInsertedBeforeUnshelvedLikeIos() {
        val unread = bookEntry(id = "unread", title = "Unread", lastAccess = 3.0)
        val shelved = bookEntry(id = "shelved", title = "Shelved", lastAccess = 1.0)

        val sections = bookshelfSections(
            entries = listOf(unread, shelved),
            shelves = listOf(BookShelf(name = "Manga", bookIds = listOf("shelved"))),
        )

        assertEquals(listOf("Manga", "Unshelved"), sections.map { it.title })
        assertEquals(1, googleDriveSectionInsertionIndex(sections))
    }

    @Test
    fun googleDriveSectionPresentationMatchesIosCollapsedSelectionBehavior() {
        val collapsed = googleDriveSectionPresentation(
            shelfExpansionState = emptyMap(),
            isSelecting = false,
        )
        val expanded = googleDriveSectionPresentation(
            shelfExpansionState = mapOf(GoogleDriveSectionCollapseKey to true),
            isSelecting = false,
        )
        val selecting = googleDriveSectionPresentation(
            shelfExpansionState = mapOf(GoogleDriveSectionCollapseKey to true),
            isSelecting = true,
        )

        assertTrue(collapsed.isCollapsible)
        assertFalse(collapsed.isExpanded)
        assertEquals(1.0f, collapsed.alpha)
        assertTrue(collapsed.allowsHitTesting)
        assertTrue(expanded.isExpanded)
        assertFalse(selecting.isExpanded)
        assertEquals(0.4f, selecting.alpha)
        assertFalse(selecting.allowsHitTesting)
    }

    @Test
    fun bookshelfPullRefreshIsEnabledOnlyForLoadedSyncShelfLikeIos() {
        assertTrue(
            shouldEnableBookshelfPullRefresh(
                syncSettings = SyncSettings(enabled = true),
                authStatus = DriveAuthStatus.Connected,
                hasLoadedBooks = true,
                isSelecting = false,
                fileTaskBlocked = false,
            ),
        )
        assertFalse(
            shouldEnableBookshelfPullRefresh(
                syncSettings = SyncSettings(enabled = false),
                authStatus = DriveAuthStatus.Connected,
                hasLoadedBooks = true,
                isSelecting = false,
                fileTaskBlocked = false,
            ),
        )
        assertFalse(
            shouldEnableBookshelfPullRefresh(
                syncSettings = SyncSettings(enabled = true),
                authStatus = DriveAuthStatus.NotConnected,
                hasLoadedBooks = true,
                isSelecting = false,
                fileTaskBlocked = false,
            ),
        )
        assertFalse(
            shouldEnableBookshelfPullRefresh(
                syncSettings = SyncSettings(enabled = true),
                authStatus = DriveAuthStatus.Connected,
                hasLoadedBooks = false,
                isSelecting = false,
                fileTaskBlocked = false,
            ),
        )
        assertFalse(
            shouldEnableBookshelfPullRefresh(
                syncSettings = SyncSettings(enabled = true),
                authStatus = DriveAuthStatus.Connected,
                hasLoadedBooks = true,
                isSelecting = true,
                fileTaskBlocked = false,
            ),
        )
        assertFalse(
            shouldEnableBookshelfPullRefresh(
                syncSettings = SyncSettings(enabled = true),
                authStatus = DriveAuthStatus.Connected,
                hasLoadedBooks = true,
                isSelecting = false,
                fileTaskBlocked = true,
            ),
        )
    }

    @Test
    fun remoteBookLongPressTargetsContextMenuBeforeDeleteLikeIos() {
        val remote = remoteEntry("drive-folder", "Remote Book")
        val target = remoteBookContextMenuTarget(remote)

        assertTrue(isRemoteBookContextMenuExpanded(target, remote))
        assertFalse(isRemoteBookContextMenuExpanded(target, remoteEntry("other-folder", "Other")))
    }

    @Test
    fun virtualReadingShelfAndUserReadingShelfHaveDistinctLayoutKeys() {
        val reading = bookEntry(id = "reading", title = "Reading", lastAccess = 2.0)
        val shelved = bookEntry(id = "shelved", title = "Shelved", lastAccess = 1.0)

        val sections = bookshelfSections(
            entries = listOf(reading, shelved),
            shelves = listOf(BookShelf(name = "Reading", bookIds = listOf("shelved"))),
            progressById = mapOf("reading" to 0.5),
            showReading = true,
            sortOption = BookSortOption.Recent,
        )

        assertEquals(listOf("Reading", "Reading", "Unshelved"), sections.map { it.title })
        assertEquals(sections.size, sections.map { it.layoutKey }.toSet().size)
        assertEquals("__reading__", sections[0].layoutKey)
        assertEquals("shelf:Reading", sections[1].layoutKey)
        assertEquals("unshelved", sections[2].layoutKey)
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
    fun bookshelfSectionsSortEachSectionByRenamedDisplayTitleWhenRequested() {
        val originalA = bookEntry(id = "original-a", title = "Alpha", lastAccess = 1.0)
        val renamedToZ = bookEntry(id = "renamed", title = "Beta", lastAccess = 2.0, renamedTitle = "Zeta")

        val sections = bookshelfSections(
            entries = listOf(renamedToZ, originalA),
            shelves = emptyList(),
            progressById = emptyMap(),
            showReading = false,
            sortOption = BookSortOption.Title,
        )

        assertEquals(listOf("original-a", "renamed"), sections.single().books.map { it.metadata.id })
    }

    @Test
    fun compactWindowsUseBottomNavigationAndTwoBookColumns() {
        val spec = MainShellLayoutSpec.forWidthDp(360)

        assertEquals(MainShellNavigationLayout.BottomBar, spec.navigationLayout)
        assertEquals(64, spec.compactNavigationHeightDp)
        assertEquals(16, spec.pageHorizontalPaddingDp)
        assertEquals(2, spec.bookGridColumns(contentWidthDp = 360))
        assertEquals(4, spec.collapsedShelfPreviewColumns(contentWidthDp = 360))
        assertTrue(spec.collapsedShelfPreviewCoverWidthDp(contentWidthDp = 360) > 64)
        assertTrue(spec.collapsedShelfPreviewColumns(contentWidthDp = 360) > spec.bookGridColumns(contentWidthDp = 360))
    }

    @Test
    fun landscapeWindowsShowMoreCollapsedShelfPreviews() {
        val spec = MainShellLayoutSpec.forWidthDp(800)
        val contentWidth = spec.constrainedContentWidthDp(800)

        assertEquals(MainShellNavigationLayout.NavigationRail, spec.navigationLayout)
        assertTrue(spec.collapsedShelfPreviewColumns(contentWidth) > 4)
        assertTrue(spec.collapsedShelfPreviewCoverWidthDp(contentWidth) >= CollapsedShelfCoverTargetWidthDp)
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
    fun bookshelfHeaderKeepsTrailingMetadataVisibleForLongShelfNames() {
        val textLayout = bookshelfHeaderTextLayout()

        assertEquals(1, textLayout.titleMaxLines)
        assertEquals(MainShellTextOverflow.Ellipsis, textLayout.titleOverflow)
        assertTrue(textLayout.titleUsesRemainingWidth)
        assertEquals(1, textLayout.countMaxLines)
        assertFalse(textLayout.countSoftWrap)
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
    fun coverDecodeSampleSizeKeepsCoversNearTargetSize() {
        assertEquals(1, coverDecodeSampleSize(width = 600, height = 800, maxDimensionPx = 768))
        assertEquals(2, coverDecodeSampleSize(width = 1200, height = 1800, maxDimensionPx = 768))
        assertEquals(4, coverDecodeSampleSize(width = 2400, height = 3600, maxDimensionPx = 768))
    }

    @Test
    fun coverThumbnailSizeScalesLongestEdgeToTargetWithoutUpscaling() {
        assertEquals(CoverThumbnailSize(width = 576, height = 768), coverThumbnailSize(600, 800, 768))
        assertEquals(CoverThumbnailSize(width = 512, height = 768), coverThumbnailSize(1200, 1800, 768))
        assertEquals(CoverThumbnailSize(width = 300, height = 400), coverThumbnailSize(300, 400, 768))
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
    fun bookshelfProgressShowsCompletedBooksAsRead() {
        assertFalse(isBookCompleted(progress = 0.998))
        assertTrue(isBookCompleted(progress = 0.999))
        assertTrue(isBookCompleted(progress = 1.0))
        assertEquals("99.8%", bookshelfProgressText(progress = 0.998))
        assertEquals("100.0%", bookshelfProgressText(progress = 0.999))
        assertEquals("100.0%", bookshelfProgressText(progress = 1.0))
    }

    @Test
    fun unknownBookProfileSelectionFallsBackToAutomatic() {
        val state = ProfileState(
            profiles = listOf(
                HoshiProfile(id = "default-ja", name = "Japanese", dictionaryLanguageId = "ja", isDefault = true),
                HoshiProfile(id = "english", name = "English", dictionaryLanguageId = "en"),
            ),
            defaultProfileId = "default-ja",
            globalActiveProfileId = "default-ja",
            loadedProfileId = null,
            primaryProfileIdsByLanguage = emptyMap(),
        )

        assertNull(selectedBookProfileId("deleted-profile", state))
        assertNull(selectedBookProfileId(null, state))
        assertEquals("english", selectedBookProfileId("english", state))
    }

    private fun bookEntry(
        id: String,
        title: String,
        lastAccess: Double,
        renamedTitle: String? = null,
    ): BookEntry =
        BookEntry(
            root = File(id),
            metadata = BookMetadata(
                id = id,
                title = title,
                renamedTitle = renamedTitle,
                cover = null,
                folder = id,
                lastAccess = lastAccess,
            ),
        )

    private fun remoteEntry(id: String, title: String): RemoteBookEntry =
        RemoteBookEntry(
            id = id,
            folderId = id,
            folderName = title,
            title = title,
            syncFiles = DriveSyncFiles(
                bookData = DriveFile("bookdata-$id", "bookdata_1_6_10_1000_1000.zip"),
                progress = null,
                statistics = null,
                audioBook = null,
            ),
        )
}
