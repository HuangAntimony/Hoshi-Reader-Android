package moe.antimony.hoshi.features.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncReloadSchedulerTest {
    @Test
    fun burstsReloadOnceTwoSecondsAfterTheFirstChange() = runTest {
        var revision = 0
        val loaded = mutableListOf<Int>()
        val scheduler = SyncReloadScheduler(backgroundScope, { false }) { loaded += revision }
        scheduler.schedule()
        runCurrent()
        advanceTimeBy(1_500)
        revision = 1
        scheduler.schedule()
        advanceTimeBy(499)
        runCurrent()
        assertEquals(emptyList<Int>(), loaded)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(1), loaded)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf(1), loaded)
        revision = 2
        scheduler.schedule()
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf(1, 2), loaded)
    }

    @Test
    fun openingReaderDuringTheDelaySkipsReloadAndLaterChangesCanReload() = runTest {
        var readerOpen = false
        var reloads = 0
        val scheduler = SyncReloadScheduler(backgroundScope, { readerOpen }) { reloads++ }
        scheduler.schedule()
        runCurrent()
        advanceTimeBy(1_000)
        readerOpen = true
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(0, reloads)
        scheduler.schedule()
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(0, reloads)
        readerOpen = false
        scheduler.schedule()
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, reloads)
    }

    @Test
    fun closingReaderBeforeTheDelayEndsAllowsReload() = runTest {
        var readerOpen = true
        var reloads = 0
        val scheduler = SyncReloadScheduler(backgroundScope, { readerOpen }) { reloads++ }
        scheduler.schedule()
        runCurrent()
        advanceTimeBy(1_000)
        readerOpen = false
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, reloads)
    }

    @Test
    fun clearingOwnerCancelsPendingReload() = runTest {
        var reloads = 0
        val scope = CoroutineScope(coroutineContext + Job())
        val scheduler = SyncReloadScheduler(scope, { false }) { reloads++ }
        scheduler.schedule()
        runCurrent()
        advanceTimeBy(1_000)
        scope.cancel()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(0, reloads)
    }
}
