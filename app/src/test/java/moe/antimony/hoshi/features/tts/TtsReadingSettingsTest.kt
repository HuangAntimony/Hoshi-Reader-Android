package moe.antimony.hoshi.features.tts

import moe.antimony.hoshi.features.sasayaki.SasayakiReaderSkipButtonAction
import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import org.junit.Assert.*
import org.junit.Test

class TtsReadingSettingsTest {
    @Test fun independentReadingEditsPreserveAnEarlierChange() {
        val observed = SasayakiSettings(autoScroll = true, autoPause = true)
        val first: (SasayakiSettings) -> SasayakiSettings = { it.copy(autoScroll = false) }
        val second: (SasayakiSettings) -> SasayakiSettings = { it.copy(autoPause = false) }
        val saved = observed.withTtsReadingSettings(first).withTtsReadingSettings(second)
        assertFalse(saved.autoScroll)
        assertFalse(saved.autoPause)
    }

    @Test fun editingSharedReadingBehaviorPreservesAudiobookPreferences() {
        val current = SasayakiSettings(enabled = false, syncEnabled = true, showReaderToggle = false,
            readerSkipButtonAction = SasayakiReaderSkipButtonAction.Seconds15, copyAudiobookToPrivateStorage = true)
        val edited = SasayakiSettings(autoScroll = false, autoPause = false, imageHoldSeconds = 2.5f,
            showReaderBottomPlaybackControls = false, reverseVerticalReaderSkipButtons = true,
            showReaderRestartSentenceButton = true,
            lightTextColor = 1, lightBackgroundColor = 2, darkTextColor = 3, darkBackgroundColor = 4)
        assertEquals(edited.copy(enabled = current.enabled, syncEnabled = current.syncEnabled,
            showReaderToggle = current.showReaderToggle, readerSkipButtonAction = current.readerSkipButtonAction,
            copyAudiobookToPrivateStorage = current.copyAudiobookToPrivateStorage), current.withTtsReadingSettings { edited })
    }
}
