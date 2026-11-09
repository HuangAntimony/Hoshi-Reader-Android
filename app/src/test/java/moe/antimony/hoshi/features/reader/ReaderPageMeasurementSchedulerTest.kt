package moe.antimony.hoshi.features.reader

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderPageMeasurementSchedulerTest {
    @Test fun restoredReaderStartsImmediatelyUnlessInputIsStillSettling() = runTest {
        val scheduler = ReaderPageMeasurementScheduler(100) { testScheduler.currentTime }
        scheduler.setReaderReady(true)
        val first = async { scheduler.awaitIdle() }
        runCurrent()
        assertTrue("restore completion alone must not add a quiet second", first.isCompleted)
        scheduler.onForegroundActivity()
        scheduler.setReaderReady(false)
        advanceTimeBy(40)
        scheduler.setReaderReady(true)
        val next = async { scheduler.awaitIdle() }
        runCurrent()
        assertFalse(next.isCompleted)
        advanceTimeBy(60)
        runCurrent()
        assertTrue("restore must not restart an already-running input quiet period", next.isCompleted)
    }

    @Test fun restoreCancelsNativeWorkSynchronouslyButPageActivityOnlyPausesIt() = runTest {
        val scheduler = ReaderPageMeasurementScheduler(100) { testScheduler.currentTime }
        var cancellations = 0
        scheduler.onRestoreStarted = { cancellations++ }
        scheduler.setReaderReady(true)
        scheduler.onForegroundActivity()
        assertEquals(0, cancellations)
        scheduler.setReaderReady(false)
        assertEquals(1, cancellations)
        scheduler.setReaderReady(false)
        assertEquals(1, cancellations)
    }

    @Test fun foregroundWorkDefersMeasurementUntilReadyAndQuiet() = runTest {
        val scheduler = ReaderPageMeasurementScheduler(100) { testScheduler.currentTime }
        val start = async { scheduler.awaitIdle(); true }
        runCurrent()
        advanceTimeBy(200)
        assertFalse(start.isCompleted)
        scheduler.setReaderReady(true)
        scheduler.onForegroundActivity()
        advanceTimeBy(80)
        scheduler.onForegroundActivity()
        advanceTimeBy(99)
        assertFalse(scheduler.isIdle)
        assertFalse(start.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(start.await())
        scheduler.setReaderReady(false)
        assertFalse(scheduler.isIdle)
    }

    @Test fun intentionalPausesDoNotConsumeMeasurementTimeoutAndCancellationPropagates() = runTest {
        val scheduler = ReaderPageMeasurementScheduler(100) { testScheduler.currentTime }
        val task = async { runCatching { scheduler.withIdleTimeout(500) { awaitCancellation() } } }
        advanceTimeBy(2_000)
        assertFalse(task.isCompleted)
        scheduler.setReaderReady(true)
        scheduler.onForegroundActivity()
        advanceTimeBy(590)
        scheduler.onForegroundActivity()
        advanceTimeBy(590)
        assertFalse(task.isCompleted)
        advanceTimeBy(11)
        runCurrent()
        assertTrue(task.await().exceptionOrNull() is IOException)
        val cancelled = launch { scheduler.awaitIdle(); awaitCancellation() }
        cancelled.cancelAndJoin()
        assertTrue(cancelled.isCancelled)
    }
}
