package moe.antimony.hoshi.features.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import moe.antimony.hoshi.features.anki.ExampleSentence
import moe.antimony.hoshi.features.anki.ExampleSentenceSource
import moe.antimony.hoshi.features.anki.ImageCandidate
import moe.antimony.hoshi.features.anki.ImageSearchSource
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

    private class FakeImageSource(private val result: List<ImageCandidate>) : ImageSearchSource {
        var lastTerm: String? = null
        override suspend fun candidates(term: String, limit: Int): List<ImageCandidate> {
            lastTerm = term
            return result
        }
    }

    private fun vm(
        sentences: ExampleSentenceSource,
        images: ImageSearchSource = FakeImageSource(emptyList()),
    ) = MineWithOptionsViewModel(sentences, images, scope())

    @Test
    fun startLoadsCandidatesAndClearsLoading() {
        val source = FakeSource(listOf(ExampleSentence("例文1"), ExampleSentence("例文2")))
        val vm = vm(source)

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
        val vm = vm(FakeSource(emptyList()))
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
        val vm = vm(source)
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
        val vm = vm(source)
        vm.start("勉強")
        assertTrue(vm.uiState.value.loading)
        gate.complete(Unit)
        assertFalse(vm.uiState.value.loading)
    }

    @Test
    fun startLoadsImagesInParallelWithSentences() {
        val images = FakeImageSource(listOf(ImageCandidate("t1", "f1"), ImageCandidate("t2", "f2")))
        val model = vm(FakeSource(listOf(ExampleSentence("例文"))), images)

        model.start("勉強")

        assertEquals("勉強", images.lastTerm)
        assertEquals(listOf("f1", "f2"), model.uiState.value.imageCandidates.map { it.fullUrl })
        assertFalse(model.uiState.value.imageLoading)
    }

    @Test
    fun emptyImageResultLeavesNoCandidatesButStillClearsImageLoading() {
        val model = vm(FakeSource(emptyList()), FakeImageSource(emptyList()))
        model.start("勉強")
        assertTrue(model.uiState.value.imageCandidates.isEmpty())
        assertFalse(model.uiState.value.imageLoading)
    }

    @Test
    fun imageLoadingIsTrueWhileImageSourceSuspends() {
        val gate = CompletableDeferred<Unit>()
        val images = object : ImageSearchSource {
            override suspend fun candidates(term: String, limit: Int): List<ImageCandidate> {
                gate.await()
                return listOf(ImageCandidate("t", "f"))
            }
        }
        val model = vm(FakeSource(emptyList()), images)
        model.start("勉強")
        assertTrue(model.uiState.value.imageLoading)
        gate.complete(Unit)
        assertFalse(model.uiState.value.imageLoading)
    }

    @Test
    fun supersededTermDoesNotClobberImageState() {
        val gateA = CompletableDeferred<Unit>()
        val images = object : ImageSearchSource {
            override suspend fun candidates(term: String, limit: Int): List<ImageCandidate> {
                if (term == "A") {
                    gateA.await()
                    return listOf(ImageCandidate("ta", "fa"))
                }
                return emptyList()
            }
        }
        val model = vm(FakeSource(emptyList()), images)
        model.start("A")
        model.start("B")          // supersedes A
        gateA.complete(Unit)      // A's slow image load finishes late
        assertEquals("B", model.uiState.value.term)
        assertTrue(model.uiState.value.imageCandidates.isEmpty())  // A's images ignored
    }

    @Test
    fun imageLoadingClearsWhenImageSourceThrows() {
        val images = object : ImageSearchSource {
            override suspend fun candidates(term: String, limit: Int): List<ImageCandidate> =
                throw RuntimeException("boom")
        }
        val model = vm(FakeSource(emptyList()), images)
        model.start("勉強")
        assertFalse(model.uiState.value.imageLoading)
        assertTrue(model.uiState.value.imageCandidates.isEmpty())
    }
}
