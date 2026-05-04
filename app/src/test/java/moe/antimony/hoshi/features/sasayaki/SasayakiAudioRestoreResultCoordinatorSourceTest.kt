package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SasayakiAudioRestoreResultCoordinatorSourceTest {
    @Test
    fun restoreResultCoordinatorOwnsFailureAndSuccessSequencing() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiAudioRestoreResultCoordinator.kt").readText()
        val failure = source.substringAfter("fun handleFailure(")
            .substringBefore("fun handleSuccess(")
        val success = source.substringAfter("fun handleSuccess(")

        assertTrue(source.contains("private val mediaSessionHandle: SasayakiMediaSessionHandleCoordinator"))
        assertTrue(source.contains("private val playbackState: SasayakiPlaybackStateCoordinator"))
        assertTrue(source.contains("private val audioAvailability: SasayakiAudioAvailabilityState"))
        assertTrue(failure.contains("audioAvailability.markRestoreFailed(error)"))
        assertTrue(success.contains("mediaSessionHandle.replace(result.mediaSession)"))
        assertTrue(success.contains("playbackState.updateDuration(result.durationMs)"))
        assertTrue(success.contains("audioAvailability.markRestoreSucceeded()"))
        assertTrue(success.contains("updateCue(currentTime)"))
        assertTrue(success.contains("updateMediaSession()"))
        assertTrue(success.indexOf("mediaSessionHandle.replace(result.mediaSession)") < success.indexOf("playbackState.updateDuration(result.durationMs)"))
        assertTrue(success.indexOf("playbackState.updateDuration(result.durationMs)") < success.indexOf("audioAvailability.markRestoreSucceeded()"))
        assertTrue(success.indexOf("audioAvailability.markRestoreSucceeded()") < success.indexOf("updateCue(currentTime)"))
        assertTrue(success.indexOf("updateCue(currentTime)") < success.indexOf("updateMediaSession()"))
        assertFalse(source.contains("mutableStateOf"))
    }
}
