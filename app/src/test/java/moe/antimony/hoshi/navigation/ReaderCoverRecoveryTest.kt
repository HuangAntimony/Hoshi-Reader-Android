package moe.antimony.hoshi.navigation

import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.antimony.hoshi.epub.*
import moe.antimony.hoshi.features.sync.SyncFileType
import moe.antimony.hoshi.features.sync.SyncStorage
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderCoverRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val repository by lazy { BookRepository(temporary.root) }
    private val bitmap = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1, 2, 3)
    private val book = EpubBook(
        title = "Book", chapters = listOf(EpubChapter("text", "text.xhtml", "application/xhtml+xml", "abcdefghij")),
        coverHref = "Images/cover.png", resources = mapOf("Images/cover.png" to EpubResource("image/png", bitmap)),
    )
    private val bookmark = Bookmark(0, 0.4, 4, 123.0)

    private suspend fun fixture(cover: String?): BookEntry {
        val root = repository.createBookDirectory("book")
        val metadata = BookMetadata(UUID.randomUUID().toString(), "Custom title", cover = cover?.let { "Books/book/$it" },
            folder = "book", lastAccess = 1.0, epub = "book.epub")
        root.resolve("book.epub").writeText("original EPUB bytes")
        repository.saveMetadata(root, metadata)
        repository.saveBookInfo(root, book.bookInfo)
        repository.saveBookmark(root, bookmark)
        return BookEntry(root, metadata)
    }

    private suspend fun open(entry: BookEntry, parsedBook: EpubBook = book): ReaderRouteLoadState.Ready {
        val holder = ReaderRouteStateHolder(repository, object : ReaderRouteEpubParser {
            override fun parse(root: File, cachedBookInfo: BookInfo?) = parsedBook
        })
        return holder.load(entry.metadata.id) as ReaderRouteLoadState.Ready
    }

    @Test fun openingLegacySvgRestoresCoverAndMarksOnlyCoverForSyncOnce() = runBlocking {
        val entry = fixture("cover.svg")
        entry.root.resolve("cover.svg").writeText("<svg><image href='Images/cover.png'/></svg>")
        entry.root.resolve("sasayaki_match.json").writeText("{}")
        val storage = SyncStorage(temporary.root, repository, Dispatchers.IO)
        storage.prepareLibrary()
        storage.updateRecord("book") { it.copy(pending = false, sources = it.sources.mapValues { 100L }) }
        val before = storage.state.books.getValue("book")

        val ready = open(entry)

        assertArrayEquals(bitmap, ready.bookCoverFile!!.readBytes())
        assertEquals("Custom title", ready.entry.metadata.title)
        assertEquals(bookmark, ready.bookmark)
        assertEquals("original EPUB bytes", entry.root.resolve("book.epub").readText())
        assertEquals("{}", entry.root.resolve("sasayaki_match.json").readText())
        val after = storage.state.books.getValue("book")
        assertTrue(after.pending)
        assertTrue(after.sources.getValue(SyncFileType.cover) > 100L)
        assertEquals(before.sources - SyncFileType.cover, after.sources - SyncFileType.cover)
        assertEquals(ready.bookCoverFile.canonicalFile, storage.sourceURL("book", SyncFileType.cover)!!.canonicalFile)
        assertTrue(ready.bookCoverFile.setLastModified(123_000L))

        open(entry)

        assertEquals(after, storage.state.books.getValue("book"))
        assertEquals(123_000L, ready.bookCoverFile.lastModified())
    }

    @Test fun openingMissingCoverReadsPackedResourcesEvenWhenMetadataHasAPath() = runBlocking {
        val entry = fixture("missing.png")
        assertArrayEquals(bitmap, open(entry).bookCoverFile!!.readBytes())
    }

    @Test fun openingWithoutCoverMetadataRecoversFromPackedResources() = runBlocking {
        val entry = fixture(null)
        assertArrayEquals(bitmap, open(entry).bookCoverFile!!.readBytes())
    }

    @Test fun openingPreservesExistingBitmapCover() = runBlocking {
        val entry = fixture("custom.png")
        val cover = entry.root.resolve("custom.png").apply { writeBytes(bitmap + 42) }
        val changes = mutableListOf<SyncFileType?>()
        repository.onBookChange = { _, type -> changes += type }
        assertEquals(cover.canonicalFile, open(entry).bookCoverFile!!.canonicalFile)
        assertArrayEquals(bitmap + 42, cover.readBytes())
        assertTrue(changes.isEmpty())
    }

    @Test fun openingRepairsSvgEvenWhenRemoteFileWasNamedJpeg() = runBlocking {
        val entry = fixture("cover_1_6.jpeg")
        entry.root.resolve("cover_1_6.jpeg").writeText("<?xml version='1.0'?><svg xmlns='http://www.w3.org/2000/svg'><image href='cover.png'/></svg>")
        assertArrayEquals(bitmap, open(entry).bookCoverFile!!.readBytes())
    }

    @Test fun complexSvgAndUnavailableResourcesDoNotChangeMetadataOrSync() = runBlocking {
        val entry = fixture("complex.svg")
        val svg = entry.root.resolve("complex.svg").apply { writeText("<svg><text>Complex</text></svg>") }
        val changes = mutableListOf<SyncFileType?>()
        repository.onBookChange = { _, type -> changes += type }
        val complex = book.copy(coverHref = "complex.svg", resources = mapOf("complex.svg" to EpubResource("image/svg+xml", svg.readBytes())))
        assertEquals(svg.canonicalFile, open(entry, complex).bookCoverFile!!.canonicalFile)
        assertEquals(svg.canonicalFile, open(entry, book.copy(resources = emptyMap())).bookCoverFile!!.canonicalFile)
        assertEquals(entry.metadata.cover, repository.loadMetadata(entry.root)!!.cover)
        assertTrue(changes.isEmpty())
    }

    @Test fun bookWithoutCoverKeepsPlaceholderAndDoesNotDirtySyncOnRepeatedOpen() = runBlocking {
        val entry = fixture(null)
        val changes = mutableListOf<SyncFileType?>()
        repository.onBookChange = { _, type -> changes += type }
        val noCover = book.copy(coverHref = null)
        repeat(2) {
            val ready = open(entry, noCover)
            assertNull(ready.bookCoverFile)
            assertNull(ready.entry.metadata.cover)
            assertEquals(bookmark, ready.bookmark)
        }
        assertTrue(changes.isEmpty())
        assertFalse(entry.root.listFiles()!!.any { it.name.startsWith("cover") })
    }

    @Test fun failedSyncBookkeepingDoesNotPreventReadingAndRetriesOnNextOpen() = runBlocking {
        val entry = fixture("cover.svg")
        val old = entry.root.resolve("cover.svg").apply { writeText("<svg/>") }
        val failure = IOException("Fixture sync bookkeeping write failed")
        repository.onBookChange = { _, _ -> throw failure }
        ErrorDiagnosticEvents.buffer.drain()
        val ready = open(entry)
        assertEquals(old.canonicalFile, ready.bookCoverFile!!.canonicalFile)
        assertEquals(bookmark, ready.bookmark)
        assertEquals(entry.metadata.cover, repository.loadMetadata(entry.root)!!.cover)
        val recorded = ErrorDiagnosticEvents.buffer.drain().single().error
        assertTrue(generateSequence(recorded) { it.cause }.any { it === failure })
        assertFalse(entry.root.listFiles()!!.any { it.name.endsWith(".tmp") })
        assertEquals(listOf("cover.svg"), entry.root.listFiles()!!.filter { it.name.startsWith("cover") }.map { it.name })
        val changes = mutableListOf<SyncFileType?>()
        repository.onBookChange = { _, type -> changes += type }
        assertArrayEquals(bitmap, open(entry).bookCoverFile!!.readBytes())
        assertEquals(listOf(SyncFileType.cover), changes)
    }

    @Test fun cancellationFinishesCoverPublicationAndSyncMarkingTogether() = runBlocking {
        withTimeout(5_000) {
            val entry = fixture("cover.svg")
            entry.root.resolve("cover.svg").writeText("<svg/>")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val changes = mutableListOf<SyncFileType?>()
            repository.onBookChange = { _, type ->
                entered.complete(Unit)
                release.await()
                changes += type
            }
            ErrorDiagnosticEvents.buffer.drain()
            val opening = async { open(entry) }
            entered.await()
            opening.cancel()
            release.complete(Unit)
            opening.join()
            assertTrue(opening.isCancelled)
            assertEquals(listOf(SyncFileType.cover), changes)
            val restored = repository.coverFile(BookEntry(entry.root, repository.loadMetadata(entry.root)!!))!!
            assertArrayEquals(bitmap, restored.readBytes())
            assertEquals(bookmark, repository.loadBookmark(entry.root))
            assertTrue(ErrorDiagnosticEvents.buffer.drain().isEmpty())
        }
    }
}
