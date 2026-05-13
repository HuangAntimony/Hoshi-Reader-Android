package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderAutoSyncTimingTest {
    @Test
    fun paginatedPageTurnPersistsBookmarkImmediatelyLikeIos() {
        assertEquals(
            ReaderProgressPersistenceAction.SaveBookmark,
            readerProgressPersistenceAction(ReaderProgressPersistenceEvent.PaginatedPageTurnCompleted),
        )
    }

    @Test
    fun continuousScrollOnlyPersistsWhenScrollingBecomesIdleLikeIos() {
        assertEquals(
            ReaderProgressPersistenceAction.DisplayOnly,
            readerProgressPersistenceAction(ReaderProgressPersistenceEvent.ContinuousScrollChanged),
        )
        assertEquals(
            ReaderProgressPersistenceAction.SaveBookmark,
            readerProgressPersistenceAction(ReaderProgressPersistenceEvent.ContinuousScrollIdle),
        )
    }

    @Test
    fun lifecyclePauseFlushesPendingExportWithoutCreatingNewBookmark() {
        val plan = readerLifecycleAutoSyncPlan(ReaderLifecycleAutoSyncEvent.Pause)

        assertTrue(plan.flushPendingProgressSave)
        assertTrue(plan.flushAutoSyncExport)
        assertFalse(plan.saveCurrentDisplayedPosition)
        assertFalse(plan.importOnForeground)
    }

    @Test
    fun lifecycleDisposeFlushesPendingExportWithoutCreatingNewBookmark() {
        val plan = readerLifecycleAutoSyncPlan(ReaderLifecycleAutoSyncEvent.Dispose)

        assertTrue(plan.flushPendingProgressSave)
        assertTrue(plan.flushAutoSyncExport)
        assertFalse(plan.saveCurrentDisplayedPosition)
        assertFalse(plan.importOnForeground)
    }

    @Test
    fun lifecycleResumeImportsOnlyAfterIosInactiveThreshold() {
        assertFalse(
            readerLifecycleAutoSyncPlan(
                event = ReaderLifecycleAutoSyncEvent.Resume,
                inactiveElapsedMillis = AutoSyncForegroundThresholdMillis - 1,
            ).importOnForeground,
        )
        assertTrue(
            readerLifecycleAutoSyncPlan(
                event = ReaderLifecycleAutoSyncEvent.Resume,
                inactiveElapsedMillis = AutoSyncForegroundThresholdMillis,
            ).importOnForeground,
        )
    }
}
