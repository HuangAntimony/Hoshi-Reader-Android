package moe.antimony.hoshi.features.bookshelf

import android.os.SystemClock
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookInfo
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookReadingState
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.BookStatisticsStore
import moe.antimony.hoshi.epub.BookStorageLock
import moe.antimony.hoshi.epub.BookWorkRegistry
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.ReadingSession
import moe.antimony.hoshi.features.display.AppDisplaySettingsRepository
import moe.antimony.hoshi.features.reader.ReaderSettingsRepository
import moe.antimony.hoshi.features.sync.SyncFileType
import moe.antimony.hoshi.features.sync.SyncStorage
import moe.antimony.hoshi.features.sync.Timestamped
import moe.antimony.hoshi.profiles.ProfileRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generated sidecars and private DataStores only; no installed library, account or network access.
 * HoshiShelfPerf logs measure repository work, not Activity startup or cold filesystem caches.
 * Run the same APK variant and device before/after a change; elapsed times are not assertions.
 */
@RunWith(AndroidJUnit4::class)
class BookshelfLargeLibraryTest {
    @Test fun recordsSixHundredBookLoadsAndConcurrentSyncPreparation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cache = context.cacheDir.canonicalFile
        val fixture = File(cache, "hoshi-shelf-perf-${UUID.randomUUID()}").canonicalFile
        val files = fixture.resolve("isolated-files")
        val settingsJob = SupervisorJob()
        val settingsScope = CoroutineScope(settingsJob + Dispatchers.IO)
        assertEquals(cache, fixture.parentFile)
        assertTrue(fixture.name.startsWith("hoshi-shelf-perf-"))
        assertFalse(fixture.exists())
        assertFalse(files.path.startsWith(context.filesDir.canonicalPath + File.separator))

