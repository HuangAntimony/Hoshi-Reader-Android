package moe.antimony.hoshi.features.reader

import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.ReadingSessions
import moe.antimony.hoshi.navigation.ReaderAutoSyncExportController
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderStatisticsPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun rolloverSavesTheCompletedDayBeforeTheNextBookmarkSave() = runBlocking {
        val repository = BookRepository(temporary.newFolder())
        val root = repository.createBookDirectory("book")
        var now = LocalDateTime.parse("2026-09-22T23:59:58")
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val clock = object : ReaderStatisticsClock {
            override fun currentTimeMillis() = now
        }
        val allowSave = CompletableDeferred<Unit>()
        val saves = ReaderAutoSyncExportController(this)
        val requests = mutableListOf<ReadingSessions>()
        lateinit var tracker: ReaderStatisticsTracker
        tracker = ReaderStatisticsTracker(emptyMap(), clock = clock, onSaveSessions = { sessions ->
            requests += sessions
            saves.launchSave {
                allowSave.await()
                repository.saveTrackedSessions(root, sessions)
                tracker.applySessions(repository.loadSessions(root))
            }
        })
        tracker.start(0)
        now += 1_000
        tracker.update(10)
        val previousDay = tracker.statisticsForPersistenceOrNull()!!
        now += 2_000
        tracker.update(20)
        now += 1_000
        tracker.update(25)

        // Storage is still delayed; both days must remain visible and retryable.
        try {
            assertEquals(25, tracker.state.allTime.charactersRead)
            assertEquals(2, tracker.statisticsForPersistenceOrNull()!!.size)
        } finally {
            allowSave.complete(Unit)
        }
        saves.flushSaves()

        assertEquals(1, requests.size)
        val saved = repository.loadSessions(root)
        assertEquals(previousDay.keys, saved.keys)
        assertEquals(previousDay.values.single().value, saved.values.single().value)
        assertEquals(25, tracker.state.allTime.charactersRead)
        assertEquals(15, tracker.state.today.charactersRead)
        val currentDay = tracker.statisticsForPersistenceOrNull()!!
        assertEquals(1, currentDay.size)
        repository.saveTrackedSessions(root, currentDay)
        tracker.applySessions(repository.loadSessions(root))

        assertNull(tracker.statisticsForPersistenceOrNull())
        assertEquals(25, tracker.state.allTime.charactersRead)
        val days = repository.statisticsStore.loadBook("book")!!.days
        assertEquals(listOf("2026-09-22", "2026-09-23"), days.map { it.date.toString() })
        assertEquals(listOf(10, 15), days.map { it.total.charactersRead })
        val exported = repository.loadStatistics(root)
        assertEquals(listOf("2026-09-22", "2026-09-23"), exported.map { it.dateKey })
        assertEquals(listOf(1.0, 3.0), exported.map { it.readingTime })
    }
}
