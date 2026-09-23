package moe.antimony.hoshi.features.sasayaki.transcription

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class SasayakiAudioDurationTest {
    @Test
    fun usesM4bContainerDurationWhenPlatformExtractorFindsNoAudioTrack() {
        var retrieverCalled = false

        val duration = resolveSasayakiAudioDuration(
            extractorDuration = { throw IOException("No audio track") },
            containerDuration = { 83.5 },
            metadataRetrieverDuration = {
                retrieverCalled = true
                90.0
            },
        )

        assertEquals(83.5, duration, 0.0)
        assertFalse(retrieverCalled)
    }

    @Test
    fun prefersPlatformAudioTrackDurationWhenAvailable() {
        var containerCalled = false
        var retrieverCalled = false

        val duration = resolveSasayakiAudioDuration(
            extractorDuration = { 12.0 },
            containerDuration = {
                containerCalled = true
                83.5
            },
            metadataRetrieverDuration = {
                retrieverCalled = true
                90.0
            },
        )

        assertEquals(12.0, duration, 0.0)
        assertFalse(containerCalled)
        assertFalse(retrieverCalled)
    }

    @Test
    fun ignoresInvalidDurationAndTriesLaterProbes() {
        val duration = resolveSasayakiAudioDuration(
            extractorDuration = { Double.NaN },
            containerDuration = { -1.0 },
            metadataRetrieverDuration = { 90.0 },
        )

        assertEquals(90.0, duration, 0.0)
    }

    @Test
    fun keepsExtractorFailureAsCauseWhenEveryProbeFails() {
        val error = assertThrows(IOException::class.java) {
            resolveSasayakiAudioDuration(
                extractorDuration = { throw IOException("No audio track") },
                containerDuration = { null },
                metadataRetrieverDuration = { null },
            )
        }

        assertEquals("Audio duration is unavailable", error.message)
        assertEquals("No audio track", error.cause?.message)
    }
}
