package moe.antimony.hoshi.epub

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookWorkRegistryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun deletionWaitsForBookWorkAndRejectsNewWorkUntilFinished() = runTest {
        val root = temporary.newFolder()
        val registry = BookWorkRegistry()
        val order = mutableListOf<String>()
        registry.register(root) { order += "cancelled" }
        registry.delete(root) {
            assertEquals(listOf("cancelled"), order)
            try {
                registry.register(root) { }
                fail("Work must not start during deletion")
            } catch (_: IllegalStateException) { }
            root.deleteRecursively()
            order += "deleted"
        }
        assertEquals(listOf("cancelled", "deleted"), order)
    }

    @Test fun closedRegistrationCannotCancelLaterWork() = runTest {
        val root = temporary.newFolder()
        val registry = BookWorkRegistry()
        var cancelled = false
        val first = registry.register(root) { fail("Obsolete work") }
        first.close()
        registry.register(root) { cancelled = true }
        first.close()
        registry.delete(root) { }
        assertTrue(cancelled)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun repositoryDeletionWaitsForOutstandingSidecarWrite() = runTest {
        val registry = BookWorkRegistry()
        val repository = BookRepository(temporary.newFolder(), StandardTestDispatcher(testScheduler), registry)
        val root = repository.createBookDirectory("book")
        val finished = CompletableDeferred<Unit>()
        registry.register(root) {
            finished.await()
            root.resolve("sasayaki_transcript.json").writeText("final checkpoint")
        }
        val deletion = async { repository.deleteBook(root) }
        runCurrent()
        assertTrue(root.isDirectory)
        assertFalse(deletion.isCompleted)
        finished.complete(Unit)
        deletion.await()
        assertFalse(root.exists())
    }
}
