package moe.antimony.hoshi.features.sync

import org.junit.Assert.*
import org.junit.Test

class SyncTransferTrackerTest {
    @Test fun includesConfirmationTimeWithoutThreeSecondCliffAndEventuallyStalls() {
        var now = 0L
        val tracker = SyncTransferTracker { now }
        val id = tracker.begin("a", SyncTransferDirection.Upload, SyncFileType.epub)
        tracker.update("a", id, DriveTransferUpdate(0, 200))
        now = 1000
        tracker.update("a", id, DriveTransferUpdate(100, 200, phase = SyncTransferPhase.AwaitingResponse, deltaBytes = 100))
        assertEquals(100.0, tracker.snapshot().getValue("a").bytesPerSecond, 0.01)
        now = 5000
        assertEquals(20.0, tracker.snapshot().getValue("a").bytesPerSecond, 0.01)
        tracker.update("a", id, DriveTransferUpdate(100, 200, confirmedBytes = 100))
        now = 10_000
        assertEquals(10.0, tracker.snapshot().getValue("a").bytesPerSecond, 0.01)
        now = 65_000
        assertEquals(0.0, tracker.snapshot().getValue("a").bytesPerSecond, 0.01)
    }

    @Test fun excludesPreparationAndPreviouslyUploadedBytesFromResumedSpeed() {
        var now = 0L
        val tracker = SyncTransferTracker { now }
        val id = tracker.begin("a", SyncTransferDirection.Upload, SyncFileType.epub)
        now = 20_000
        tracker.update("a", id, DriveTransferUpdate(900, 2000, 900))
        now = 21_000
        tracker.update("a", id, DriveTransferUpdate(1000, 2000, 900, deltaBytes = 100))
        assertEquals(100.0, tracker.snapshot().getValue("a").bytesPerSecond, 0.01)
    }

    @Test fun retryRollsBackUsefulProgressAndDoesNotCountDuplicateTraffic() {
        var now = 0L
        val tracker = SyncTransferTracker { now }
        val id = tracker.begin("a", SyncTransferDirection.Upload, SyncFileType.epub)
        tracker.update("a", id, DriveTransferUpdate(0, 200))
        now = 1000
        tracker.update("a", id, DriveTransferUpdate(100, 200, deltaBytes = 100))
        tracker.update("a", id, DriveTransferUpdate(50, 200, 50, SyncTransferPhase.Retrying))
        assertEquals(50.0, tracker.snapshot().getValue("a").bytesPerSecond, 0.01)
        now = 2000
        tracker.update("a", id, DriveTransferUpdate(100, 200, 50, deltaBytes = 50))
        assertEquals(50.0, tracker.snapshot().getValue("a").bytesPerSecond, 0.01)
    }

    @Test fun expiredResumedSessionCountsReplacementUploadFromZero() {
        var now = 0L
        val tracker = SyncTransferTracker { now }
        val id = tracker.begin("a", SyncTransferDirection.Upload, SyncFileType.epub)
        tracker.update("a", id, DriveTransferUpdate(900, 2000, 900))
        now = 1000
        tracker.update("a", id, DriveTransferUpdate(1000, 2000, 900, deltaBytes = 100))
        tracker.update("a", id, DriveTransferUpdate(0, 2000, phase = SyncTransferPhase.Preparing))
        now = 2000
        tracker.update("a", id, DriveTransferUpdate(100, 2000, deltaBytes = 100))
        assertEquals(50.0, tracker.snapshot().getValue("a").bytesPerSecond, 0.01)
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

    @Test fun bothDirectionsUseIndependentElapsedTimeAndFinishImmediately() {
        var now = 0L
        val tracker = SyncTransferTracker { now }
        val a = tracker.begin("a", SyncTransferDirection.Upload, SyncFileType.epub)
        val b = tracker.begin("b", SyncTransferDirection.Download, SyncFileType.cover)
        tracker.update("a", a, DriveTransferUpdate(0, 200))
        tracker.update("b", b, DriveTransferUpdate(0, 200))
        now = 1000
        tracker.update("a", a, DriveTransferUpdate(100, 200, deltaBytes = 100))
        tracker.update("b", b, DriveTransferUpdate(40, 200, 40, deltaBytes = 40))
        now = 5000
        assertEquals(28.0, tracker.snapshot().values.sumOf { it.bytesPerSecond }, 0.01)
        tracker.finish("a", a)
        assertEquals(setOf("b"), tracker.snapshot().keys)
    }
}
