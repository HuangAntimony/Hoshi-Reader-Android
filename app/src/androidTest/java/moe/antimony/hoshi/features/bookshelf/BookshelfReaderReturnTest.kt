package moe.antimony.hoshi.features.bookshelf

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookInfo
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.BookSortOption
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.LegacyBookMigrationProgress
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Renders a generated 600-entry shelf and reads only its scratch book's persisted exit save. */
@RunWith(AndroidJUnit4::class)
class BookshelfReaderReturnTest {
    @get:Rule val compose = createComposeRule()

    @Test fun returnedProgressAndRecentOrderAppearWithoutLibraryReload() = checkReturn(eInk = false)
    @Test fun eInkReturnedProgressAndRecentOrderAppearWithoutLibraryReload() = checkReturn(eInk = true)

    private fun checkReturn(eInk: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "reader-return-${UUID.randomUUID()}").apply { mkdirs() }
        val books = BookRepository(root)
        val saveJob = SupervisorJob()
        val viewModelJob = SupervisorJob()
        val saveScope = CoroutineScope(saveJob + Dispatchers.IO)
        val saveGate = CompletableDeferred<Unit>()
        var loads = 0
        val entries = List(600) { index ->
            val folder = "fixture-$index"
            BookEntry(root.resolve("Books/$folder"), BookMetadata(
                UUID(0, index.toLong() + 1).toString(), "Return fixture $index", folder = folder,
                epub = "fixture.epub", lastAccess = (600 - index).toDouble(),
            ))
        }
        val opened = entries[1]
        val unsupported = Proxy.newProxyInstance(
            BookshelfRepository::class.java.classLoader, arrayOf(BookshelfRepository::class.java),
        ) { _, method, _ -> error("Unexpected fixture operation: ${method.name}") } as BookshelfRepository
        val repository = object : BookshelfRepository by unsupported {
            override suspend fun loadBooks(sortOption: BookSortOption, onLegacyBookMigrationProgress: (LegacyBookMigrationProgress) -> Unit): BookshelfLoadResult {
                check(++loads == 1) { "Reader return must not reload all 600 books" }
                return BookshelfLoadResult(entries, mapOf(opened.metadata.id to 0.1), emptyMap(), emptyList(), BookshelfSettings())
            }
            override suspend fun loadRemoteBooks(localEntries: List<BookEntry>) = RemoteBookshelfLoadResult()
            override suspend fun openBook(entry: BookEntry): BookEntry = withContext(Dispatchers.IO) {
                entry.copy(metadata = entry.metadata.copy(lastAccess = 1_000.0)).also { books.saveMetadata(it.root, it.metadata) }
            }
            override suspend fun loadBook(entry: BookEntry): BookshelfBookSnapshot? = withContext(Dispatchers.IO) {
                loadBookshelfBookSnapshot(entry, books)
            }
        }
        lateinit var viewModel: BookshelfViewModel
        try {
            runBlocking(Dispatchers.IO) {
                books.createBookDirectory(opened.root.name)
                books.saveMetadata(opened.root, opened.metadata)
                books.saveBookInfo(opened.root, BookInfo(1_000, emptyMap()))
                books.saveBookmark(opened.root, Bookmark(0, 0.1, 100))
            }
            instrumentation.runOnMainSync { viewModel = BookshelfViewModel(repository, CoroutineScope(viewModelJob + Dispatchers.Main.immediate)) }
            compose.setContent {
                val state by viewModel.uiState.collectAsState()
                HoshiReaderTheme(eInkMode = eInk) { BookshelfFixture(state) }
            }
            compose.runOnIdle { viewModel.onBookshelfVisible() }
            compose.waitUntil { viewModel.uiState.value.hasLoadedBooks }
            compose.onNodeWithText("10.0%").assertIsDisplayed()
            compose.runOnIdle { viewModel.openBook(opened) }
            compose.waitUntil { viewModel.uiState.value.openReaderBookId != null }
            compose.runOnIdle { viewModel.consumeOpenReaderEvent() }
            val pendingSave = saveScope.launch {
                saveGate.await()
                books.saveBookmark(opened.root, Bookmark(0, 0.65, 650))
            }
            books.trackReaderSave(opened.root, pendingSave)
            compose.runOnIdle { viewModel.onBookshelfVisible() }
            compose.onNodeWithText("10.0%").assertIsDisplayed()
            val start = SystemClock.elapsedRealtime()
            saveGate.complete(Unit)
            compose.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.bookProgressById[opened.metadata.id] == 0.65 }
            compose.onNodeWithText("65.0%").assertIsDisplayed()
            val firstBounds = compose.onNodeWithText("Return fixture 1").fetchSemanticsNode().boundsInRoot
            val secondBounds = compose.onNodeWithText("Return fixture 0").fetchSemanticsNode().boundsInRoot
            assertTrue(firstBounds.top < secondBounds.top || (firstBounds.top == secondBounds.top && firstBounds.left < secondBounds.left))
            assertEquals(opened.metadata.id, viewModel.uiState.value.bookEntries.first().metadata.id)
            assertEquals(1, loads)
            assertNull(viewModel.uiState.value.errorMessage)
            Log.i("HoshiShelfReturn", "eInk=$eInk saveReleaseToDisplayedMs=${SystemClock.elapsedRealtime() - start} entries=600")
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            assertNotNull(screenshot)
            val evidence = File(context.cacheDir, "reader-return-evidence").apply { mkdirs() }
            evidence.resolve(if (eInk) "eink.png" else "standard.png").outputStream().use {
                screenshot!!.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot!!.recycle()
        } finally {
            runBlocking {
                saveJob.cancelAndJoin()
                withContext(Dispatchers.Main) { viewModelJob.cancelAndJoin() }
            }
            root.deleteRecursively()
        }
    }
}
