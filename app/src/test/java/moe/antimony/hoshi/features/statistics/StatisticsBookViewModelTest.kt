package moe.antimony.hoshi.features.statistics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.antimony.hoshi.epub.ReadingStatistics
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsBookViewModelTest {
    @Test
    fun editRoundsMinutesAndSaveFailureKeepsDraft() = runTest {
        val repository = StatisticsRepositoryFake().apply { storedBook = book() }
        val model = StatisticsBookViewModel(repository, backgroundScope)
        model.load("book")
        runCurrent()
        model.edit("2026-09-17")
        assertEquals("1", model.uiState.value.draft?.hours)
        assertEquals("2", model.uiState.value.draft?.minutes)
        model.changeDraft { it.copy(characters = "1500") }
        repository.failWrites = true
        model.saveDay()
        runCurrent()
        assertEquals("1500", model.uiState.value.draft?.characters)
        assertNotNull(model.uiState.value.error)
        repository.failWrites = false
        model.saveDay()
        runCurrent()
        assertNull(model.uiState.value.draft)
        assertEquals(1500, model.uiState.value.book!!.statistics.single().charactersRead)
        assertEquals(3720.0, model.uiState.value.book!!.statistics.single().readingTime, 0.0)
    }

    @Test
    fun deletingFromAnInvalidDraftKeepsInputOnFailureAndClosesItOnSuccess() = runTest {
        val repository = StatisticsRepositoryFake().apply { storedBook = book() }
        val model = StatisticsBookViewModel(repository, backgroundScope)
        model.load("book")
        runCurrent()
        model.edit("2026-09-17")
        model.changeDraft { it.copy(characters = "") }
        repository.failWrites = true
        model.deleteDay("2026-09-17")
        runCurrent()
        assertEquals("", model.uiState.value.draft?.characters)
        assertEquals(1, model.uiState.value.book!!.statistics.size)
        assertNotNull(model.uiState.value.error)
        repository.failWrites = false
        model.deleteDay("2026-09-17")
        runCurrent()
        assertNull(model.uiState.value.draft)
        assertTrue(model.uiState.value.book!!.statistics.isEmpty())
        assertFalse(model.uiState.value.closeRequested)
    }

    @Test
    fun deletingLastArchivedDayReturnsToDashboard() = runTest {
        val repository = StatisticsRepositoryFake().apply { storedBook = book().copy(isArchived = true) }
        val model = StatisticsBookViewModel(repository, backgroundScope)
        model.load("book")
        runCurrent()
        model.edit("2026-09-17")
        model.deleteDay("2026-09-17")
        runCurrent()
        assertNull(model.uiState.value.draft)
        assertTrue(model.uiState.value.closeRequested)
    }

    @Test
    fun staleLoadCannotOverwriteASavedEditAndCancelDoesNotWrite() = runTest {
        val staleLoad = CompletableDeferred<StatisticsBookRecords?>()
        val repository = object : StatisticsRepositoryFake() {
            var loads = 0
            override suspend fun loadBookStatistics(folder: String): StatisticsBookRecords? {
                loads++
                return if (loads == 2) withContext(NonCancellable) { staleLoad.await() } else storedBook
            }
        }.apply { storedBook = book() }
        val model = StatisticsBookViewModel(repository, backgroundScope)
        model.load("book")
        runCurrent()
        model.edit("2026-09-17")
        model.changeDraft { it.copy(characters = "777") }
        model.cancelEdit()
        assertEquals(1000, repository.storedBook!!.statistics.single().charactersRead)
        assertNull(model.uiState.value.draft)
        model.load("book")
        runCurrent()
        model.edit("2026-09-17")
        model.changeDraft { it.copy(characters = "2000") }
        model.saveDay()
        runCurrent()
        staleLoad.complete(book())
        runCurrent()
        assertEquals(2000, model.uiState.value.book!!.statistics.single().charactersRead)
    }

    @Test
    fun invalidAndOverflowingDraftsCannotBeSaved() {
        assertFalse(StatisticsDayDraft("2026-09-17", "-1", "1", "0").canSave)
        assertFalse(StatisticsDayDraft("2026-09-17", "100", "1", "60").canSave)
        assertFalse(StatisticsDayDraft("2026-09-17", "100", "2147483647", "0").canSave)
        assertTrue(StatisticsDayDraft("2026-09-17", "0", "0", "0").canSave)
    }

    private fun book() = StatisticsBookRecords("book", "Book", false, listOf(
        ReadingStatistics("Book", "2026-09-17", charactersRead = 1000, readingTime = 3690.0),
    ))
}
