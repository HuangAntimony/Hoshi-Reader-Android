package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SasayakiAudioAvailabilityStateTest {
    @Test
    fun restoreSuccessMarksAudioAvailableAndClearsError() {
        val state = SasayakiAudioAvailabilityState()
        state.markRestoreFailed(IllegalStateException("missing"))

        state.markRestoreSucceeded()

        assertTrue(state.hasAudio)
        assertNull(state.errorMessage)
    }

    @Test
    fun restoreFailureMarksAudioUnavailableAndKeepsReadableMessage() {
        val state = SasayakiAudioAvailabilityState()
        state.markRestoreSucceeded()

        state.markRestoreFailed(IllegalStateException("bad audio"))

        assertFalse(state.hasAudio)
        assertEquals("bad audio", state.errorMessage)
    }

    @Test
    fun restoreFailureUsesFallbackMessageWhenErrorHasNoMessage() {
        val state = SasayakiAudioAvailabilityState()

        state.markRestoreFailed(object : Throwable() {})

        assertFalse(state.hasAudio)
        assertEquals("Unable to load audiobook.", state.errorMessage)
    }

    @Test
    fun clearAudioClearsErrorButTeardownOnlyMarksAudioUnavailable() {
        val state = SasayakiAudioAvailabilityState()
        state.markRestoreFailed(IllegalStateException("still visible"))

        state.markAudioUnavailable()

        assertFalse(state.hasAudio)
        assertEquals("still visible", state.errorMessage)

        state.markAudioCleared()

        assertFalse(state.hasAudio)
        assertNull(state.errorMessage)
    }
}
