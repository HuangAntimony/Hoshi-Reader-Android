package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.features.statistics.StatisticsDateProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReaderStatisticsTrackerTest {
    @Test
    fun onlyChangedDaysAreReturnedForSaving() {
        val old = ReadingStatistics("Book", "2020-01-01", charactersRead = 123, readingTime = 60.0)
        val clock = FakeStatisticsClock()
        val tracker = ReaderStatisticsTracker("Book", listOf(old), clock = clock)
        assertNull(tracker.statisticsForPersistenceOrNull())
        tracker.start(0)
        clock.advance(seconds = 10)
        tracker.update(20)
        val changed = requireNotNull(tracker.statisticsForPersistenceOrNull())
        assertEquals(1, changed.size)
        assertEquals(20, changed.single().charactersRead)
        assertFalse(changed.any { it.dateKey == old.dateKey })
        assertEquals(143, tracker.state.allTime.charactersRead)
    }

    @Test
    fun forwardProgressUpdatesSessionTodayAndAllTimeSpeeds() {
        val clock = FakeStatisticsClock(
            millis = 1_778_623_200_000,
        )
        val tracker = ReaderStatisticsTracker(
            title = "Book",
            initialStatistics = emptyList(),
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
        val tracker = ReaderStatisticsTracker(title = "Book", initialStatistics = emptyList(), clock = clock)

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
        val clock = FakeStatisticsClock()
        val dateProvider = FakeStatisticsDateProvider(LocalDate.parse("2026-05-13"))
        val tracker = ReaderStatisticsTracker(
            title = "Book",
            initialStatistics = emptyList(),
            clock = clock,
            dateProvider = dateProvider,
        )

        tracker.start(currentCharacter = 0)
        clock.advance(seconds = 10)
        tracker.update(currentCharacter = 10)
        dateProvider.date = LocalDate.parse("2026-05-14")
        clock.advance(seconds = 10)
        tracker.update(currentCharacter = 20)

        val persisted = tracker.statisticsForPersistence()
        assertEquals(10, persisted.single { it.dateKey == "2026-05-13" }.charactersRead)
        assertEquals(10, persisted.single { it.dateKey == "2026-05-14" }.charactersRead)
        assertEquals(20, tracker.state.allTime.charactersRead)
    }

    @Test
    fun configuredResetMinutesDetermineTrackerStatisticsDate() {
        val tracker = ReaderStatisticsTracker(
            title = "Book",
            initialStatistics = emptyList(),
            resetMinutes = 105,
            dateProvider = object : StatisticsDateProvider {
                override fun currentDate(resetMinutes: Int): LocalDate =
                    if (resetMinutes == 105) LocalDate.parse("2026-05-12") else LocalDate.MIN
            },
        )

        assertEquals("2026-05-12", tracker.state.today.dateKey)
    }

    @Test
    fun idleTicksLowerMinSpeedButNotAltMinSpeed() {
        val clock = FakeStatisticsClock()
        val tracker = ReaderStatisticsTracker(title = "Book", initialStatistics = emptyList(), clock = clock)

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
        val tracker = ReaderStatisticsTracker(title = "Book", initialStatistics = emptyList(), clock = clock)

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
        val tracker = ReaderStatisticsTracker(title = "Book", initialStatistics = emptyList(), clock = clock)

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
    fun modalPauseKeepsTrackingEnabledAndResumesFromCurrentBaseline() {
        val clock = FakeStatisticsClock()
        val tracker = ReaderStatisticsTracker(title = "Book", initialStatistics = emptyList(), clock = clock)

        tracker.start(currentCharacter = 100)
        clock.advance(seconds = 5)
        tracker.setModalPaused(paused = true, currentCharacter = 110)
        clock.advance(seconds = 60)
        tracker.update(currentCharacter = 200)

        assertTrue(tracker.state.isTracking)
        assertEquals(10, tracker.state.session.charactersRead)
        assertEquals(5.0, tracker.state.session.readingTime, 0.0)

        tracker.setModalPaused(paused = false, currentCharacter = 200)
        clock.advance(seconds = 5)
        tracker.update(currentCharacter = 210)

        assertEquals(20, tracker.state.session.charactersRead)
        assertEquals(10.0, tracker.state.session.readingTime, 0.0)
    }

    @Test
    fun lifecycleResumeDoesNotCountWhileModalRemainsOpen() {
        val clock = FakeStatisticsClock()
        val tracker = ReaderStatisticsTracker(title = "Book", initialStatistics = emptyList(), clock = clock)

        tracker.start(currentCharacter = 100)
        tracker.setModalPaused(paused = true, currentCharacter = 100)
        assertTrue(tracker.pause(currentCharacter = 100))
        clock.advance(seconds = 60)
        tracker.start(currentCharacter = 150)
        clock.advance(seconds = 10)
        tracker.update(currentCharacter = 160)

        assertEquals(0, tracker.state.session.charactersRead)
        assertEquals(0.0, tracker.state.session.readingTime, 0.0)

        tracker.setModalPaused(paused = false, currentCharacter = 160)
        clock.advance(seconds = 5)
        tracker.update(currentCharacter = 170)

        assertEquals(10, tracker.state.session.charactersRead)
        assertEquals(5.0, tracker.state.session.readingTime, 0.0)
    }

    @Test
    fun newTrackerDoesNotTrackOrPersistUntilStarted() {
        val tracker = ReaderStatisticsTracker(title = "Book", initialStatistics = emptyList())

        tracker.update(currentCharacter = 100)

        assertFalse(tracker.state.isTracking)
        assertEquals(0, tracker.state.session.charactersRead)
        assertNull(tracker.statisticsForPersistenceOrNull())
    }

    @Test
    fun pageTurnAutostartStartsFromPreTurnDisplayedCharacter() {
        val clock = FakeStatisticsClock()
        val tracker = ReaderStatisticsTracker(title = "Book", initialStatistics = emptyList(), clock = clock)

        tracker.startForPageTurnIfNeeded(currentCharacter = 100)
        clock.advance(seconds = 10)
        tracker.update(currentCharacter = 140)

        assertTrue(tracker.state.isTracking)
        assertEquals(40, tracker.state.session.charactersRead)
    }

    @Test
    fun sasayakiBackwardRestoreReanchorsWithoutCountingTargetChapter() {
        val clock = FakeStatisticsClock()
        val tracker = ReaderStatisticsTracker(title = "Book", initialStatistics = emptyList(), clock = clock)

        tracker.start(currentCharacter = 2_000)
        clock.advance(seconds = 1)
        tracker.update(currentCharacter = 2_000)
        tracker.resetBaseline(currentCharacter = 900)
        tracker.resetBaseline(currentCharacter = 920)
        clock.advance(seconds = 1)
        tracker.update(currentCharacter = 930)

        assertEquals(10, tracker.state.session.charactersRead)
    }

    private class FakeStatisticsClock(
        var millis: Long = 1_778_623_200_000,
    ) : ReaderStatisticsClock {
        override fun currentTimeMillis(): Long = millis

        fun advance(seconds: Long) {
            millis += seconds * 1_000
        }
    }

    private class FakeStatisticsDateProvider(
        var date: LocalDate,
    ) : StatisticsDateProvider {
        override fun currentDate(resetMinutes: Int): LocalDate = date
    }
}
