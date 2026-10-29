package moe.antimony.hoshi.features.bookshelf

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.features.sync.SyncSettings
import moe.antimony.hoshi.features.sync.SyncStorage
import moe.antimony.hoshi.profiles.ProfileState
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Generated books in a scratch store; never reads the installed library or Drive account. */
@RunWith(AndroidJUnit4::class)
class BookshelfImportProgressTest {
    @get:Rule val compose = createComposeRule()

    @Test fun batchProgressSurvivesLocalNotifications() = checkBatchProgress(eInk = false)
    @Test fun eInkBatchProgressSurvivesLocalNotifications() = checkBatchProgress(eInk = true)

    private fun checkBatchProgress(eInk: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "import-progress-${UUID.randomUUID()}").apply { mkdirs() }
        val books = BookRepository(root)
        val storage = SyncStorage(root, books, Dispatchers.IO).apply { syncEnabled = { false } }
        val entries = mutableListOf<BookEntry>()
        val loads = AtomicInteger()
        // The screen needs only shelf snapshots. Any accidental account/library operation fails.
        val repository = Proxy.newProxyInstance(
            BookshelfRepository::class.java.classLoader, arrayOf(BookshelfRepository::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "loadBooks" -> {
                    loads.incrementAndGet()
                    BookshelfLoadResult(entries.toList(), emptyMap(), emptyMap(), emptyList(), BookshelfSettings())
                }
                "loadRemoteBooks" -> RemoteBookshelfLoadResult()
                else -> error("Unexpected repository operation: ${method.name}")
            }
        } as BookshelfRepository
        lateinit var viewModel: BookshelfViewModel
        var initialized = false
        val gates = List(3) { CompletableDeferred<Unit>() }
        var observeImport = false
        val interruptedStates = mutableListOf<BookshelfUiState>()
        try {
            instrumentation.runOnMainSync { viewModel = BookshelfViewModel(repository, storage) }
            initialized = true
            compose.setContent {
                val state by viewModel.uiState.collectAsState()
                HoshiReaderTheme(eInkMode = eInk) { ImportFixtureShelf(state) }
            }
            compose.runOnIdle {
                viewModel.viewModelScope.launch {
                    viewModel.uiState.collect { state ->
                        if (observeImport && (!state.isLoading || state.blockingProgressMessage == null)) {
                            interruptedStates += state
                        }
                    }
                }
                viewModel.reloadBookEntries()
            }
            compose.waitUntil { viewModel.uiState.value.hasLoadedBooks }
            compose.runOnIdle {
                viewModel.importBooks(gates.mapIndexed { index, gate ->
                    PendingBookImport("fixture-$index", "Fixture $index.epub") {
                        gate.await()
                        val directory = books.createBookDirectory("fixture-$index")
                        val metadata = BookMetadata(
                            UUID.randomUUID().toString(), "Import fixture $index",
                            folder = directory.name, epub = "book.epub", lastAccess = 0.0,
                        )
                        withContext(Dispatchers.IO) {
                            books.saveMetadata(directory, metadata)
                            directory.resolve("book.epub").writeText("generated fixture")
                        }
                        entries += BookEntry(directory, metadata)
                        storage.handleBookImport(metadata, directory)
                        metadata.id
                    }
                })
            }
            compose.onNodeWithText(context.getString(R.string.bookshelf_importing_progress_format, 1, 3)).assertIsDisplayed()
            compose.runOnIdle { observeImport = true }
            for (index in 0..1) {
                compose.runOnIdle { gates[index].complete(Unit) }
                val message = context.getString(R.string.bookshelf_importing_progress_format, index + 2, 3)
                compose.waitUntil { viewModel.uiState.value.blockingProgressMessage == moe.antimony.hoshi.ui.UiText.Resource(
                    R.string.bookshelf_importing_progress_format, index + 2, 3,
                ) }
                // Cross the real scheduler's two-second window after each local import notification.
                SystemClock.sleep(2_200)
                compose.onNodeWithText(message).assertIsDisplayed()
                compose.runOnIdle {
                    assertTrue(viewModel.uiState.value.isLoading)
                    assertEquals(1, loads.get())
                    assertTrue(interruptedStates.isEmpty())
                }
                saveScreenshot("import-${if (eInk) "eink" else "standard"}-${index + 2}.png")
            }
            compose.runOnIdle {
                observeImport = false
                gates.last().complete(Unit)
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                !viewModel.uiState.value.isLoading && viewModel.uiState.value.bookEntries.size == 3
            }
            compose.onNodeWithText("Import fixture 0").assertIsDisplayed()
            assertEquals(2, loads.get())
            saveScreenshot("import-${if (eInk) "eink" else "standard"}-complete.png")
        } finally {
            if (initialized) runBlocking {
                withContext(Dispatchers.Main) { viewModel.viewModelScope.coroutineContext[Job]!!.cancelAndJoin() }
            }
            root.deleteRecursively()
        }
    }

    private fun saveScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull(bitmap)
        val directory = File(instrumentation.targetContext.cacheDir, "import-progress-evidence").apply { mkdirs() }
        File(directory, name).outputStream().use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap!!.recycle()
    }
}

@androidx.compose.runtime.Composable
private fun ImportFixtureShelf(state: BookshelfUiState) {
    BooksTab(
        layoutSpec = MainShellLayoutSpec.forWidthDp(400),
        bookEntries = state.bookEntries,
        remoteBookEntries = state.remoteBookEntries,
        sections = state.sections,
        bookProgressById = state.bookProgressById,
        bookReadingStateById = state.bookReadingStateById,
        remoteProgressById = state.remoteProgressById,
        remoteImportProgressById = state.remoteImportProgressById,
        remoteBusyBookIds = state.remoteBusyBookIds,
        coverSourcesById = state.coverSourcesById,
        remoteCoverSourcesById = state.remoteCoverSourcesById,
        coverMode = state.coverMode,
        hideCollapsedShelfThumbnails = state.hideCollapsedShelfThumbnails,
        sortOption = state.sortOption,
        hasLoadedBooks = state.hasLoadedBooks,
        isLoading = state.isLoading,
        blockingProgressMessage = state.blockingProgressMessage,
        shelves = state.shelves,
        isSelecting = false, selectedBookIds = emptySet(), shelfExpansionState = emptyMap(),
        sortMenuExpanded = false, onSortMenuExpandedChange = {}, onSortChange = {},
        onStartSelecting = {}, onClearSelection = {}, onToggleSelectedBook = {},
        onShelfExpandedChange = { _, _ -> }, onMoveSelectedBooks = {},
        onCreateShelfForSelectedBooks = {}, onDeleteSelectedBooks = {}, onManageShelves = {},
        onImportFiles = {}, onImportFolder = {}, onOpenBook = {}, onRefreshRemoteBooks = {},
        onImportRemoteBook = {}, onDeleteRemoteCandidate = {},
        contextMenuTarget = null, onContextMenuTargetChange = {},
        remoteContextMenuTarget = null, onRemoteContextMenuTargetChange = {},
        onDeleteCandidate = {}, onExportCandidate = {}, onMarkReadCandidate = {}, onMarkUnreadCandidate = {},
        onRenameCandidate = {}, onMoveBook = { _, _ -> }, onCreateShelfForBook = {},
        profileState = ProfileState(emptyList(), "fixture", "fixture", null, emptyMap()),
        onSetBookProfile = { _, _ -> }, syncSettings = SyncSettings(), driveAuthStatus = null,
        onSyncBook = { _, _ -> }, onDownloadBook = {},
    )
}