        try {
            withContext(Dispatchers.IO) {
                assertTrue(files.mkdirs())
                val sentinel = fixture.resolve("outside-library.txt").apply { writeText("untouched") }
                val preferences = files.resolve("datastore").apply { mkdirs() }
                val display = AppDisplaySettingsRepository(PreferenceDataStoreFactory.create(
                    scope = settingsScope,
                    produceFile = { preferences.resolve("display.preferences_pb") },
                ))
                val profiles = ProfileRepository(files, Dispatchers.IO)
                val settings = ReaderSettingsRepository(
                    dataStore = PreferenceDataStoreFactory.create(
                        scope = settingsScope,
                        produceFile = { preferences.resolve("reader.preferences_pb") },
                    ),
                    profileRepository = profiles,
                    displaySettings = display.settings,
                    ioDispatcher = Dispatchers.IO,
                )
                // Model an existing installation: create the profile/settings once, before timing.
                settings.update { it.copy(statisticsResetMinutes = 240) }
                assertEquals(240, settings.settings.first().statisticsResetMinutes)
                assertTrue(profiles.readerSettingsFile().isFile)
                val statistics = BookStatisticsStore(files, Dispatchers.IO, settings, BookStorageLock())
                val books = BookRepository(files, Dispatchers.IO, statistics, BookWorkRegistry())
                generateBooks(files)
                // Construct after generation so import callbacks cannot pre-populate sync records.
                val storage = SyncStorage(files, books, Dispatchers.IO).apply { syncEnabled = { false } }
                assertFalse(files.resolve("Books/.sync.json").exists())
                assertTrue(storage.state.books.isEmpty())
                Log.i(Tag, "fixture books=600 bookmarkProgress=30 statisticsOnly=20 unread=550 root=${fixture.name}")

                val baseline = timed("local.total") { loadShelf("local", books) }
                assertShelf(baseline, files)
                assertTrue(storage.state.books.isEmpty())

                timed("prepare.missingRecords") { storage.prepareLibrary() }
                assertEquals(BookCount, storage.state.books.size)
                assertTrue(storage.state.books.values.all {
                    !it.deleted && it.pending && SyncFileType.epub in it.sources
                })
                val preparedRecords = storage.state.books
                val concurrent = timed("concurrent.total") {
                    coroutineScope {
                        val start = CompletableDeferred<Unit>()
                        val preparation = async(Dispatchers.IO) {
                            start.await()
                            timed("prepare.existingRecords.concurrent") { storage.prepareLibrary() }
                        }
                        val loading = async(Dispatchers.IO) {
                            start.await()
                            timed("concurrent.shelf.total") { loadShelf("concurrent", books) }
                        }
                        start.complete(Unit)
                        preparation.await()
                        loading.await()
                    }
                }
                assertShelf(concurrent, files)
                assertEquals(baseline.entries, concurrent.entries)
                assertEquals(baseline.readingStates, concurrent.readingStates)
                assertEquals(preparedRecords, storage.state.books)
                assertEquals("untouched", sentinel.readText())
                assertTrue(files.resolve("Books/.sync.json").isFile)
                assertEquals(BookCount, files.resolve("Books").listFiles().orEmpty().count { it.isDirectory })
                assertTrue(fixture.walkTopDown().all {
                    it.canonicalPath == fixture.path || it.canonicalPath.startsWith(fixture.path + File.separator)
                })
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                settingsJob.cancelAndJoin()
                assertEquals(cache, fixture.canonicalFile.parentFile)
                assertTrue("Unable to remove isolated performance fixture", fixture.deleteRecursively())
                assertFalse(fixture.exists())
            }
        }
    }

    private fun generateBooks(files: File) {
        val json = Json { encodeDefaults = true }
        val sessionsSerializer = MapSerializer(String.serializer(), Timestamped.serializer(ReadingSession.serializer().nullable))
        val info = BookInfo(1_000, mapOf("chapter.xhtml" to BookInfo.ChapterInfo(0, 0, 1_000)))
        repeat(BookCount) { index ->
            val folder = "fixture-book-${index.toString().padStart(4, '0')}"
            val root = files.resolve("Books/$folder").apply { check(mkdirs()) }
            val metadata = BookMetadata(
                id = UUID(0, index.toLong() + 1).toString(),
                title = "Generated shelf book $index",
                folder = folder,
                lastAccess = index.toDouble() + 1,
                // Discovery and bookkeeping use sidecars; the fixture never opens this EPUB.
                epub = "fixture.epub",
                characterCount = 1_000,
            )
            val bookmark = if (index < 30) Bookmark(0, 0.25, 250) else Bookmark(0, 0.0, 0)
            root.resolve("metadata.json").writeText(json.encodeToString(BookMetadata.serializer(), metadata))
            root.resolve("bookinfo.json").writeText(json.encodeToString(BookInfo.serializer(), info))
            root.resolve("bookmark.json").writeText(json.encodeToString(Bookmark.serializer(), bookmark))
            if (index in 30 until 50) {
                val sessions = mapOf("session-$index" to Timestamped<ReadingSession?>(
                    1_700_000_060_000,
                    ReadingSession(1_700_000_000_000, 1_700_000_060_000, 100, 60.0),
                ))
                root.resolve("statistics.json").writeText(json.encodeToString(sessionsSerializer, sessions))
            }
        }
    }

    private suspend fun loadShelf(stage: String, books: BookRepository): ShelfSnapshot {
        val entries = timed("$stage.entries") { books.loadBookEntries() }
        val states = timed("$stage.readingStates") { loadBookReadingStatesById(entries, books) }
        val covers = timed("$stage.coverSources") { loadBookCoverSourcesById(entries, books) }
        assertTrue(covers.isEmpty())
        return ShelfSnapshot(entries, states)
    }

    private fun assertShelf(snapshot: ShelfSnapshot, files: File) {
        assertEquals(BookCount, snapshot.entries.size)
        assertEquals(BookCount, snapshot.entries.map { it.metadata.id }.toSet().size)
        assertEquals(UUID(0, 600).toString(), snapshot.entries.first().metadata.id)
        assertEquals(UUID(0, 1).toString(), snapshot.entries.last().metadata.id)
        assertTrue(snapshot.entries.all { it.root.canonicalFile.parentFile == files.resolve("Books").canonicalFile })
        assertEquals(BookCount, snapshot.readingStates.size)
        assertTrue(snapshot.readingStates.values.all { it.canMarkRead })
        assertEquals(30, snapshot.readingStates.values.count { it.progress == 0.25 })
        assertEquals(570, snapshot.readingStates.values.count { it.progress == 0.0 })
        assertEquals(50, snapshot.readingStates.values.count { it.canMarkUnread })
    }

    private suspend fun <T> timed(stage: String, block: suspend () -> T): T {
        val started = SystemClock.elapsedRealtimeNanos()
        return block().also {
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
            Log.i(Tag, "stage=$stage elapsedMs=$elapsedMs books=$BookCount")
        }
    }

    private data class ShelfSnapshot(
        val entries: List<BookEntry>,
        val readingStates: Map<String, BookReadingState>,
    )

    private companion object {
        const val Tag = "HoshiShelfPerf"
        const val BookCount = 600
    }
}
