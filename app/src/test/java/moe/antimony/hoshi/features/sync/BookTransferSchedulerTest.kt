package moe.antimony.hoshi.features.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookTransferSchedulerTest {
    @Test fun changingLimitsAdmitsMoreButDoesNotCancelActiveBooks() = runTest {
        for (limit in listOf(1, 2, 8)) {
            val scheduler = BookTransferScheduler(limit)
            val started = mutableListOf<Int>()
            val gate = CompletableDeferred<Unit>()
            repeat(9) { key -> launch { scheduler.withBook("$key") { started += key; gate.await() } } }
            runCurrent()
            assertEquals(limit, started.size)
            scheduler.setLimit(8)
            runCurrent()
            assertEquals(8, started.size)
            scheduler.setLimit(1)
            runCurrent()
            assertEquals(8, started.size)
            gate.complete(Unit)
            runCurrent()
            assertEquals(9, started.size)
        }
    }

    @Test fun priorityDownloadWinsNextSlotAndSameBookNeverRunsTwice() = runTest {
        val scheduler = BookTransferScheduler(1)
        val gate = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        launch { scheduler.withBook("a") { order += "a"; gate.await() } }
        launch { scheduler.withBook("b") { order += "b" } }
        launch { scheduler.withBook("c", priority = true) { order += "c" } }
        runCurrent()
        assertEquals(listOf("a"), order)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("a", "c", "b"), order)
        scheduler.setLimit(8)
        val sameBook = CompletableDeferred<Unit>()
        launch { scheduler.withBook("x") { sameBook.await() } }
        val blocked = launch { scheduler.withBook("x") { error("same book overlapped") } }
        runCurrent()
        assertFalse(blocked.isCompleted)
        blocked.cancel()
        sameBook.complete(Unit)
    }
}
