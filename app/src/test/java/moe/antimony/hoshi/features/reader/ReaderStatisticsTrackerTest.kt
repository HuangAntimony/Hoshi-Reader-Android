package moe.antimony.hoshi.features.reader

import java.time.Instant
import moe.antimony.hoshi.epub.ReadingSession
import moe.antimony.hoshi.features.sync.Timestamped
import org.junit.Assert.*
import org.junit.Test

class ReaderStatisticsTrackerTest {
    private class Clock(var now: Long = Instant.parse("2026-09-22T12:00:00Z").toEpochMilli()) : ReaderStatisticsClock {
        override fun currentTimeMillis() = now
    }

    @Test fun readingUpdatesOneSessionAndSavedHistoryIsCountedOnce() {
        val clock = Clock()
        val old = ReadingSession(clock.now - 60000, clock.now, 100, 60.0)
        val tracker = ReaderStatisticsTracker(mapOf("old" to Timestamped<ReadingSession?>(1, old)), clock = clock)
        assertNull(tracker.statisticsForPersistenceOrNull())
        tracker.start(100)
        clock.now += 60000
        tracker.update(150)
        val saved = tracker.statisticsForPersistenceOrNull()!!
        assertEquals(50, saved.values.single().value!!.charactersRead)
        assertEquals(150, tracker.state.allTime.charactersRead)
        tracker.applySessions(mapOf("old" to Timestamped<ReadingSession?>(1, old)) + saved)
        assertEquals(150, tracker.state.allTime.charactersRead)
        assertNull(tracker.statisticsForPersistenceOrNull())
        clock.now += 60000
        tracker.update(175)
        assertEquals(saved.keys, tracker.statisticsForPersistenceOrNull()!!.keys)
        assertEquals(75, tracker.state.session.charactersRead)
        assertEquals(120.0, tracker.state.session.readingTime, 0.0)
    }

    @Test fun pauseResumeAndPageTurnRestartKeepTheSameSitting() {
        val clock = Clock()
        val tracker = ReaderStatisticsTracker(emptyMap(), clock = clock)
        tracker.startForPageTurnIfNeeded(10)
        clock.now += 1000
        tracker.pause(20)
        val id = tracker.statisticsForPersistenceOrNull()!!.keys.single()
        clock.now += 10000
        tracker.startForPageTurnIfNeeded(20)
        clock.now += 1000
        tracker.stop(30)
        assertEquals(id, tracker.statisticsForPersistenceOrNull()!!.keys.single())
        assertEquals(2.0, tracker.state.session.readingTime, 0.0)
        assertEquals(20, tracker.state.session.charactersRead)
    }

    @Test fun deletionStartsAnEmptyNewSessionWhileRemoteEditIsOverwrittenByNextSave() {
        val clock = Clock()
        val tracker = ReaderStatisticsTracker(emptyMap(), clock = clock)
        tracker.start(0)
        clock.now += 1000
        tracker.update(20)
        val saved = tracker.statisticsForPersistenceOrNull()!!
        val id = saved.keys.single()
        tracker.applySessions(mapOf(id to Timestamped(clock.now, tracker.state.session.copy(charactersRead = 999))))
        assertEquals(20, tracker.statisticsForPersistenceOrNull()!!.getValue(id).value!!.charactersRead)
        tracker.applySessions(mapOf(id to Timestamped(clock.now, null)))
        assertEquals(0, tracker.state.session.charactersRead)
        assertNull(tracker.statisticsForPersistenceOrNull())
        clock.now += 1000
        tracker.update(30)
        assertNotEquals(id, tracker.statisticsForPersistenceOrNull()!!.keys.single())
        assertEquals(10, tracker.state.session.charactersRead)
    }

    @Test fun backwardReadingCannotMakeCharactersNegativeAndSyncJumpResetsBaseline() {
        val clock = Clock()
        val tracker = ReaderStatisticsTracker(emptyMap(), clock = clock)
        tracker.start(100)
        clock.now += 1000
        tracker.update(120)
        clock.now += 1000
        tracker.update(10)
        assertEquals(0, tracker.state.session.charactersRead)
        tracker.resetBaseline(1000)
        clock.now += 1000
        tracker.update(1005)
        assertEquals(5, tracker.state.session.charactersRead)
    }

    @Test fun modalPauseExcludesTimeAndSessionsStayOnTheirStartDay() {
        val clock = Clock()
        val tracker = ReaderStatisticsTracker(emptyMap(), clock = clock)
        tracker.start(0)
        clock.now += 1000
        tracker.setModalPaused(true, 10)
        clock.now += 86400000
        tracker.update(1000)
        tracker.setModalPaused(false, 1000)
        clock.now += 1000
        tracker.update(1005)
        assertEquals(2.0, tracker.state.session.readingTime, 0.0)
        assertEquals(15, tracker.state.session.charactersRead)
        assertEquals(0, tracker.state.today.charactersRead)
        assertEquals(15, tracker.state.allTime.charactersRead)
    }

    @Test fun changingResetTimeRegroupsWithoutChangingSessionIdsOrValues() {
        val clock = Clock()
        val tracker = ReaderStatisticsTracker(emptyMap(), clock = clock)
        tracker.start(0)
        clock.now += 1000
        tracker.update(5)
        val before = tracker.statisticsForPersistenceOrNull()
        tracker.resetMinutes = 240
        assertEquals(before, tracker.statisticsForPersistenceOrNull())
    }
}
