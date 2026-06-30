package moe.antimony.hoshi.features.statistics

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Test

class StatisticsViewReloadTest {
    @Test
    fun reloadsOnceForEachResumeLifecycleEventOnly() {
        var reloadCount = 0
        val reloader = StatisticsLifecycleReloader { reloadCount += 1 }

        reloader.onStateChanged(FakeLifecycleOwner, Lifecycle.Event.ON_CREATE)
        reloader.onStateChanged(FakeLifecycleOwner, Lifecycle.Event.ON_START)
        assertEquals(0, reloadCount)

        reloader.onStateChanged(FakeLifecycleOwner, Lifecycle.Event.ON_RESUME)
        assertEquals(1, reloadCount)

        reloader.onStateChanged(FakeLifecycleOwner, Lifecycle.Event.ON_PAUSE)
        reloader.onStateChanged(FakeLifecycleOwner, Lifecycle.Event.ON_RESUME)
        assertEquals(2, reloadCount)
    }

    private object FakeLifecycleOwner : LifecycleOwner {
        override val lifecycle: Lifecycle
            get() = error("StatisticsLifecycleReloader does not read the owner lifecycle.")
    }
}
