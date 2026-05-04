package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SasayakiPlaybackTeardownCoordinatorSourceTest {
    @Test
    fun teardownCoordinatorOwnsPlaybackReleaseAndOptionalCueClearSequencing() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlaybackTeardownCoordinator.kt").readText()
        val teardown = source.substringAfter("fun teardown(")

        assertTrue(source.contains("private val playbackLifecycle: SasayakiPlaybackLifecycleController"))
        assertTrue(source.contains("private val mediaSessionHandle: SasayakiMediaSessionHandleCoordinator"))
        assertTrue(source.contains("private val audioAvailability: SasayakiAudioAvailabilityState"))
        assertTrue(source.contains("private val cueDisplay: SasayakiCueDisplayCoordinator"))
        assertTrue(teardown.contains("pausePlayback()"))
        assertTrue(teardown.contains("playbackLifecycle.releaseEngine()"))
        assertTrue(teardown.contains("mediaSessionHandle.releaseAndClear()"))
        assertTrue(teardown.contains("audioAvailability.markAudioUnavailable()"))
        assertTrue(teardown.contains("if (clearCue) applyCueDisplayAction(cueDisplay.clear())"))
        assertTrue(teardown.indexOf("pausePlayback()") < teardown.indexOf("playbackLifecycle.releaseEngine()"))
        assertTrue(teardown.indexOf("playbackLifecycle.releaseEngine()") < teardown.indexOf("mediaSessionHandle.releaseAndClear()"))
        assertTrue(teardown.indexOf("mediaSessionHandle.releaseAndClear()") < teardown.indexOf("audioAvailability.markAudioUnavailable()"))
        assertTrue(teardown.indexOf("audioAvailability.markAudioUnavailable()") < teardown.indexOf("if (clearCue) applyCueDisplayAction(cueDisplay.clear())"))
        assertFalse(source.contains("mutableStateOf"))
    }
}
