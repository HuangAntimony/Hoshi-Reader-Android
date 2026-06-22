package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import moe.antimony.hoshi.ui.UiText

class SasayakiAudioRestoreResultCoordinatorTest {
    @Test
    fun failureMarksAudioUnavailableWithErrorMessage() {
        val availability = SasayakiAudioAvailabilityState()
        availability.markRestoreSucceeded()
        val coordinator = SasayakiAudioRestoreResultCoordinator(
            playbackState = SasayakiPlaybackStateCoordinator(initialPosition = 0.0),
            audioAvailability = availability,
        )

        coordinator.handleFailure(IllegalStateException("decode failed"))

        assertFalse(availability.hasAudio)
        assertEquals(UiText.Literal("decode failed"), availability.errorMessage)
    }

    @Test
    fun successUpdatesDurationAndThenRefreshesCueAndSession() {
        val events = mutableListOf<String>()
        val playbackState = SasayakiPlaybackStateCoordinator(initialPosition = 0.0)
        val availability = SasayakiAudioAvailabilityState()
        val coordinator = SasayakiAudioRestoreResultCoordinator(
            playbackState = playbackState,
            audioAvailability = availability,
        )

        coordinator.handleSuccess(
            result = SasayakiAudioRestoreResult(
                durationMs = 12_500,
            ),
            currentTime = 3.25,
            updateCue = { events += "cue:$it" },
            updateMediaSession = {
                events += "session:${playbackState.duration}:${availability.hasAudio}"
            },
        )

        assertEquals(12.5, playbackState.duration, 0.0)
        assertTrue(availability.hasAudio)
        assertNull(availability.errorMessage)
        assertEquals(listOf("cue:3.25", "session:12.5:true"), events)
    }
}
