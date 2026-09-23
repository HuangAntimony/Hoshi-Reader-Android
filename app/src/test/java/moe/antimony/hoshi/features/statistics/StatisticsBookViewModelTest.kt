package moe.antimony.hoshi.features.statistics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.antimony.hoshi.epub.ReadingSession
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
        model.saveSession()
        runCurrent()
        assertEquals("1500", model.uiState.value.draft?.characters)
        assertNotNull(model.uiState.value.error)
        repository.failWrites = false
        model.saveSession()
        runCurrent()
        assertNull(model.uiState.value.draft)
        assertEquals(1500, model.uiState.value.book!!.sessions.values.single().value!!.charactersRead)
        assertEquals(3690.0, model.uiState.value.book!!.sessions.values.single().value!!.readingTime, 0.0)
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
        model.deleteSession("2026-09-17")
        runCurrent()
        assertEquals("", model.uiState.value.draft?.characters)
        assertEquals(1, model.uiState.value.book!!.sessions.size)
        assertNotNull(model.uiState.value.error)
        repository.failWrites = false
        model.deleteSession("2026-09-17")
        runCurrent()
        assertNull(model.uiState.value.draft)
        assertTrue(model.uiState.value.book!!.sessions.values.all { it.value == null })
        assertFalse(model.uiState.value.closeRequested)
    }

    @Test
    fun deletingLastArchivedDayReturnsToDashboard() = runTest {
        val repository = StatisticsRepositoryFake().apply { storedBook = book().copy(isArchived = true) }
        val model = StatisticsBookViewModel(repository, backgroundScope)
        model.load("book")
        runCurrent()
        model.edit("2026-09-17")
        model.deleteSession("2026-09-17")
        runCurrent()
        assertNull(model.uiState.value.draft)
        assertFalse(model.uiState.value.closeRequested)
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
        assertEquals(1000, repository.storedBook!!.sessions.values.single().value!!.charactersRead)
        assertNull(model.uiState.value.draft)
        model.load("book")
        runCurrent()
        model.edit("2026-09-17")
        model.changeDraft { it.copy(characters = "2000") }
        model.saveSession()
        runCurrent()
        staleLoad.complete(book())
        runCurrent()
        assertEquals(2000, model.uiState.value.book!!.sessions.values.single().value!!.charactersRead)
    }

    @Test
    fun invalidAndOverflowingDraftsCannotBeSaved() {
        assertFalse(StatisticsSessionDraft("2026-09-17", 0, "-1", "1", "0").canSave)
        assertFalse(StatisticsSessionDraft("2026-09-17", 0, "100", "1", "60").canSave)
        assertFalse(StatisticsSessionDraft("2026-09-17", 0, "100", "2147483647", "0").canSave)
        assertTrue(StatisticsSessionDraft("2026-09-17", 0, "0", "0", "0").canSave)
    }

    private fun book() = StatisticsBookRecords("book", "Book", false, mapOf(
        "2026-09-17" to moe.antimony.hoshi.features.sync.Timestamped<ReadingSession?>(1, ReadingSession(0, 3690000, charactersRead = 1000, readingTime = 3690.0)),
    ))
}
