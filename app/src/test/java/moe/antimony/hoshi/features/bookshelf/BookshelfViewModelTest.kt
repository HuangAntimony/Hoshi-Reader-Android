package moe.antimony.hoshi.features.bookshelf

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.LegacyBookMigrationProgress
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
    fun reloadBooksPublishesEntriesAndProgress() {
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
            settings = BookshelfSettings(
                sortOption = BookSortOption.Title,
                showReading = true,
                coverMode = BookshelfCoverMode.Blur,
            ),
        )
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.reloadBookEntries()

        assertEquals(listOf(entry), viewModel.uiState.value.bookEntries)
        assertEquals(mapOf("book-a" to 0.25), viewModel.uiState.value.bookProgressById)
        assertEquals(mapOf("book-a" to coverSource), viewModel.uiState.value.coverSourcesById)
        assertEquals(listOf(BookShelf("Manga", listOf("book-a"))), viewModel.uiState.value.shelves)
        assertEquals(BookSortOption.Title, viewModel.uiState.value.sortOption)
        assertTrue(viewModel.uiState.value.showReading)
        assertEquals(BookshelfCoverMode.Blur, viewModel.uiState.value.coverMode)
        assertTrue(viewModel.uiState.value.hasLoadedBooks)
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.errorMessage.testString())
    }

    @Test
    fun changeCoverModePersistsAndPublishesState() {
        val persistenceGate = CompletableDeferred<Unit>()
        val repository = FakeBookshelfRepository(coverModeGate = persistenceGate)
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.changeCoverMode(BookshelfCoverMode.Hide)

        assertEquals(listOf(BookshelfCoverMode.Hide), repository.coverModeUpdates)
        assertEquals(BookshelfCoverMode.Hide, viewModel.uiState.value.coverMode)

        persistenceGate.complete(Unit)
    }

    @Test
    fun reloadBooksShowsLegacyPackedMigrationProgressWhileLoading() {
        val continueLoad = CompletableDeferred<Unit>()
        val entry = bookEntry("legacy-book")
        val repository = FakeBookshelfRepository(
            migrationProgressEvents = listOf(LegacyBookMigrationProgress(current = 1, total = 2)),
            loadPlans = ArrayDeque(
                listOf(
                    FakeBookshelfRepository.LoadPlan(
                        gate = continueLoad,
                        entries = listOf(entry),
                    ),
                ),
            ),
        )
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.reloadBookEntries()

        assertEquals("Preparing older books 1 / 2...", viewModel.uiState.value.blockingProgressMessage.testString())

        continueLoad.complete(Unit)

        assertEquals(listOf(entry), viewModel.uiState.value.bookEntries)
        assertNull(viewModel.uiState.value.blockingProgressMessage.testString())
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

        assertEquals("Failed to import EPUB.", viewModel.uiState.value.errorMessage.testString())
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
    fun failedArchiveKeepsBookAndShowsLocalizedDeletionError() {
        val entry = bookEntry("book-a")
        val repository = FakeBookshelfRepository(entries = listOf(entry), localDeleteError = java.io.IOException("disk full"))
        val viewModel = BookshelfViewModel(repository, testScope())
        viewModel.reloadBookEntries()
        viewModel.deleteBook(entry)
        assertEquals(listOf(entry), viewModel.uiState.value.bookEntries)
        assertEquals(UiText.Resource(R.string.bookshelf_delete_failed), viewModel.uiState.value.errorMessage)
        viewModel.startSelecting()
        viewModel.toggleSelectedBook(entry)
        viewModel.deleteSelectedBooks()
        assertEquals(setOf("book-a"), viewModel.uiState.value.selectedBookIds)
        assertEquals(UiText.Resource(R.string.bookshelf_delete_failed), viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isLoading)
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
    fun createShelfAndMoveSelectedBooksClearsSelectionAndExpandsNewShelfOnSuccess() {
        val first = bookEntry("book-a")
        val second = bookEntry("book-b")
        val repository = FakeBookshelfRepository(entries = listOf(first, second))
        val viewModel = BookshelfViewModel(repository, testScope())
        viewModel.reloadBookEntries()
        viewModel.startSelecting()
        viewModel.toggleSelectedBook(first)
        viewModel.toggleSelectedBook(second)

        viewModel.beginShelfCreationMoveForSelectedBooks()
        viewModel.updateShelfCreationMoveName("  New Shelf  ")
        viewModel.confirmShelfCreationMove()

        assertEquals(
            listOf(Triple("New Shelf", setOf("book-a", "book-b"), true)),
            repository.createdShelvesWithBooks,
        )
        assertFalse(viewModel.uiState.value.isSelecting)
        assertEquals(emptySet<String>(), viewModel.uiState.value.selectedBookIds)
        assertEquals(true, viewModel.uiState.value.shelfExpansionState["shelf:New Shelf"])
        assertEquals(
            ShelfCreationMoveStatus.Succeeded(shelfName = "New Shelf", bookCount = 2),
            viewModel.uiState.value.shelfCreationMoveStatus,
        )
    }

    @Test
    fun createShelfAndMoveSingleBookUsesTheSameOperationWithoutChangingSelectionMode() {
        val entry = bookEntry("book-a")
        val repository = FakeBookshelfRepository(entries = listOf(entry))
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.beginShelfCreationMoveForBook(entry)
        viewModel.updateShelfCreationMoveName("New Shelf")
        viewModel.confirmShelfCreationMove()

        assertEquals(
            listOf(Triple("New Shelf", setOf("book-a"), true)),
            repository.createdShelvesWithBooks,
        )
        assertFalse(viewModel.uiState.value.isSelecting)
        assertEquals(
            ShelfCreationMoveStatus.Succeeded(shelfName = "New Shelf", bookCount = 1),
            viewModel.uiState.value.shelfCreationMoveStatus,
        )
    }

    @Test
    fun duplicateShelfDuringCreateAndMoveKeepsBatchSelection() {
        val entry = bookEntry("book-a")
        val repository = FakeBookshelfRepository(
            entries = listOf(entry),
            createShelfAndMoveResult = false,
        )
        val viewModel = BookshelfViewModel(repository, testScope())
        viewModel.reloadBookEntries()
        viewModel.startSelecting()
        viewModel.toggleSelectedBook(entry)

        viewModel.beginShelfCreationMoveForSelectedBooks()
        viewModel.updateShelfCreationMoveName("Manga")
        viewModel.confirmShelfCreationMove()

        assertTrue(viewModel.uiState.value.isSelecting)
        assertEquals(setOf("book-a"), viewModel.uiState.value.selectedBookIds)
        assertEquals(
            "A shelf with this name already exists.",
            (viewModel.uiState.value.shelfCreationMoveStatus as ShelfCreationMoveStatus.Failed).message.testString(),
        )
    }

    @Test
    fun createShelfAndMoveIgnoresRepeatedSubmissionWhileTheFirstIsRunning() {
        val continueCreation = CompletableDeferred<Unit>()
        val entry = bookEntry("book-a")
        val repository = FakeBookshelfRepository(
            entries = listOf(entry),
            createShelfAndMoveGate = continueCreation,
        )
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.beginShelfCreationMoveForBook(entry)
        viewModel.updateShelfCreationMoveName("New Shelf")
        viewModel.confirmShelfCreationMove()
        viewModel.confirmShelfCreationMove()

        assertEquals(1, repository.createdShelvesWithBooks.size)
        continueCreation.complete(Unit)
    }

    @Test
    fun shelfCreationMoveTargetAndDraftRemainInViewModelWhileSubmitting() {
        val continueCreation = CompletableDeferred<Unit>()
        val entry = bookEntry("book-a")
        val repository = FakeBookshelfRepository(
            entries = listOf(entry),
            createShelfAndMoveGate = continueCreation,
        )
        val viewModel = BookshelfViewModel(repository, testScope())
        viewModel.reloadBookEntries()
        viewModel.startSelecting()
        viewModel.toggleSelectedBook(entry)

        viewModel.beginShelfCreationMoveForSelectedBooks()
        viewModel.updateShelfCreationMoveName("  New Shelf  ")
        viewModel.confirmShelfCreationMove()
        viewModel.consumeShelfCreationMoveStatus()

        assertEquals(
            ShelfCreationMoveDialogState(
                bookIds = setOf("book-a"),
                clearSelectionOnSuccess = true,
                name = "  New Shelf  ",
            ),
            viewModel.uiState.value.shelfCreationMoveDialog,
        )
        assertEquals(ShelfCreationMoveStatus.Submitting, viewModel.uiState.value.shelfCreationMoveStatus)
        assertEquals(1, repository.createdShelvesWithBooks.size)
        continueCreation.complete(Unit)
    }

    @Test
    fun createShelfAndMoveFailureKeepsBatchSelectionAndShowsStableError() {
        val entry = bookEntry("book-a")
        val repository = FakeBookshelfRepository(
            entries = listOf(entry),
            createShelfAndMoveError = IllegalStateException("disk details"),
        )
        val viewModel = BookshelfViewModel(repository, testScope())
        viewModel.reloadBookEntries()
        viewModel.startSelecting()
        viewModel.toggleSelectedBook(entry)

        viewModel.beginShelfCreationMoveForSelectedBooks()
        viewModel.updateShelfCreationMoveName("Manga")
        viewModel.confirmShelfCreationMove()

        assertTrue(viewModel.uiState.value.isSelecting)
        assertEquals(setOf("book-a"), viewModel.uiState.value.selectedBookIds)
        assertEquals(
            "Failed to create the shelf. Try again.",
            (viewModel.uiState.value.shelfCreationMoveStatus as ShelfCreationMoveStatus.Failed).message.testString(),
        )
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
    fun renameShelfTrimsNameDelegatesToRepositoryAndReloadsShelf() {
        val repository = FakeBookshelfRepository()
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.renameShelf("Manga", "  Novels  ")

        assertEquals(listOf("Manga" to "Novels"), repository.renamedShelves)
        assertEquals(listOf(BookSortOption.Recent), repository.loadRequests)
    }

    @Test
    fun renameShelfListPreservesMembershipAndRejectsInvalidNames() {
        val shelves = listOf(
            BookShelf(name = "Manga", bookIds = listOf("book-a")),
            BookShelf(name = "Novels", bookIds = listOf("book-b")),
        )

        assertEquals(
            listOf(
                BookShelf(name = "Mystery", bookIds = listOf("book-a")),
                BookShelf(name = "Novels", bookIds = listOf("book-b")),
            ),
            renameShelfList(shelves, oldName = "Manga", newName = " Mystery "),
        )
        assertEquals(shelves, renameShelfList(shelves, oldName = "Manga", newName = "   "))
        assertEquals(shelves, renameShelfList(shelves, oldName = "Manga", newName = "Novels"))
        assertEquals(shelves, renameShelfList(shelves, oldName = "Missing", newName = "Mystery"))
    }

    @Test
    fun createShelfAndMoveBooksListMovesMembershipIntoTrimmedNewShelf() {
        val shelves = listOf(
            BookShelf(name = "Manga", bookIds = listOf("book-a", "book-b")),
            BookShelf(name = "Novels", bookIds = listOf("book-c")),
        )

        assertEquals(
            listOf(
                BookShelf(name = "Manga", bookIds = listOf("book-a")),
                BookShelf(name = "Novels", bookIds = emptyList()),
                BookShelf(name = "New Shelf", bookIds = listOf("book-b", "book-c")),
            ),
            createShelfAndMoveBooksList(
                shelves = shelves,
                name = "  New Shelf  ",
                bookIds = linkedSetOf("book-b", "book-c"),
            ),
        )
    }

    @Test
    fun createShelfAndMoveBooksListRejectsBlankAndDuplicateNames() {
        val shelves = listOf(BookShelf(name = "Manga", bookIds = listOf("book-a")))

        assertNull(createShelfAndMoveBooksList(shelves, name = "   ", bookIds = setOf("book-a")))
        assertNull(createShelfAndMoveBooksList(shelves, name = " Manga ", bookIds = setOf("book-a")))
        assertNull(createShelfAndMoveBooksList(shelves, name = "Novels", bookIds = emptySet()))
    }

    @Test
    fun newShelfNameValidationDistinguishesBlankDuplicateAndValidNames() {
        val shelves = listOf(BookShelf(name = "Manga", bookIds = emptyList()))

        assertEquals(NewShelfNameValidation.Blank, validateNewShelfName("   ", shelves))
        assertEquals(NewShelfNameValidation.Duplicate, validateNewShelfName(" Manga ", shelves))
        assertEquals(NewShelfNameValidation.Valid, validateNewShelfName(" Novels ", shelves))
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
    fun setBookProfilePersistsProfileAndReloadsShelf() {
        val entry = bookEntry("book-a")
        val repository = FakeBookshelfRepository(entries = listOf(entry))
        val viewModel = BookshelfViewModel(repository, testScope())

        viewModel.setBookProfile(entry, "english")

        assertEquals(listOf(entry to "english"), repository.profiledBooks)
        assertEquals(listOf(BookSortOption.Recent), repository.loadRequests)
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
    fun manualSyncKeepsLoadedShelfMountedWithoutReloadingBooksLikeIos() {
        val entry = bookEntry("book-a")
        val continueSync = CompletableDeferred<Unit>()
        val repository = FakeBookshelfRepository(
            entries = listOf(entry),
            syncGate = continueSync,
        )
        val viewModel = BookshelfViewModel(repository, testScope())
        viewModel.reloadBookEntries()
        repository.loadRequests.clear()

        viewModel.syncBook(
            entry = entry,
            direction = SyncDirection.ExportToTtu,
            syncStats = false,
            statsSyncMode = StatisticsSyncMode.Merge,
            syncAudioBook = false,
        )

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(listOf(entry), viewModel.uiState.value.bookEntries)
        assertEquals("Syncing...", viewModel.uiState.value.blockingProgressMessage.testString())

        continueSync.complete(Unit)

        assertTrue(repository.loadRequests.isEmpty())
        assertTrue(repository.progressLoadRequests.isEmpty())
        assertNull(viewModel.uiState.value.blockingProgressMessage.testString())
    }

    @Test
    fun importedSyncRefreshesProgressWithoutReloadingBooksLikeIos() {
        val entry = bookEntry("book-a")
        val repository = FakeBookshelfRepository(
            entries = listOf(entry),
            progressById = mapOf("book-a" to 0.0),
            settings = BookshelfSettings(showReading = true),
            syncResult = SyncResult.Imported("book-a", characterCount = 100),
        )
        val viewModel = BookshelfViewModel(repository, testScope())
        viewModel.reloadBookEntries()
        repository.loadRequests.clear()
        repository.progressById = mapOf("book-a" to 0.75)

        viewModel.syncBook(
            entry = entry,
            direction = SyncDirection.ImportFromTtu,
            syncStats = false,
            statsSyncMode = StatisticsSyncMode.Merge,
            syncAudioBook = false,
        )

        assertEquals(mapOf("book-a" to 0.75), viewModel.uiState.value.bookProgressById)
        assertTrue(repository.loadRequests.isEmpty())
        assertEquals(listOf(listOf("book-a")), repository.progressLoadRequests)
        assertEquals(
            listOf(entry),
            viewModel.uiState.value.sections.single { it.isReading }.books,
        )
    }

    @Test
    fun importedSyncProgressRefreshDoesNotDropBooksAddedByConcurrentReload() {
        val first = bookEntry("book-a")
        val second = bookEntry("book-b")
        val continueProgressLoad = CompletableDeferred<Unit>()
        val repository = FakeBookshelfRepository(
            entries = listOf(first),
            progressById = mapOf("book-a" to 0.25),
            syncResult = SyncResult.Imported("book-a", characterCount = 100),
            progressLoadGate = continueProgressLoad,
        )
        val viewModel = BookshelfViewModel(repository, testScope())
        viewModel.reloadBookEntries()

        viewModel.syncBook(
            entry = first,
            direction = SyncDirection.ImportFromTtu,
            syncStats = false,
            statsSyncMode = StatisticsSyncMode.Merge,
            syncAudioBook = false,
        )
        repository.entries = listOf(first, second)
        repository.progressById = mapOf("book-a" to 0.75, "book-b" to 0.5)
        viewModel.reloadBookEntries()

        continueProgressLoad.complete(Unit)

        assertEquals(
            mapOf("book-a" to 0.75, "book-b" to 0.5),
            viewModel.uiState.value.bookProgressById,
        )
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

        assertEquals("Failed to import EPUB.", viewModel.uiState.value.errorMessage.testString())

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
        val localDeleteError: Throwable? = null,
        val remoteImportGate: CompletableDeferred<Unit>? = null,
        val remoteDeleteGate: CompletableDeferred<Unit>? = null,
        val remoteImportProgress: List<Double> = emptyList(),
        val migrationProgressEvents: List<LegacyBookMigrationProgress> = emptyList(),
        val syncGate: CompletableDeferred<Unit>? = null,
        val syncResult: SyncResult? = null,
        val progressLoadGate: CompletableDeferred<Unit>? = null,
        val createShelfAndMoveResult: Boolean = true,
        val createShelfAndMoveGate: CompletableDeferred<Unit>? = null,
        val createShelfAndMoveError: Throwable? = null,
        val coverModeGate: CompletableDeferred<Unit>? = null,
        private val loadPlans: ArrayDeque<LoadPlan> = ArrayDeque(),
    ) : BookshelfRepository {
        data class LoadPlan(
            val gate: CompletableDeferred<Unit>?,
            val entries: List<BookEntry>,
        )

        val loadRequests = mutableListOf<BookSortOption>()
        val progressLoadRequests = mutableListOf<List<String>>()
        val remoteLoadRequests = mutableListOf<List<String>>()
        val deletedEntries = mutableListOf<BookEntry>()
        val movedBooks = mutableListOf<Pair<Set<String>, String?>>()
        val createdShelves = mutableListOf<String>()
        val createdShelvesWithBooks = mutableListOf<Triple<String, Set<String>, Boolean>>()
        val deletedShelves = mutableListOf<String>()
        val renamedShelves = mutableListOf<Pair<String, String>>()
        val movedShelves = mutableListOf<Pair<Int, Int>>()
        val markedReadEntries = mutableListOf<BookEntry>()
        val importedRemoteEntries = mutableListOf<ImportedRemoteBook>()
        val deletedRemoteEntries = mutableListOf<RemoteBookEntry>()
        val exportedBooks = mutableListOf<BookEntry>()
        val showReadingUpdates = mutableListOf<Boolean>()
        val coverModeUpdates = mutableListOf<BookshelfCoverMode>()
        val renamedBooks = mutableListOf<Pair<BookEntry, String?>>()
        val profiledBooks = mutableListOf<Pair<BookEntry, String?>>()

        override suspend fun loadBooks(
            sortOption: BookSortOption,
            onLegacyBookMigrationProgress: (LegacyBookMigrationProgress) -> Unit,
        ): BookshelfLoadResult {
            loadRequests += sortOption
            migrationProgressEvents.forEach(onLegacyBookMigrationProgress)
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

        override suspend fun loadBookProgress(entries: List<BookEntry>): Map<String, Double> {
            progressLoadRequests += entries.map { it.metadata.id }
            progressLoadGate?.await()
            return entries.associate { it.metadata.id to progressById.getValue(it.metadata.id) }
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
            localDeleteError?.let { throw it }
            deletedEntries += entry
        }

        override suspend fun deleteRemoteBook(entry: RemoteBookEntry) {
            deletedRemoteEntries += entry
            remoteDeleteGate?.await()
            remoteDeleteError?.let { throw it }
        }

        override suspend fun deleteBooks(entries: Collection<BookEntry>) {
            localDeleteError?.let { throw it }
            deletedEntries += entries
        }

        override suspend fun moveBooks(bookIds: Set<String>, shelfName: String?) {
            movedBooks += bookIds to shelfName
        }

        override suspend fun createShelf(name: String) {
            createdShelves += name
        }

        override suspend fun createShelfAndMoveBooks(name: String, bookIds: Set<String>): List<BookShelf>? {
            createdShelvesWithBooks += Triple(name, bookIds, createShelfAndMoveResult)
            createShelfAndMoveGate?.await()
            createShelfAndMoveError?.let { throw it }
            if (!createShelfAndMoveResult) return null
            return createShelfAndMoveBooksList(shelves, name, bookIds)
        }

        override suspend fun deleteShelf(name: String) {
            deletedShelves += name
        }

        override suspend fun renameShelf(oldName: String, newName: String) {
            renamedShelves += oldName to newName
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

        override suspend fun setBookProfile(entry: BookEntry, profileId: String?) {
            profiledBooks += entry to profileId
        }

        override suspend fun changeSort(sortOption: BookSortOption) {
            settings = settings.copy(sortOption = sortOption)
        }

        override suspend fun changeShowReading(showReading: Boolean) {
            settings = settings.copy(showReading = showReading)
            showReadingUpdates += showReading
        }

        override suspend fun changeCoverMode(coverMode: BookshelfCoverMode) {
            coverModeUpdates += coverMode
            coverModeGate?.await()
            settings = settings.copy(coverMode = coverMode)
        }

        override suspend fun rebuildLookupQuery() = Unit

        override suspend fun syncBook(
            entry: BookEntry,
            direction: SyncDirection?,
            syncStats: Boolean,
            statsSyncMode: StatisticsSyncMode,
            syncAudioBook: Boolean,
        ): SyncResult {
            syncGate?.await()
            return syncResult ?: SyncResult.Synced(entry.metadata.title.orEmpty())
        }
    }
}

private fun UiText?.testString(): String? =
    when (this) {
        null -> null
        is UiText.Literal -> value
        is UiText.Resource -> when (id) {
            R.string.bookshelf_importing_named_format -> "Importing ${args[0]}..."
            R.string.bookshelf_importing_progress_format -> "Importing ${args[0]} / ${args[1]}..."
            R.string.bookshelf_legacy_migration_progress_format -> "Preparing older books ${args[0]} / ${args[1]}..."
            R.string.bookshelf_import_failed -> "Failed to import EPUB."
            R.string.bookshelf_import_failed_list_format -> "Failed to import:\n${args[0]}"
            R.string.bookshelf_scanning_folder -> "Scanning folder..."
            R.string.bookshelf_no_epub_files_found -> "No EPUB files found."
            R.string.bookshelf_syncing -> "Syncing..."
            R.string.bookshelf_already_synced_format -> "${args[0]} is already synced"
            R.string.bookshelf_exported_epub_format -> "Exported ${args[0]}."
            R.string.bookshelf_remote_books_load_failed -> "Failed to fetch books from Google Drive."
            R.string.bookshelf_remote_book_import_failed -> "Failed to import book from Google Drive."
            R.string.bookshelf_remote_book_delete_failed -> "Failed to delete book from Google Drive."
            R.string.bookshelf_shelf_name_exists -> "A shelf with this name already exists."
            R.string.bookshelf_create_shelf_failed -> "Failed to create the shelf. Try again."
            else -> "resource:$id:${args.joinToString()}"
        }
        is UiText.Plural -> "plural:$id:$quantity:${args.joinToString()}"
    }
