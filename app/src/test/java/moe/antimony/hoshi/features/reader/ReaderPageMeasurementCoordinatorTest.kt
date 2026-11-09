package moe.antimony.hoshi.features.reader

import kotlinx.coroutines.*
import moe.antimony.hoshi.epub.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderPageMeasurementCoordinatorTest {
    @get:Rule val temp = TemporaryFolder()
    private val book = EpubBook("Book", chapters = (0..1).map { EpubChapter("$it", "$it", "text/html", "甲乙") },
        bookInfo = BookInfo(4, (0..1).associate { "$it" to BookInfo.ChapterInfo(it, it * 2, 2) }))

    @Test fun cancellationRetainsOnlyCompletedSpinesAndCacheHitDoesNoMeasurement() = runBlocking {
        val repository = ReaderPageCountRepository(temp.newFolder(), Dispatchers.IO)
        val coordinator = ReaderPageMeasurementCoordinator(repository)
        val secondStarted = CompletableDeferred<Unit>()
        val task = launch {
            coordinator.pagesFor(book, "book", "layout") { spine ->
                if (spine == 1) { secondStarted.complete(Unit); awaitCancellation() }
                listOf(0, 1)
            }
        }
        secondStarted.await()
        task.cancelAndJoin()
        assertNull(repository.load("book", "layout", book))
        val measured = mutableListOf<Int>()
        assertEquals(3, coordinator.pagesFor(book, "book", "layout") { measured += it; listOf(0) }.totalPages)
        assertEquals(listOf(1), measured)
        assertEquals(3, ReaderPageMeasurementCoordinator(repository).pagesFor(book, "book", "layout") {
            error("cache hit must not construct a measuring WebView")
        }.totalPages)
        measured.clear()
        coordinator.pagesFor(book, "book", "new-layout") { measured += it; listOf(0) }
        assertEquals(listOf(0, 1), measured)
    }

    @Test fun invalidSpineDoesNotPersistAFalseTotal() = runBlocking {
        val repository = ReaderPageCountRepository(temp.newFolder(), Dispatchers.IO)
        val result = runCatching { ReaderPageMeasurementCoordinator(repository).pagesFor(book, "book", "layout") { listOf(3) } }
        assertTrue(result.isFailure)
        assertNull(repository.load("book", "layout", book))
    }

    @Test fun lateMeasurementCannotOverwriteNewLayoutOrCache() = runBlocking {
        val repository = ReaderPageCountRepository(temp.newFolder(), Dispatchers.IO)
        val coordinator = ReaderPageMeasurementCoordinator(repository)
        val started = CompletableDeferred<Unit>()
        val late = CompletableDeferred<List<Int>>()
        val old = async {
            runCatching { coordinator.pagesFor(book, "book", "old") { started.complete(Unit); late.await() } }
        }
        started.await()
        coordinator.pagesFor(book, "book", "new") { listOf(0) }
        late.complete(listOf(0, 1))
        assertTrue(old.await().exceptionOrNull() is CancellationException)
        assertEquals(2, repository.load("book", "new", book)?.totalPages)
        assertNull(repository.load("book", "old", book))
    }
}
