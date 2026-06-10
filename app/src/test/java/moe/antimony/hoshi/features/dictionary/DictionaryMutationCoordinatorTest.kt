package moe.antimony.hoshi.features.dictionary

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.dictionary.DictionaryUpdateProgress
import moe.antimony.hoshi.dictionary.DictionaryUpdateStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DictionaryMutationCoordinatorTest {
    @Test
    fun runExclusivePublishesOperationProgressAndIdleState() = runBlocking {
        val coordinator = DictionaryMutationCoordinator()
        val progress = DictionaryUpdateProgress(DictionaryUpdateStage.Checking, "JMdict")

        val result = coordinator.runExclusive(DictionaryMutationOperation.Import) {
            assertEquals(DictionaryMutationOperation.Import, coordinator.state.value.operation)
            assertNull(coordinator.state.value.progress)

            report(progress)

            assertEquals(progress, coordinator.state.value.progress)
            "finished"
        }

        assertEquals("finished", result)
        assertNull(coordinator.state.value.operation)
        assertNull(coordinator.state.value.progress)
    }

    @Test
    fun changedSessionIncrementsCompletedVersionOnce() = runBlocking {
        val coordinator = DictionaryMutationCoordinator()

        coordinator.runExclusive(DictionaryMutationOperation.Edit) {
            markDictionariesChanged()
            markDictionariesChanged()
        }

        assertEquals(1L, coordinator.state.value.completedChangeVersion)
    }

    @Test
    fun secondMutationDoesNotRunWhileBusy() = runBlocking {
        val coordinator = DictionaryMutationCoordinator()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var secondRan = false

        val first = async {
            coordinator.runExclusive(DictionaryMutationOperation.Import) {
                entered.complete(Unit)
                release.await()
                "first"
            }
        }
        entered.await()

        val second = coordinator.runExclusive(DictionaryMutationOperation.Edit) {
            secondRan = true
            "second"
        }

        release.complete(Unit)

        assertNull(second)
        assertFalse(secondRan)
        assertEquals("first", first.await())
    }
}
