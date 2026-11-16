package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SasayakiDeferredPlaybackCommandTest {
    @Test
    fun commandWithoutPreparedEngineDefersUntilPlaybackEnvironmentIsReady() {
        val gate = SasayakiDeferredPlaybackCommand()
        val events = mutableListOf<String>()
        var ready: (() -> Unit)? = null

        val ranImmediately = gate.run(
            hasPreparedEngine = false,
            requestPlaybackEnvironment = { onReady ->
                events += "prepare"
                ready = onReady
            },
            runPreparedCommand = {
                events += "start"
                true
            },
        )

        assertFalse(ranImmediately)
        assertEquals(listOf("prepare"), events)

        ready?.invoke()

        assertEquals(listOf("prepare", "start"), events)
    }

    @Test
    fun duplicateCommandWhilePrepareIsPendingDoesNotRequestEnvironmentTwice() {
        val gate = SasayakiDeferredPlaybackCommand()
        val events = mutableListOf<String>()
        var ready: (() -> Unit)? = null

        repeat(2) {
            gate.run(
                hasPreparedEngine = false,
                requestPlaybackEnvironment = { onReady ->
                    events += "prepare"
                    ready = onReady
                },
                runPreparedCommand = {
                    events += "start"
                    true
                },
            )
        }

        ready?.invoke()

        assertEquals(listOf("prepare", "start"), events)
    }

    @Test
    fun cancelDropsPendingCommandWhenOldControllerIsReleased() {
        val gate = SasayakiDeferredPlaybackCommand()
        val events = mutableListOf<String>()
        var ready: (() -> Unit)? = null

        gate.run(
            hasPreparedEngine = false,
            requestPlaybackEnvironment = { onReady ->
                events += "prepare"
                ready = onReady
            },
            runPreparedCommand = {
                events += "start"
                true
            },
        )

        gate.cancel()
        ready?.invoke()

        assertEquals(listOf("prepare"), events)
    }

    @Test
    fun canceledReadinessCallbackCannotConsumeTheNextRequest() {
        val gate = SasayakiDeferredPlaybackCommand()
        val callbacks = mutableListOf<() -> Unit>()
        val played = mutableListOf<String>()

        gate.run(false, callbacks::add) { played.add("old") }
        gate.cancel()
        gate.run(false, callbacks::add) { played.add("new") }

        callbacks.first().invoke()
        assertTrue(played.isEmpty())
        callbacks.last().invoke()
        assertEquals(listOf("new"), played)
    }

    @Test
    fun preparedEngineRunsCommandImmediately() {
        val gate = SasayakiDeferredPlaybackCommand()
        val events = mutableListOf<String>()

        val ran = gate.run(
            hasPreparedEngine = true,
            requestPlaybackEnvironment = { events += "prepare" },
            runPreparedCommand = {
                events += "start"
                true
            },
        )

        assertTrue(ran)
        assertEquals(listOf("start"), events)
    }
}
