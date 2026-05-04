package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertEquals
import org.junit.Test

class SasayakiMediaSessionHandleCoordinatorTest {
    @Test
    fun delegatesOnlyToCurrentHandleAndClearsItOnRelease() {
        val coordinator = SasayakiMediaSessionHandleCoordinator()
        val first = FakeMediaSessionHandle()
        val second = FakeMediaSessionHandle()

        coordinator.replace(first)
        coordinator.activate()
        coordinator.update(isPlaying = true, currentTimeMs = 1000, durationMs = 2000, rate = 1.25f)
        coordinator.replace(second)
        coordinator.releaseExisting()
        coordinator.releaseAndClear()
        coordinator.activate()

        assertEquals(
            listOf(
                "activate",
                "update:true:1000:2000:1.25",
            ),
            first.events,
        )
        assertEquals(listOf("release", "release"), second.events)
    }

    private class FakeMediaSessionHandle : SasayakiMediaSessionHandle {
        val events = mutableListOf<String>()

        override fun activate() {
            events += "activate"
        }

        override fun update(
            isPlaying: Boolean,
            currentTimeMs: Long,
            durationMs: Long,
            rate: Float,
        ) {
            events += "update:$isPlaying:$currentTimeMs:$durationMs:$rate"
        }

        override fun release() {
            events += "release"
        }
    }
}
