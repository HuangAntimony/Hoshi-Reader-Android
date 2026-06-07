package moe.antimony.hoshi.features.bookshelf

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookShelf
import moe.antimony.hoshi.epub.BookSortOption
import moe.antimony.hoshi.features.sync.StatisticsSyncMode
import moe.antimony.hoshi.features.sync.DriveFile
import moe.antimony.hoshi.features.sync.DriveSyncFiles
import moe.antimony.hoshi.features.sync.GoogleDriveApiException
import moe.antimony.hoshi.features.sync.SyncDirection
import moe.antimony.hoshi.features.sync.SyncResult
import moe.antimony.hoshi.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookshelfViewModelTest {
    @Test
    fun initialStateWaitsForFirstShelfLoadBeforeShowingEmptyBooks() {
        val viewModel = BookshelfViewModel(FakeBookshelfRepository(), testScope())

        assertFalse(viewModel.uiState.value.hasLoadedBooks)
    }

    @Test
    fun reloadBooksPublishesEntriesProgressAndSasayakiState() {
        val entry = bookEntry("book-a")
        val coverSource = BookCoverSource(
            path = "/tmp/book-a/cover.jpg",
            cacheKey = "/tmp/book-a/cover.jpg:10:20",
        )
        val repository = FakeBookshelfRepository(
            entries = listOf(entry),
            progressById = mapOf("book-a" to 0.25),
            coverSourcesById = mapOf("book-a" to coverSource),
            shelves = listOf(BookShelf("Manga", listOf("book-a"))),
            settings = BookshelfSettings(sortOption = BookSortOption.Title, showReading = true),
        )
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.reloadBookEntries()
        viewModel.setSasayakiEnabled(true)

        assertEquals(listOf(entry), viewModel.uiState.value.bookEntries)
        assertEquals(mapOf("book-a" to 0.25), viewModel.uiState.value.bookProgressById)
        assertEquals(mapOf("book-a" to coverSource), viewModel.uiState.value.coverSourcesById)
        assertEquals(listOf(BookShelf("Manga", listOf("book-a"))), viewModel.uiState.value.shelves)
        assertEquals(BookSortOption.Title, viewModel.uiState.value.sortOption)
        assertTrue(viewModel.uiState.value.showReading)
        assertTrue(viewModel.uiState.value.hasLoadedBooks)
        assertTrue(viewModel.uiState.value.sasayakiEnabled)
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.errorMessage.testString())
    }

    @Test
    fun reloadBooksPublishesRemoteGoogleDriveBooksSeparatelyFromLocalEntries() {
        val remote = remoteEntry("drive-folder", "Remote Book")
        val repository = FakeBookshelfRepository(
            entries = listOf(bookEntry("local-book")),
            remoteEntries = listOf(remote),
            remoteProgressById = mapOf("drive-folder" to 0.5),
        )
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.reloadBookEntries()

        assertEquals(listOf("local-book"), viewModel.uiState.value.bookEntries.map { it.metadata.id })
        assertEquals(listOf(remote), viewModel.uiState.value.remoteBookEntries)
        assertEquals(mapOf("drive-folder" to 0.5), viewModel.uiState.value.remoteProgressById)
    }

    @Test
    fun reloadBooksSuppressesAutomaticOfflineRemoteLoadErrorLikeIos() {
        val local = bookEntry("local-book")
        val repository = FakeBookshelfRepository(
            entries = listOf(local),
            remoteLoadError = GoogleDriveApiException(GoogleDriveApiException.NoInternetConnectionMessage),
        )
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.reloadBookEntries()

        assertEquals(listOf(local), viewModel.uiState.value.bookEntries)
        assertTrue(viewModel.uiState.value.hasLoadedBooks)
        assertNull(viewModel.uiState.value.errorMessage.testString())
    }

    @Test
    fun refreshRemoteBooksReportsOfflineRemoteLoadErrorLikeIos() {
        val local = bookEntry("local-book")
        val remote = remoteEntry("drive-folder", "Remote Book")
        val repository = FakeBookshelfRepository(
            entries = listOf(local),
            remoteEntries = listOf(remote),
        )
        val viewModel = BookshelfViewModel(repository, testScope())
        viewModel.reloadBookEntries()
        repository.remoteLoadError = GoogleDriveApiException(GoogleDriveApiException.NoInternetConnectionMessage)

        viewModel.refreshRemoteBooks()

        assertEquals("Failed to fetch books from Google Drive.", viewModel.uiState.value.errorMessage.testString())
        assertEquals(listOf(remote), viewModel.uiState.value.remoteBookEntries)
    }

    @Test
    fun reloadBooksPublishesLocalShelfBeforeRemoteGoogleDriveLoadCompletes() {
        val local = bookEntry("local-book")
        val remote = remoteEntry("drive-folder", "Remote Book")
        val continueRemoteLoad = CompletableDeferred<Unit>()
        val repository = FakeBookshelfRepository(
            entries = listOf(local),
            remoteEntries = listOf(remote),
            remoteLoadGate = continueRemoteLoad,
        )
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.reloadBookEntries()

        assertEquals(listOf(local), viewModel.uiState.value.bookEntries)
        assertTrue(viewModel.uiState.value.hasLoadedBooks)
        assertEquals(emptyList<RemoteBookEntry>(), viewModel.uiState.value.remoteBookEntries)

        continueRemoteLoad.complete(Unit)

        assertEquals(listOf(remote), viewModel.uiState.value.remoteBookEntries)
    }

    @Test
    fun reloadBooksKeepsExistingRemoteSectionWhileRefreshingRemoteBooks() {
        val oldRemote = remoteEntry("old-drive-folder", "Old Remote Book")
        val newRemote = remoteEntry("new-drive-folder", "New Remote Book")
        val continueRemoteLoad = CompletableDeferred<Unit>()
        val repository = FakeBookshelfRepository(
            entries = listOf(bookEntry("local-book")),
            remoteEntries = listOf(oldRemote),
        )
        val viewModel = BookshelfViewModel(repository, testScope())
        viewModel.reloadBookEntries()
        repository.remoteEntries = listOf(newRemote)
        repository.remoteLoadGate = continueRemoteLoad

        viewModel.reloadBookEntries()

        assertEquals(listOf(oldRemote), viewModel.uiState.value.remoteBookEntries)

        continueRemoteLoad.complete(Unit)

        assertEquals(listOf(newRemote), viewModel.uiState.value.remoteBookEntries)
    }

    @Test
    fun refreshRemoteBooksReloadsGoogleDriveSectionWithoutReloadingLocalShelfLikeIos() {
        val local = bookEntry("local-book")
        val oldRemote = remoteEntry("old-drive-folder", "Old Remote Book")
        val newRemote = remoteEntry("new-drive-folder", "New Remote Book")
        val repository = FakeBookshelfRepository(
            entries = listOf(local),
            remoteEntries = listOf(oldRemote),
        )
        val viewModel = BookshelfViewModel(repository, testScope())
        viewModel.reloadBookEntries()
        repository.remoteEntries = listOf(newRemote)

        viewModel.refreshRemoteBooks()

        assertEquals(listOf(local), viewModel.uiState.value.bookEntries)
        assertEquals(listOf(newRemote), viewModel.uiState.value.remoteBookEntries)
        assertEquals(listOf(BookSortOption.Recent), repository.loadRequests)
        assertEquals(listOf(listOf("local-book"), listOf("local-book")), repository.remoteLoadRequests)
    }

    @Test
    fun newerReloadResultIsNotOverwrittenWhenOlderReloadFinishesLater() {
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val repository = FakeBookshelfRepository(
            loadPlans = ArrayDeque(
                listOf(
                    FakeBookshelfRepository.LoadPlan(firstGate, listOf(bookEntry("older-local-book"))),
                    FakeBookshelfRepository.LoadPlan(secondGate, listOf(bookEntry("newer-local-book"))),
                ),
            ),
        )
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.reloadBookEntries()
        viewModel.reloadBookEntries()
        secondGate.complete(Unit)
        firstGate.complete(Unit)

        assertEquals(listOf("newer-local-book"), viewModel.uiState.value.bookEntries.map { it.metadata.id })
    }

    @Test
    fun remoteImportAndDeleteCallRepositoryAndReloadShelf() {
        val remote = remoteEntry("drive-folder", "Remote Book")
        val repository = FakeBookshelfRepository(remoteEntries = listOf(remote))
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.importRemoteBook(remote, syncStats = true, syncAudioBook = false)
        viewModel.deleteRemoteBook(remote)

        assertEquals(listOf(ImportedRemoteBook(remote, syncStats = true, syncAudioBook = false)), repository.importedRemoteEntries)
        assertEquals(listOf(remote), repository.deletedRemoteEntries)
        assertEquals(listOf(BookSortOption.Recent, BookSortOption.Recent), repository.loadRequests)
    }

    @Test
    fun remoteImportDoesNotReplaceLocalShelfWithBlockingLoading() {
        val local = bookEntry("local-book")
        val remote = remoteEntry("drive-folder", "Remote Book")
        val continueImport = CompletableDeferred<Unit>()
        val repository = FakeBookshelfRepository(
            entries = listOf(local),
            remoteEntries = listOf(remote),
            remoteImportGate = continueImport,
        )
        val viewModel = BookshelfViewModel(repository, testScope())
        viewModel.reloadBookEntries()

        viewModel.importRemoteBook(remote, syncStats = false, syncAudioBook = false)

        assertEquals(listOf(local), viewModel.uiState.value.bookEntries)
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.blockingProgressMessage.testString())

        continueImport.complete(Unit)

        assertEquals(listOf(ImportedRemoteBook(remote, syncStats = false, syncAudioBook = false)), repository.importedRemoteEntries)
    }

    @Test
    fun remoteDeleteDoesNotReplaceLocalShelfWithBlockingLoading() {
        val local = bookEntry("local-book")
        val remote = remoteEntry("drive-folder", "Remote Book")
        val continueDelete = CompletableDeferred<Unit>()
        val repository = FakeBookshelfRepository(
            entries = listOf(local),
            remoteEntries = listOf(remote),
            remoteDeleteGate = continueDelete,
        )
        val viewModel = BookshelfViewModel(repository, testScope())
        viewModel.reloadBookEntries()

        viewModel.deleteRemoteBook(remote)

        assertEquals(listOf(local), viewModel.uiState.value.bookEntries)
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.blockingProgressMessage.testString())

        continueDelete.complete(Unit)

        assertEquals(listOf(remote), repository.deletedRemoteEntries)
    }

    @Test
    fun duplicateRemoteImportIsIgnoredWhileImportIsInFlight() {
        val remote = remoteEntry("drive-folder", "Remote Book")
        val continueImport = CompletableDeferred<Unit>()
        val repository = FakeBookshelfRepository(
            remoteEntries = listOf(remote),
            remoteImportGate = continueImport,
        )
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.importRemoteBook(remote, syncStats = false, syncAudioBook = false)
        viewModel.importRemoteBook(remote, syncStats = true, syncAudioBook = true)

        assertEquals(listOf(ImportedRemoteBook(remote, syncStats = false, syncAudioBook = false)), repository.importedRemoteEntries)

        continueImport.complete(Unit)
    }

    @Test
    fun remoteImportPublishesDownloadProgressAndBlocksDeleteWhileInFlight() {
        val remote = remoteEntry("drive-folder", "Remote Book")
        val continueImport = CompletableDeferred<Unit>()
        val repository = FakeBookshelfRepository(
            remoteEntries = listOf(remote),
            remoteImportGate = continueImport,
            remoteImportProgress = listOf(0.25, 0.75),
        )
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.importRemoteBook(remote, syncStats = false, syncAudioBook = true)

        assertEquals(mapOf(remote.id to 0.75), viewModel.uiState.value.remoteImportProgressById)
        viewModel.deleteRemoteBook(remote)
        assertEquals(emptyList<RemoteBookEntry>(), repository.deletedRemoteEntries)

        continueImport.complete(Unit)

        assertEquals(emptyMap<String, Double>(), viewModel.uiState.value.remoteImportProgressById)
    }

    @Test
    fun remoteDeleteBlocksImportWhileInFlight() {
        val remote = remoteEntry("drive-folder", "Remote Book")
        val continueDelete = CompletableDeferred<Unit>()
        val repository = FakeBookshelfRepository(
            remoteEntries = listOf(remote),
            remoteDeleteGate = continueDelete,
        )
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.deleteRemoteBook(remote)
        viewModel.importRemoteBook(remote, syncStats = true, syncAudioBook = true)

        assertEquals(listOf(remote), repository.deletedRemoteEntries)
        assertEquals(emptyList<ImportedRemoteBook>(), repository.importedRemoteEntries)

        continueDelete.complete(Unit)
    }

    @Test
    fun remoteLoadFailurePublishesStableGoogleDriveError() {
        val repository = FakeBookshelfRepository(
            entries = listOf(bookEntry("local-book")),
            remoteLoadError = RuntimeException("drive failed"),
        )
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.reloadBookEntries()

        assertEquals(
            "Failed to fetch books from Google Drive.",
            viewModel.uiState.value.errorMessage.testString(),
        )
    }

    @Test
    fun remoteImportFailurePublishesStableGoogleDriveError() {
        val remote = remoteEntry("drive-folder", "Remote Book")
        val repository = FakeBookshelfRepository(remoteImportError = RuntimeException("drive import failed"))
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.importRemoteBook(remote, syncStats = true, syncAudioBook = true)

        assertEquals(
            "Failed to import book from Google Drive.",
            viewModel.uiState.value.errorMessage.testString(),
        )
    }

    @Test
    fun remoteDeleteFailurePublishesStableGoogleDriveError() {
        val remote = remoteEntry("drive-folder", "Remote Book")
        val repository = FakeBookshelfRepository(remoteDeleteError = RuntimeException("drive delete failed"))
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.deleteRemoteBook(remote)

        assertEquals(
            "Failed to delete book from Google Drive.",
            viewModel.uiState.value.errorMessage.testString(),
        )
    }

    @Test
    fun changingSortOptionReloadsBooksWithThatOption() {
        val repository = FakeBookshelfRepository()
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.changeSort(BookSortOption.Title)

        assertEquals(BookSortOption.Title, viewModel.uiState.value.sortOption)
        assertEquals(listOf(BookSortOption.Title), repository.loadRequests)
    }

    @Test
    fun changingReadingShelfVisibilityPersistsAndRebuildsSections() {
        val repository = FakeBookshelfRepository(
            entries = listOf(bookEntry("book-a")),
            progressById = mapOf("book-a" to 0.5),
        )
        val viewModel = BookshelfViewModel(repository, testScope())
        viewModel.reloadBookEntries()

        viewModel.changeShowReading(true)

        assertEquals(listOf(true), repository.showReadingUpdates)
        assertTrue(viewModel.uiState.value.showReading)
        assertEquals(listOf("Reading", "Unshelved"), viewModel.uiState.value.sections.map { it.title })
    }

    @Test
    fun openingBookEmitsOpenReaderEventWithoutRefreshingShelf() {
        val entry = bookEntry("book-a")
        val repository = FakeBookshelfRepository(entries = listOf(entry), openBookId = "book-a")
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.openBook(entry)

        assertEquals("book-a", viewModel.uiState.value.openReaderBookId)
        assertEquals(emptyList<BookEntry>(), viewModel.uiState.value.bookEntries)
        assertEquals(emptyList<BookSortOption>(), repository.loadRequests)
        assertFalse(viewModel.uiState.value.isLoading)

        viewModel.consumeOpenReaderEvent()

        assertNull(viewModel.uiState.value.openReaderBookId)
    }

    @Test
    fun importingBookRefreshesShelfWithoutOpeningReaderOnSuccess() {
        val repository = FakeBookshelfRepository(importBookId = "imported-book")
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.importBook(
            importKey = "content://books/import.epub",
            displayName = "import.epub",
        ) {
            repository.importBookId
        }

        assertNull(viewModel.uiState.value.openReaderBookId)
        assertEquals(listOf(BookSortOption.Recent), repository.loadRequests)
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.blockingProgressMessage.testString())
        assertNull(viewModel.uiState.value.errorMessage.testString())
    }

    @Test
    fun importingBookShowsAndClearsBlockingProgressMessage() {
        val repository = FakeBookshelfRepository()
        val viewModel = BookshelfViewModel(repository, testScope())
        val continueImport = CompletableDeferred<Unit>()

        viewModel.importBook(
            importKey = "content://books/import.epub",
            displayName = "import.epub",
        ) {
            continueImport.await()
            "imported-book"
        }

        assertEquals("Importing import.epub...", viewModel.uiState.value.blockingProgressMessage.testString())

        continueImport.complete(Unit)

        assertNull(viewModel.uiState.value.openReaderBookId)
        assertNull(viewModel.uiState.value.blockingProgressMessage.testString())
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun importingBookReportsErrorAndAllowsRetryOnFailure() {
        val repository = FakeBookshelfRepository()
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.importBook(
            importKey = "content://books/import.epub",
            displayName = "import.epub",
        ) {
            error("bad epub")
        }

        assertEquals("bad epub", viewModel.uiState.value.errorMessage.testString())
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.blockingProgressMessage.testString())

        viewModel.importBook(
            importKey = "content://books/import.epub",
            displayName = "import.epub",
        ) {
            "retry-book"
        }

        assertNull(viewModel.uiState.value.openReaderBookId)
        assertNull(viewModel.uiState.value.errorMessage.testString())
    }

    @Test
    fun duplicatePendingImportDoesNotLeaveBlockingProgressMessage() {
        val repository = FakeBookshelfRepository()
        val viewModel = BookshelfViewModel(repository, testScope())
        val continueImport = CompletableDeferred<Unit>()
        var importCount = 0

        viewModel.importBook(
            importKey = "content://books/import.epub",
            displayName = "import.epub",
        ) {
            importCount += 1
            continueImport.await()
            "imported-book"
        }

        viewModel.importBook(
            importKey = "content://books/import.epub",
            displayName = "import.epub",
        ) {
            importCount += 1
            "duplicate-book"
        }

        assertEquals(1, importCount)
        assertEquals("Importing import.epub...", viewModel.uiState.value.blockingProgressMessage.testString())

        continueImport.complete(Unit)

        assertNull(viewModel.uiState.value.openReaderBookId)
        assertEquals(1, importCount)
        assertNull(viewModel.uiState.value.blockingProgressMessage.testString())
    }

    @Test
    fun importingBooksProcessesEachItemWithCountProgressAndDoesNotOpenReader() {
        val repository = FakeBookshelfRepository()
        val viewModel = BookshelfViewModel(repository, testScope())
        val firstImport = CompletableDeferred<Unit>()
        val secondImport = CompletableDeferred<Unit>()
        val importedKeys = mutableListOf<String>()

        viewModel.importBooks(
            listOf(
                PendingBookImport(
                    importKey = "content://books/first.epub",
                    displayName = "first.epub",
                ) {
                    importedKeys += "content://books/first.epub"
                    firstImport.await()
                    "first-book"
                },
                PendingBookImport(
                    importKey = "content://books/second.epub",
                    displayName = "second.epub",
                ) {
                    importedKeys += "content://books/second.epub"
                    secondImport.await()
                    "second-book"
                },
            ),
        )

        assertEquals("Importing 1 / 2...", viewModel.uiState.value.blockingProgressMessage.testString())
        assertEquals(listOf("content://books/first.epub"), importedKeys)

        firstImport.complete(Unit)

        assertEquals("Importing 2 / 2...", viewModel.uiState.value.blockingProgressMessage.testString())
        assertEquals(
            listOf("content://books/first.epub", "content://books/second.epub"),
            importedKeys,
        )

        secondImport.complete(Unit)

        assertNull(viewModel.uiState.value.openReaderBookId)
        assertEquals(listOf(BookSortOption.Recent), repository.loadRequests)
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.blockingProgressMessage.testString())
        assertNull(viewModel.uiState.value.errorMessage.testString())
    }

    @Test
    fun importingBooksContinuesAfterItemFailureAndReportsFailedNames() {
        val repository = FakeBookshelfRepository()
        val viewModel = BookshelfViewModel(repository, testScope())
        val importedKeys = mutableListOf<String>()

        viewModel.importBooks(
            listOf(
                PendingBookImport(
                    importKey = "content://books/bad.epub",
                    displayName = "bad.epub",
                ) {
                    importedKeys += "bad"
                    error("bad epub")
                },
                PendingBookImport(
                    importKey = "content://books/good.epub",
                    displayName = "good.epub",
                ) {
                    importedKeys += "good"
                    "good-book"
                },
            ),
        )

        assertEquals(listOf("bad", "good"), importedKeys)
        assertEquals("Failed to import:\nbad.epub", viewModel.uiState.value.errorMessage.testString())
        assertNull(viewModel.uiState.value.openReaderBookId)
        assertEquals(listOf(BookSortOption.Recent), repository.loadRequests)
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.blockingProgressMessage.testString())
    }

    @Test
    fun importingBookFolderReportsErrorWhenNoEpubFilesAreFound() {
        val repository = FakeBookshelfRepository()
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.importBookFolder {
            emptyList()
        }

        assertEquals("No EPUB files found.", viewModel.uiState.value.errorMessage.testString())
        assertEquals(emptyList<BookSortOption>(), repository.loadRequests)
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.blockingProgressMessage.testString())
    }

    @Test
    fun importingBookFolderScansThenUsesBatchImportFlow() {
        val repository = FakeBookshelfRepository()
        val viewModel = BookshelfViewModel(repository, testScope())
        val importedKeys = mutableListOf<String>()

        viewModel.importBookFolder {
            listOf(
                PendingBookImport(
                    importKey = "content://books/folder/first.epub",
                    displayName = "Series/first.epub",
                ) {
                    importedKeys += "first"
                    "first-book"
                },
                PendingBookImport(
                    importKey = "content://books/folder/second.epub",
                    displayName = "Series/second.epub",
                ) {
                    importedKeys += "second"
                    "second-book"
                },
            )
        }

        assertEquals(listOf("first", "second"), importedKeys)
        assertEquals(listOf(BookSortOption.Recent), repository.loadRequests)
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.blockingProgressMessage.testString())
        assertNull(viewModel.uiState.value.errorMessage.testString())
    }

    @Test
    fun deleteBookReloadsBooksAfterRepositoryDeletion() {
        val entry = bookEntry("book-a")
        val repository = FakeBookshelfRepository(entries = emptyList())
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.deleteBook(entry)

        assertEquals(listOf(entry), repository.deletedEntries)
        assertEquals(emptyList<BookEntry>(), viewModel.uiState.value.bookEntries)
    }

    @Test
    fun selectionModeTogglesBooksAndBatchMovesSelectionToShelf() {
        val first = bookEntry("book-a")
        val second = bookEntry("book-b")
        val repository = FakeBookshelfRepository(entries = listOf(first, second))
        val viewModel = BookshelfViewModel(repository, testScope())
        viewModel.reloadBookEntries()

        viewModel.startSelecting()
        viewModel.toggleSelectedBook(first)
        viewModel.toggleSelectedBook(second)
        viewModel.moveSelectedBooks("Manga")

        assertEquals(listOf(setOf("book-a", "book-b") to "Manga"), repository.movedBooks)
        assertFalse(viewModel.uiState.value.isSelecting)
        assertEquals(emptySet<String>(), viewModel.uiState.value.selectedBookIds)
    }

    @Test
    fun createDeleteAndMoveShelfDelegateToRepositoryAndReload() {
        val repository = FakeBookshelfRepository()
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.createShelf("Manga")
        viewModel.deleteShelf("Manga")
        viewModel.moveShelf(fromIndex = 1, toIndex = 0)

        assertEquals(listOf("Manga"), repository.createdShelves)
        assertEquals(listOf("Manga"), repository.deletedShelves)
        assertEquals(listOf(1 to 0), repository.movedShelves)
        assertEquals(listOf(BookSortOption.Recent, BookSortOption.Recent, BookSortOption.Recent), repository.loadRequests)
    }

    @Test
    fun markReadWritesCompletedBookmarkThroughRepository() {
        val entry = bookEntry("book-a")
        val repository = FakeBookshelfRepository(entries = listOf(entry))
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.markRead(entry)

        assertEquals(listOf(entry), repository.markedReadEntries)
    }

    @Test
    fun renameBookTrimsTitlePersistsItAndReloadsShelf() {
        val entry = bookEntry("book-a")
        val repository = FakeBookshelfRepository(entries = listOf(entry))
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.renameBook(entry, "  Custom Title  ")

        assertEquals(listOf(entry to "Custom Title"), repository.renamedBooks)
        assertEquals(listOf(BookSortOption.Recent), repository.loadRequests)
    }

    @Test
    fun renameBookClearsCustomTitleWhenBlank() {
        val entry = bookEntry("book-a")
        val repository = FakeBookshelfRepository(entries = listOf(entry))
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.renameBook(entry, "   ")

        assertEquals(listOf(entry to null), repository.renamedBooks)
    }

    @Test
    fun sasayakiEnabledCanBeDrivenByObservedSettingsState() {
        val viewModel = BookshelfViewModel(FakeBookshelfRepository(), testScope())

        viewModel.setSasayakiEnabled(true)

        assertTrue(viewModel.uiState.value.sasayakiEnabled)
    }

    @Test
    fun syncBookPublishesDismissibleSuccessFeedback() {
        val entry = bookEntry("book-a")
        val viewModel = BookshelfViewModel(
            FakeBookshelfRepository(entries = listOf(entry)),
            testScope(),
        )

        viewModel.syncBook(
            entry = entry,
            direction = SyncDirection.ExportToTtu,
            syncStats = false,
            statsSyncMode = StatisticsSyncMode.Merge,
            syncAudioBook = false,
        )

        assertEquals("book-a is already synced", viewModel.uiState.value.statusMessage.testString())

        viewModel.consumeStatusMessage()

        assertNull(viewModel.uiState.value.statusMessage.testString())
    }

    @Test
    fun errorFeedbackCanBeDismissedAfterFailure() {
        val viewModel = BookshelfViewModel(FakeBookshelfRepository(), testScope())

        viewModel.importBook(
            importKey = "content://books/import.epub",
            displayName = "import.epub",
        ) {
            error("bad epub")
        }

        assertEquals("bad epub", viewModel.uiState.value.errorMessage.testString())

        viewModel.consumeErrorMessage()

        assertNull(viewModel.uiState.value.errorMessage.testString())
    }

    @Test
    fun shelfExpansionStateStaysInMemoryAcrossReloads() {
        val viewModel = BookshelfViewModel(FakeBookshelfRepository(), testScope())

        viewModel.setShelfExpanded("__reading__", false)
        viewModel.setShelfExpanded("shelf:Manga", true)
        viewModel.reloadBookEntries()

        assertEquals(
            mapOf("__reading__" to false, "shelf:Manga" to true),
            viewModel.uiState.value.shelfExpansionState,
        )
    }

    private fun testScope(): CoroutineScope = CoroutineScope(Dispatchers.Unconfined)

    private fun bookEntry(id: String): BookEntry =
        BookEntry(
            root = File(id),
            metadata = BookMetadata(
                id = id,
                title = id,
                cover = null,
                folder = id,
                lastAccess = 0.0,
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

    private data class ImportedRemoteBook(
        val entry: RemoteBookEntry,
        val syncStats: Boolean,
        val syncAudioBook: Boolean,
    )

    private class FakeBookshelfRepository(
        var entries: List<BookEntry> = emptyList(),
        var progressById: Map<String, Double> = emptyMap(),
        var openBookId: String = "book-a",
        var importBookId: String = "imported-book",
        var shelves: List<BookShelf> = emptyList(),
        var coverSourcesById: Map<String, BookCoverSource> = emptyMap(),
        var settings: BookshelfSettings = BookshelfSettings(),
        var remoteEntries: List<RemoteBookEntry> = emptyList(),
        var remoteProgressById: Map<String, Double> = emptyMap(),
        var remoteCoverSourcesById: Map<String, BookCoverSource> = emptyMap(),
        var remoteLoadGate: CompletableDeferred<Unit>? = null,
        var remoteLoadError: Throwable? = null,
        val remoteImportError: Throwable? = null,
        val remoteDeleteError: Throwable? = null,
        val remoteImportGate: CompletableDeferred<Unit>? = null,
        val remoteDeleteGate: CompletableDeferred<Unit>? = null,
        val remoteImportProgress: List<Double> = emptyList(),
        private val loadPlans: ArrayDeque<LoadPlan> = ArrayDeque(),
    ) : BookshelfRepository {
        data class LoadPlan(
            val gate: CompletableDeferred<Unit>?,
            val entries: List<BookEntry>,
        )

        val loadRequests = mutableListOf<BookSortOption>()
        val remoteLoadRequests = mutableListOf<List<String>>()
        val deletedEntries = mutableListOf<BookEntry>()
        val movedBooks = mutableListOf<Pair<Set<String>, String?>>()
        val createdShelves = mutableListOf<String>()
        val deletedShelves = mutableListOf<String>()
        val movedShelves = mutableListOf<Pair<Int, Int>>()
        val markedReadEntries = mutableListOf<BookEntry>()
        val importedRemoteEntries = mutableListOf<ImportedRemoteBook>()
        val deletedRemoteEntries = mutableListOf<RemoteBookEntry>()
        val exportedBooks = mutableListOf<BookEntry>()
        val showReadingUpdates = mutableListOf<Boolean>()
        val renamedBooks = mutableListOf<Pair<BookEntry, String?>>()

        override suspend fun loadBooks(sortOption: BookSortOption): BookshelfLoadResult {
            loadRequests += sortOption
            val plan = loadPlans.removeFirstOrNull()
            plan?.gate?.await()
            return BookshelfLoadResult(
                entries = plan?.entries ?: entries,
                progressById = progressById,
                coverSourcesById = coverSourcesById,
                shelves = shelves,
                settings = settings,
            )
        }

        override suspend fun loadRemoteBooks(localEntries: List<BookEntry>): RemoteBookshelfLoadResult {
            remoteLoadRequests += localEntries.map { it.metadata.id }
            remoteLoadGate?.await()
            remoteLoadError?.let { throw it }
            return RemoteBookshelfLoadResult(
                remoteEntries = remoteEntries,
                remoteProgressById = remoteProgressById,
                remoteCoverSourcesById = remoteCoverSourcesById,
            )
        }

        override suspend fun openBook(entry: BookEntry): String = openBookId

        override suspend fun importBook(uri: android.net.Uri): String = importBookId

        override suspend fun exportBook(entry: BookEntry, uri: android.net.Uri) {
            exportedBooks += entry
        }

        override suspend fun importRemoteBook(
            entry: RemoteBookEntry,
            syncStats: Boolean,
            syncAudioBook: Boolean,
            onProgress: (Double) -> Unit,
        ): String {
            importedRemoteEntries += ImportedRemoteBook(entry, syncStats, syncAudioBook)
            remoteImportProgress.forEach(onProgress)
            remoteImportGate?.await()
            remoteImportError?.let { throw it }
            return importBookId
        }

        override suspend fun deleteBook(entry: BookEntry) {
            deletedEntries += entry
        }

        override suspend fun deleteRemoteBook(entry: RemoteBookEntry) {
            deletedRemoteEntries += entry
            remoteDeleteGate?.await()
            remoteDeleteError?.let { throw it }
        }

        override suspend fun deleteBooks(entries: Collection<BookEntry>) {
            deletedEntries += entries
        }

        override suspend fun moveBooks(bookIds: Set<String>, shelfName: String?) {
            movedBooks += bookIds to shelfName
        }

        override suspend fun createShelf(name: String) {
            createdShelves += name
        }

        override suspend fun deleteShelf(name: String) {
            deletedShelves += name
        }

        override suspend fun moveShelf(fromIndex: Int, toIndex: Int) {
            movedShelves += fromIndex to toIndex
        }

        override suspend fun markRead(entry: BookEntry) {
            markedReadEntries += entry
        }

        override suspend fun renameBook(entry: BookEntry, title: String?) {
            renamedBooks += entry to title
        }

        override suspend fun changeSort(sortOption: BookSortOption) {
            settings = settings.copy(sortOption = sortOption)
        }

        override suspend fun changeShowReading(showReading: Boolean) {
            settings = settings.copy(showReading = showReading)
            showReadingUpdates += showReading
        }

        override suspend fun rebuildLookupQuery() = Unit

        override suspend fun syncBook(
            entry: BookEntry,
            direction: SyncDirection?,
            syncStats: Boolean,
            statsSyncMode: StatisticsSyncMode,
            syncAudioBook: Boolean,
        ): SyncResult = SyncResult.Synced(entry.metadata.title.orEmpty())
    }
}

private fun UiText?.testString(): String? =
    when (this) {
        null -> null
        is UiText.Literal -> value
        is UiText.Resource -> when (id) {
            R.string.bookshelf_importing_named_format -> "Importing ${args[0]}..."
            R.string.bookshelf_importing_progress_format -> "Importing ${args[0]} / ${args[1]}..."
            R.string.bookshelf_import_failed_list_format -> "Failed to import:\n${args[0]}"
            R.string.bookshelf_scanning_folder -> "Scanning folder..."
            R.string.bookshelf_no_epub_files_found -> "No EPUB files found."
            R.string.bookshelf_already_synced_format -> "${args[0]} is already synced"
            R.string.bookshelf_exported_epub_format -> "Exported ${args[0]}."
            R.string.bookshelf_remote_books_load_failed -> "Failed to fetch books from Google Drive."
            R.string.bookshelf_remote_book_import_failed -> "Failed to import book from Google Drive."
            R.string.bookshelf_remote_book_delete_failed -> "Failed to delete book from Google Drive."
            else -> "resource:$id:${args.joinToString()}"
        }
        is UiText.Plural -> "plural:$id:$quantity:${args.joinToString()}"
    }
