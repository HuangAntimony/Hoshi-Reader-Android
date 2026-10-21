package moe.antimony.hoshi.features.sync

import org.junit.Assert.*
import org.junit.Test

class SyncTransferTrackerTest {
    @Test fun tracksAllBooksSeparatelyAndAgesSpeedToZero() {
        var now = 0L
        val tracker = SyncTransferTracker { now }
        val a = tracker.begin("a", SyncTransferDirection.Upload, SyncFileType.epub)
        val b = tracker.begin("b", SyncTransferDirection.Download, SyncFileType.cover)
        now = 1000
        tracker.update("a", a, DriveTransferUpdate(100, 200, deltaBytes = 100))
        tracker.update("b", b, DriveTransferUpdate(40, 80, deltaBytes = 40))
        val active = tracker.snapshot()
        assertEquals(setOf("a", "b"), active.keys)
        assertEquals(100.0, active.getValue("a").bytesPerSecond, 0.01)
        assertEquals(40.0, active.getValue("b").bytesPerSecond, 0.01)
        now = 4001
        assertTrue(tracker.snapshot().values.all { it.bytesPerSecond == 0.0 })
    }

    @Test fun staleCallbacksAndFinishesCannotOverwriteReplacement() {
        val tracker = SyncTransferTracker { 0L }
        val old = tracker.begin("a", SyncTransferDirection.Upload, SyncFileType.epub)
        val fresh = tracker.begin("a", SyncTransferDirection.Download, SyncFileType.cover)
        tracker.update("a", old, DriveTransferUpdate(100, 100, deltaBytes = 100))
        tracker.finish("a", old)
        assertEquals(fresh, tracker.snapshot().getValue("a").id)
        assertEquals(0L, tracker.snapshot().getValue("a").bytes)
        tracker.finish("a", fresh)
        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test fun retryCanRollBackProgressWithoutSubtractingTransferredTraffic() {
        var now = 0L
        val tracker = SyncTransferTracker { now }
        val id = tracker.begin("a", SyncTransferDirection.Upload, SyncFileType.epub)
        now = 1000
        tracker.update("a", id, DriveTransferUpdate(100, 200, deltaBytes = 100))
        tracker.update("a", id, DriveTransferUpdate(50, 200, confirmedBytes = 50, phase = SyncTransferPhase.Retrying))
        tracker.update("a", id, DriveTransferUpdate(100, 200, confirmedBytes = 50, deltaBytes = 50))
        assertEquals(150.0, tracker.snapshot().getValue("a").bytesPerSecond, 0.01)
        assertEquals(50L, tracker.snapshot().getValue("a").confirmedBytes)
    }
}
