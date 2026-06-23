package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SasayakiDeferredPlaybackStartTest {
    @Test
    fun startWithoutPreparedEngineDefersUntilPlaybackEnvironmentIsReady() {
        val gate = SasayakiDeferredPlaybackStart()
        val events = mutableListOf<String>()
        var ready: (() -> Unit)? = null

        val startedImmediately = gate.start(
            hasPreparedEngine = false,
            requestPlaybackEnvironment = { onReady ->
                events += "prepare"
                ready = onReady
            },
            startPreparedPlayback = {
                events += "start"
                true
            },
        )

        assertFalse(startedImmediately)
        assertEquals(listOf("prepare"), events)

        ready?.invoke()

        assertEquals(listOf("prepare", "start"), events)
    }

    @Test
    fun duplicateStartWhilePrepareIsPendingDoesNotRequestEnvironmentTwice() {
        val gate = SasayakiDeferredPlaybackStart()
        val events = mutableListOf<String>()
        var ready: (() -> Unit)? = null

        repeat(2) {
            gate.start(
                hasPreparedEngine = false,
                requestPlaybackEnvironment = { onReady ->
                    events += "prepare"
                    ready = onReady
                },
                startPreparedPlayback = {
                    events += "start"
                    true
                },
            )
        }

        ready?.invoke()

        assertEquals(listOf("prepare", "start"), events)
    }

    @Test
    fun cancelDropsPendingStartWhenOldControllerIsReleased() {
        val gate = SasayakiDeferredPlaybackStart()
        val events = mutableListOf<String>()
        var ready: (() -> Unit)? = null

        gate.start(
            hasPreparedEngine = false,
            requestPlaybackEnvironment = { onReady ->
                events += "prepare"
                ready = onReady
            },
            startPreparedPlayback = {
                events += "start"
                true
            },
        )

        gate.cancel()
        ready?.invoke()

        assertEquals(listOf("prepare"), events)
    }

    @Test
    fun preparedEngineStartsImmediately() {
        val gate = SasayakiDeferredPlaybackStart()
        val events = mutableListOf<String>()

        val started = gate.start(
            hasPreparedEngine = true,
            requestPlaybackEnvironment = { events += "prepare" },
            startPreparedPlayback = {
                events += "start"
                true
            },
        )

        assertTrue(started)
        assertEquals(listOf("start"), events)
    }
}
