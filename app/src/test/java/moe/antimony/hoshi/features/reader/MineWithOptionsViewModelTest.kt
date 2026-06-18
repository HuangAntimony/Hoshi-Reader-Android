package moe.antimony.hoshi.features.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import moe.antimony.hoshi.features.anki.ExampleSentence
import moe.antimony.hoshi.features.anki.ExampleSentenceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class MineWithOptionsViewModelTest {
    private fun scope() = CoroutineScope(Dispatchers.Unconfined)

    private class FakeSource(private val result: List<ExampleSentence>) : ExampleSentenceSource {
        var lastTerm: String? = null
        override suspend fun candidates(term: String, limit: Int): List<ExampleSentence> {
            lastTerm = term
            return result
        }
    }

    @Test
    fun startLoadsCandidatesAndClearsLoading() {
        val source = FakeSource(listOf(ExampleSentence("例文1"), ExampleSentence("例文2")))
        val vm = MineWithOptionsViewModel(source, scope())

        vm.start("勉強")

        assertEquals("勉強", source.lastTerm)
        assertEquals(
            listOf(ExampleSentence("例文1"), ExampleSentence("例文2")),
            vm.uiState.value.candidates,
        )
        assertFalse(vm.uiState.value.loading)
        assertEquals("勉強", vm.uiState.value.term)
    }

    @Test
    fun emptyResultYieldsNoCandidates() {
        val vm = MineWithOptionsViewModel(FakeSource(emptyList()), scope())
        vm.start("勉強")
        assertTrue(vm.uiState.value.candidates.isEmpty())
    }

    @Test
    fun startIsIdempotentForSameTerm() {
        var calls = 0
        val source = object : ExampleSentenceSource {
            override suspend fun candidates(term: String, limit: Int): List<ExampleSentence> {
                calls++
                return listOf(ExampleSentence("例文"))
            }
        }
        val vm = MineWithOptionsViewModel(source, scope())
        vm.start("勉強")
        vm.start("勉強")
        assertEquals(1, calls)
    }

    @Test
    fun loadingIsTrueWhileSourceSuspends() {
        val gate = CompletableDeferred<Unit>()
        val source = object : ExampleSentenceSource {
            override suspend fun candidates(term: String, limit: Int): List<ExampleSentence> {
                gate.await()
                return listOf(ExampleSentence("例文"))
            }
        }
        val vm = MineWithOptionsViewModel(source, scope())
        vm.start("勉強")
        assertTrue(vm.uiState.value.loading)
        gate.complete(Unit)
        assertFalse(vm.uiState.value.loading)
    }
}
