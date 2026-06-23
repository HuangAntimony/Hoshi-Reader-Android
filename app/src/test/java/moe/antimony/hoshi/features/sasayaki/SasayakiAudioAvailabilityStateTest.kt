package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText

class SasayakiAudioAvailabilityStateTest {
    @Test
    fun storedAudioSourceKeepsControlsAvailableBeforePlayerRestore() {
        val state = SasayakiAudioAvailabilityState(initialHasAudio = true)

        assertTrue(state.hasAudio)
        assertNull(state.errorMessage)
    }

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
        assertEquals(UiText.Literal("bad audio"), state.errorMessage)
    }

    @Test
    fun restoreFailureUsesFallbackMessageWhenErrorHasNoMessage() {
        val state = SasayakiAudioAvailabilityState()

        state.markRestoreFailed(object : Throwable() {})

        assertFalse(state.hasAudio)
        assertEquals(UiText.Resource(R.string.sasayaki_import_audiobook_failed), state.errorMessage)
    }

    @Test
    fun clearAudioClearsErrorButTeardownOnlyMarksAudioUnavailable() {
        val state = SasayakiAudioAvailabilityState()
        state.markRestoreFailed(IllegalStateException("still visible"))

        state.markAudioUnavailable()

        assertFalse(state.hasAudio)
        assertEquals(UiText.Literal("still visible"), state.errorMessage)

        state.markAudioCleared()

        assertFalse(state.hasAudio)
        assertNull(state.errorMessage)
    }
}
