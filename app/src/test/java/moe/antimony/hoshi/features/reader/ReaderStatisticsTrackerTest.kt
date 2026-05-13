package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.epub.ReadingStatistics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReaderStatisticsTrackerTest {
    @Test
    fun forwardProgressUpdatesSessionTodayAndAllTimeSpeeds() {
        val clock = FakeStatisticsClock(
            millis = 1_778_623_200_000,
            date = LocalDate.parse("2026-05-13"),
        )
        val tracker = ReaderStatisticsTracker(
            title = "Book",
            initialStatistics = emptyList(),
            enabled = true,
            clock = clock,
        )

        tracker.start(currentCharacter = 100)
        clock.advance(seconds = 10)
        tracker.update(currentCharacter = 120)

        assertEquals(20, tracker.state.session.charactersRead)
        assertEquals(10.0, tracker.state.session.readingTime, 0.0)
        assertEquals(7200, tracker.state.session.lastReadingSpeed)
        assertEquals(7200, tracker.state.session.minReadingSpeed)
        assertEquals(7200, tracker.state.session.altMinReadingSpeed)
        assertEquals(7200, tracker.state.session.maxReadingSpeed)
        assertEquals(tracker.state.session, tracker.state.today.copy(dateKey = tracker.state.session.dateKey))
        assertEquals(20, tracker.state.allTime.charactersRead)
    }

    @Test
    fun backwardProgressClampsAtNegativeSessionCharacters() {
        val clock = FakeStatisticsClock()
        val tracker = ReaderStatisticsTracker(title = "Book", initialStatistics = emptyList(), enabled = true, clock = clock)

        tracker.start(currentCharacter = 100)
        clock.advance(seconds = 10)
        tracker.update(currentCharacter = 130)
        clock.advance(seconds = 10)
        tracker.update(currentCharacter = 20)

        assertEquals(0, tracker.state.session.charactersRead)
        assertEquals(20.0, tracker.state.session.readingTime, 0.0)
        assertEquals(0, tracker.state.session.lastReadingSpeed)
    }

    @Test
    fun dayRolloverStoresPreviousTodayAndStartsCurrentDateEntry() {
        val clock = FakeStatisticsClock(date = LocalDate.parse("2026-05-13"))
        val tracker = ReaderStatisticsTracker(title = "Book", initialStatistics = emptyList(), enabled = true, clock = clock)

        tracker.start(currentCharacter = 0)
        clock.advance(seconds = 10)
        tracker.update(currentCharacter = 10)
        clock.date = LocalDate.parse("2026-05-14")
        clock.advance(seconds = 10)
        tracker.update(currentCharacter = 20)

        val persisted = tracker.statisticsForPersistence()
        assertEquals(10, persisted.single { it.dateKey == "2026-05-13" }.charactersRead)
        assertEquals(10, persisted.single { it.dateKey == "2026-05-14" }.charactersRead)
        assertEquals(20, tracker.state.allTime.charactersRead)
    }

    @Test
    fun idleTicksLowerMinSpeedButNotAltMinSpeed() {
        val clock = FakeStatisticsClock()
        val tracker = ReaderStatisticsTracker(title = "Book", initialStatistics = emptyList(), enabled = true, clock = clock)

        tracker.start(currentCharacter = 0)
        clock.advance(seconds = 10)
        tracker.update(currentCharacter = 10)
        clock.advance(seconds = 10)
        tracker.update(currentCharacter = 10)

        assertEquals(1800, tracker.state.session.lastReadingSpeed)
        assertEquals(1800, tracker.state.session.minReadingSpeed)
        assertEquals(3600, tracker.state.session.altMinReadingSpeed)
        assertEquals(3600, tracker.state.session.maxReadingSpeed)
    }

    @Test
    fun startStopUsesCurrentCharacterAsBaselineAndFlushesOnStop() {
        val clock = FakeStatisticsClock()
        val tracker = ReaderStatisticsTracker(title = "Book", initialStatistics = emptyList(), enabled = true, clock = clock)

        tracker.start(currentCharacter = 50)
        clock.advance(seconds = 5)
        tracker.stop(currentCharacter = 55)

        assertFalse(tracker.state.isTracking)
        assertEquals(5, tracker.state.session.charactersRead)
        assertEquals(5.0, tracker.state.session.readingTime, 0.0)
    }

    @Test
    fun lifecyclePauseFlushesWithoutCountingBackgroundTimeAndResumeUsesCurrentBaseline() {
        val clock = FakeStatisticsClock()
        val tracker = ReaderStatisticsTracker(title = "Book", initialStatistics = emptyList(), enabled = true, clock = clock)

        tracker.start(currentCharacter = 100)
        clock.advance(seconds = 5)
        assertTrue(tracker.pause(currentCharacter = 110))
        clock.advance(seconds = 60)
        tracker.start(currentCharacter = 110)
        clock.advance(seconds = 5)
        tracker.update(currentCharacter = 120)

        assertTrue(tracker.state.isTracking)
        assertEquals(20, tracker.state.session.charactersRead)
        assertEquals(10.0, tracker.state.session.readingTime, 0.0)
    }

    @Test
    fun disabledTrackerDoesNotTrackOrPersist() {
        val tracker = ReaderStatisticsTracker(title = "Book", initialStatistics = emptyList(), enabled = false)

        tracker.start(currentCharacter = 0)
        tracker.update(currentCharacter = 100)

        assertFalse(tracker.state.isTracking)
        assertEquals(0, tracker.state.session.charactersRead)
        assertNull(tracker.statisticsForPersistenceOrNull())
    }

    @Test
    fun pageTurnAutostartStartsFromPreTurnDisplayedCharacter() {
        val clock = FakeStatisticsClock()
        val tracker = ReaderStatisticsTracker(title = "Book", initialStatistics = emptyList(), enabled = true, clock = clock)

        tracker.startForPageTurnIfNeeded(currentCharacter = 100)
        clock.advance(seconds = 10)
        tracker.update(currentCharacter = 140)

        assertTrue(tracker.state.isTracking)
        assertEquals(40, tracker.state.session.charactersRead)
    }

    private class FakeStatisticsClock(
        var millis: Long = 1_778_623_200_000,
        var date: LocalDate = LocalDate.parse("2026-05-13"),
    ) : ReaderStatisticsClock {
        override fun currentTimeMillis(): Long = millis

        override fun currentDate(): LocalDate = date

        fun advance(seconds: Long) {
            millis += seconds * 1_000
        }
    }
}
