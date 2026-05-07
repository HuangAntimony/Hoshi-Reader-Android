package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSwipeGestureTrackerTest {
    @Test
    fun horizontalMovePastThresholdTriggersBeforePointerUp() {
        val tracker = ReaderSwipeGestureTracker(minDistance = 72f)

        tracker.onDown(240f, 100f, eventTime = 1_000L)

        val result = tracker.onMove(150f, 104f, eventTime = 1_120L)

        assertTrue(result == ReaderSwipeGestureTracker.Result.LeftSwipe)
    }

    @Test
    fun shortFastHorizontalMoveTriggersBeforePointerUp() {
        val tracker = ReaderSwipeGestureTracker(minDistance = 72f)

        tracker.onDown(240f, 100f, eventTime = 1_000L)

        val result = tracker.onMove(196f, 104f, eventTime = 1_030L)

        assertTrue(result == ReaderSwipeGestureTracker.Result.LeftSwipe)
    }

    @Test
    fun tinyFastHorizontalMoveDoesNotTriggerPageSwipe() {
        val tracker = ReaderSwipeGestureTracker(minDistance = 72f)

        tracker.onDown(240f, 100f, eventTime = 1_000L)

        val result = tracker.onMove(220f, 104f, eventTime = 1_010L)

        assertTrue(result == ReaderSwipeGestureTracker.Result.None)
    }

    @Test
    fun completedSwipeDoesNotTriggerAgainOnLaterMoveOrUp() {
        val tracker = ReaderSwipeGestureTracker(minDistance = 72f)

        tracker.onDown(240f, 100f, eventTime = 1_000L)

        assertTrue(tracker.onMove(150f, 104f, eventTime = 1_120L) == ReaderSwipeGestureTracker.Result.LeftSwipe)
        assertTrue(tracker.onMove(120f, 104f, eventTime = 1_140L) == ReaderSwipeGestureTracker.Result.None)
        assertTrue(tracker.onUp(120f, 104f) == ReaderSwipeGestureTracker.Result.None)
    }

    @Test
    fun verticalMovePastThresholdDoesNotTriggerPageSwipe() {
        val tracker = ReaderSwipeGestureTracker(minDistance = 72f)

        tracker.onDown(240f, 100f, eventTime = 1_000L)

        val result = tracker.onMove(232f, 190f, eventTime = 1_120L)

        assertTrue(result == ReaderSwipeGestureTracker.Result.None)
    }

    @Test
    fun slowHorizontalDragDoesNotTriggerPageSwipe() {
        val tracker = ReaderSwipeGestureTracker(minDistance = 72f)

        tracker.onDown(240f, 100f, eventTime = 1_000L)

        val result = tracker.onMove(150f, 104f, eventTime = 1_900L)

        assertTrue(result == ReaderSwipeGestureTracker.Result.None)
    }

    @Test
    fun horizontalDragPastThresholdCanPageEvenWhenDiagonal() {
        val tracker = ReaderSwipeGestureTracker(minDistance = 72f)

        tracker.onDown(240f, 100f, eventTime = 1_000L)

        val result = tracker.onMove(150f, 152f, eventTime = 1_120L)

        assertTrue(result == ReaderSwipeGestureTracker.Result.LeftSwipe)
    }

    @Test
    fun shortTapIsReportedOnPointerUp() {
        val tracker = ReaderSwipeGestureTracker(minDistance = 72f)

        tracker.onDown(240f, 100f, eventTime = 1_000L)

        val result = tracker.onUp(242f, 103f)

        assertTrue(result is ReaderSwipeGestureTracker.Result.Tap)
        result as ReaderSwipeGestureTracker.Result.Tap
        assertEquals(242f, result.x)
        assertEquals(103f, result.y)
    }
}
