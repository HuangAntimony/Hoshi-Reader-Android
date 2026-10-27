package moe.antimony.hoshi.features.reader

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import moe.antimony.hoshi.epub.ReadingSession
import moe.antimony.hoshi.features.sync.TtuStatistics
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

    @Test fun modalPauseExcludesTimeAndResumesInANewDaySession() {
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
        assertEquals(1.0, tracker.state.session.readingTime, 0.0)
        assertEquals(5, tracker.state.session.charactersRead)
        assertEquals(5, tracker.state.today.charactersRead)
        assertEquals(2.0, tracker.state.allTime.readingTime, 0.0)
        assertEquals(15, tracker.state.allTime.charactersRead)
    }

    @Test fun dayRolloverRetainsBothSessionsAndExportsTheirSeparateDays() {
        val clock = Clock(localMillis("2026-09-22T23:59:58"))
        val tracker = ReaderStatisticsTracker(emptyMap(), clock = clock)
        tracker.start(100)
        clock.now += 1_000
        tracker.update(110)
        val old = tracker.statisticsForPersistenceOrNull()!!.entries.single()

        clock.now += 2_000
        tracker.update(120)

        val sessions = tracker.statisticsForPersistenceOrNull()!!
        assertEquals(2, sessions.size)
        assertEquals(old.value.value, sessions.getValue(old.key).value)
        assertEquals(clock.now, tracker.state.session.startedAt)
        assertEquals(10, tracker.state.session.charactersRead)
        assertEquals(2.0, tracker.state.today.readingTime, 0.0)
        assertEquals(10, tracker.state.today.charactersRead)
        assertEquals(20, tracker.state.allTime.charactersRead)
        assertEquals(3.0, tracker.state.allTime.readingTime, 0.0)
        val days = TtuStatistics.export(sessions, "Book", 0)
        assertEquals(listOf("2026-09-22", "2026-09-23"), days.map { it.dateKey })
        assertEquals(listOf(10, 10), days.map { it.charactersRead })
        assertEquals(listOf(1.0, 2.0), days.map { it.readingTime })

        tracker.applySessions(sessions)
        assertNull(tracker.statisticsForPersistenceOrNull())
        assertEquals(20, tracker.state.allTime.charactersRead)
        clock.now += 1_000
        tracker.update(125)
        assertEquals(sessions.keys - old.key, tracker.statisticsForPersistenceOrNull()!!.keys)
        assertEquals(15, tracker.state.today.charactersRead)
        assertEquals(25, tracker.state.allTime.charactersRead)
    }

    @Test fun rolloverRegroupsAnAlreadySavedSessionEvenWhenReloadIsUnchanged() {
        val clock = Clock(localMillis("2026-09-22T03:59:58"))
        val tracker = ReaderStatisticsTracker(emptyMap(), resetMinutes = 240, clock = clock)
        tracker.start(0)
        clock.now += 1_000
        tracker.update(10)
        val saved = tracker.statisticsForPersistenceOrNull()!!
        tracker.applySessions(saved)

        clock.now += 2_000
        tracker.update(15)
        tracker.applySessions(saved)

        assertEquals(5, tracker.state.today.charactersRead)
        assertEquals(15, tracker.state.allTime.charactersRead)
        assertEquals(3.0, tracker.state.allTime.readingTime, 0.0)
        val pending = tracker.statisticsForPersistenceOrNull()!!
        assertEquals(1, pending.size)
        assertTrue(pending.keys.intersect(saved.keys).isEmpty())
        assertEquals(listOf("2026-09-21", "2026-09-22"),
            TtuStatistics.export(saved + pending, "Book", 240).map { it.dateKey })
    }

    @Test fun staleSaveReadbackCannotDropAnUnconfirmedPreviousDay() {
        val clock = Clock(localMillis("2026-09-22T23:59:57"))
        val tracker = ReaderStatisticsTracker(emptyMap(), clock = clock)
        tracker.start(0)
        clock.now += 1_000
        tracker.update(5)
        val stale = tracker.statisticsForPersistenceOrNull()!!
        tracker.applySessions(stale)
        clock.now += 1_000
        tracker.update(10)
        clock.now += 2_000
        tracker.update(20)

        tracker.applySessions(stale)

        val pending = tracker.statisticsForPersistenceOrNull()!!
        assertEquals(2, pending.size)
        assertEquals(10, pending.getValue(stale.keys.single()).value!!.charactersRead)
        assertEquals(20, tracker.state.allTime.charactersRead)
        assertEquals(10, tracker.state.today.charactersRead)
        tracker.applySessions(pending)
        assertNull(tracker.statisticsForPersistenceOrNull())
        assertEquals(20, tracker.state.allTime.charactersRead)
    }

    @Test fun pauseResumeAcrossDaysRetainsEveryUnconfirmedSessionWithoutBackgroundTime() {
        val clock = Clock(localMillis("2026-09-22T23:59:58"))
        val tracker = ReaderStatisticsTracker(emptyMap(), clock = clock)
        tracker.start(0)
        clock.now += 1_000
        tracker.pause(10)
        clock.now = localMillis("2026-09-23T12:00:00")
        tracker.startForPageTurnIfNeeded(100)
        clock.now += 1_000
        tracker.pause(105)
        clock.now = localMillis("2026-09-24T12:00:00")
        tracker.start(200)
        clock.now += 1_000
        tracker.update(207)

        val pending = tracker.statisticsForPersistenceOrNull()!!
        assertEquals(3, pending.size)
        assertEquals(listOf(10, 5, 7), TtuStatistics.export(pending, "Book", 0).map { it.charactersRead })
        assertEquals(listOf(1.0, 1.0, 1.0), TtuStatistics.export(pending, "Book", 0).map { it.readingTime })
        assertEquals(7, tracker.state.today.charactersRead)
        assertEquals(22, tracker.state.allTime.charactersRead)
        assertEquals(3.0, tracker.state.allTime.readingTime, 0.0)
    }

    @Test fun deletingAnUnconfirmedPreviousDayDoesNotResurrectItOnSave() {
        val clock = Clock(localMillis("2026-09-22T23:59:58"))
        val tracker = ReaderStatisticsTracker(emptyMap(), clock = clock)
        tracker.start(0)
        clock.now += 1_000
        tracker.update(10)
        val oldId = tracker.statisticsForPersistenceOrNull()!!.keys.single()
        clock.now += 2_000
        tracker.update(15)

        tracker.applySessions(mapOf(oldId to Timestamped(clock.now, null)))

        assertEquals(5, tracker.state.allTime.charactersRead)
        assertEquals(5, tracker.state.today.charactersRead)
        assertFalse(tracker.statisticsForPersistenceOrNull()!!.containsKey(oldId))
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

    private fun localMillis(dateTime: String): Long =
        LocalDateTime.parse(dateTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
