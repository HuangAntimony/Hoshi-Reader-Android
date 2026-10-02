package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTopPlaybackControlTest {
    @Test
    fun ttsUsesTheSharedButtonEvenWhenSasayakiIsDisabled() {
        var toggled = false
        val tts = ReaderTopPlaybackControl(isPlaying = true) { toggled = true }
        val control = readerTopPlaybackControl(SasayakiSettings(enabled = false), tts, null)

        assertSame(tts, control)
        assertTrue(requireNotNull(control).isPlaying)
        control.onToggle()
        assertTrue(toggled)
    }

    @Test
    fun associatedAudiobookKeepsItsPlaybackCallbackAndEnablePreference() {
        var toggled = false
        val audiobook = ReaderTopPlaybackControl(isPlaying = false) { toggled = true }
        val control = readerTopPlaybackControl(SasayakiSettings(), null, audiobook)

        assertSame(audiobook, control)
        assertFalse(requireNotNull(control).isPlaying)
        control.onToggle()
        assertTrue(toggled)
        assertNull(readerTopPlaybackControl(SasayakiSettings(enabled = false), null, audiobook))
    }

    @Test
    fun hidingTheSharedButtonAppliesToBothSources() {
        val control = ReaderTopPlaybackControl(isPlaying = false) {}
        val settings = SasayakiSettings(showReaderToggle = false)

        assertNull(readerTopPlaybackControl(settings, control, null))
        assertNull(readerTopPlaybackControl(settings, null, control))
    }

    @Test
    fun unavailableSourcesDoNotExposeAnInactivePlaybackButton() {
        assertNull(readerTopPlaybackControl(SasayakiSettings(), null, null))
    }
}
