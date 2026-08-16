package moe.antimony.hoshi.features.reader

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderHardwareKeyNavigationTest {
    @Test
    fun pageDownAndPageUpAlwaysMapToReaderNavigation() {
        val settings = ReaderSettings(volumeKeysTurnPages = false, reverseVolumeKeyDirection = true)

        assertEquals(
            ReaderNavigationDirection.Forward,
            readerNavigationDirectionForKeyEvent(
                keyCode = KeyEvent.KEYCODE_PAGE_DOWN,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                settings = settings,
            ),
        )
        assertEquals(
            ReaderNavigationDirection.Backward,
            readerNavigationDirectionForKeyEvent(
                keyCode = KeyEvent.KEYCODE_PAGE_UP,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                settings = settings,
            ),
        )
    }

    @Test
    fun volumeKeysAreIgnoredUntilEnabled() {
        val settings = ReaderSettings(volumeKeysTurnPages = false)

        assertNull(
            readerNavigationDirectionForKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                settings = settings,
            ),
        )
        assertNull(
            readerNavigationDirectionForKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_UP,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                settings = settings,
            ),
        )
    }

    @Test
    fun sasayakiSeekVolumeKeysAreIgnoredUntilEnabled() {
        val settings = ReaderSettings(
            volumeKeysTurnPages = false,
            volumeKeysSeekSasayaki = false,
        )

        assertNull(
            readerHardwareKeyActionForKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                settings = settings,
                sasayakiEnabled = true,
                hasSasayakiAudio = true,
            ),
        )
        assertNull(
            readerHardwareKeyActionForKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_UP,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                settings = settings,
                sasayakiEnabled = true,
                hasSasayakiAudio = true,
            ),
        )
    }

    @Test
    fun sasayakiSeekVolumeKeysRequireEnabledSasayakiAndLoadedAudio() {
        val settings = ReaderSettings(volumeKeysSeekSasayaki = true)

        assertNull(
            readerHardwareKeyActionForKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_UP,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                settings = settings,
                sasayakiEnabled = false,
                hasSasayakiAudio = true,
            ),
        )
        assertNull(
            readerHardwareKeyActionForKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_UP,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                settings = settings,
                sasayakiEnabled = true,
                hasSasayakiAudio = false,
            ),
        )
    }

    @Test
    fun sasayakiSeekVolumeKeysUseDefaultDirection() {
        val settings = ReaderSettings(
            volumeKeysSeekSasayaki = true,
            reverseVolumeKeyDirection = false,
        )

        assertEquals(
            ReaderHardwareKeyAction.SasayakiSeekBackward,
            readerHardwareKeyActionForKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_UP,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                settings = settings,
                sasayakiEnabled = true,
                hasSasayakiAudio = true,
            ),
        )
        assertEquals(
            ReaderHardwareKeyAction.SasayakiSeekForward,
            readerHardwareKeyActionForKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                settings = settings,
                sasayakiEnabled = true,
                hasSasayakiAudio = true,
            ),
        )
    }

    @Test
    fun sasayakiSeekVolumeKeysCanBeReversed() {
        val settings = ReaderSettings(
            volumeKeysSeekSasayaki = true,
            reverseVolumeKeyDirection = true,
        )

        assertEquals(
            ReaderHardwareKeyAction.SasayakiSeekForward,
            readerHardwareKeyActionForKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_UP,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                settings = settings,
                sasayakiEnabled = true,
                hasSasayakiAudio = true,
            ),
        )
        assertEquals(
            ReaderHardwareKeyAction.SasayakiSeekBackward,
            readerHardwareKeyActionForKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                settings = settings,
                sasayakiEnabled = true,
                hasSasayakiAudio = true,
            ),
        )
    }

    @Test
    fun sasayakiSeekVolumeKeysTakePriorityOverVolumePageTurnsWhenAudioIsLoaded() {
        val settings = ReaderSettings(
            volumeKeysTurnPages = true,
            volumeKeysSeekSasayaki = true,
        )

        assertEquals(
            ReaderHardwareKeyAction.SasayakiSeekBackward,
            readerHardwareKeyActionForKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_UP,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                settings = settings,
                sasayakiEnabled = true,
                hasSasayakiAudio = true,
            ),
        )
    }

    @Test
    fun popupTermNavigationTakesPriorityOverSasayakiAndPageTurns() {
        val settings = ReaderSettings(
            volumeKeysTurnPages = true,
            volumeKeysNavigatePopupTerms = true,
            volumeKeysSeekSasayaki = true,
        )

        assertEquals(
            ReaderHardwareKeyAction.PopupTermNavigation(PopupTermNavigationDirection.Previous),
            readerHardwareKeyActionForKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_UP,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                settings = settings,
                sasayakiEnabled = true,
                hasSasayakiAudio = true,
                hasLookupPopup = true,
            ),
        )
        assertEquals(
            ReaderHardwareKeyAction.PopupTermNavigation(PopupTermNavigationDirection.Next),
            readerHardwareKeyActionForKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                settings = settings,
                sasayakiEnabled = true,
                hasSasayakiAudio = true,
                hasLookupPopup = true,
            ),
        )
    }

    @Test
    fun popupTermNavigationUsesReverseVolumeDirection() {
        val settings = ReaderSettings(
            volumeKeysNavigatePopupTerms = true,
            reverseVolumeKeyDirection = true,
        )

        assertEquals(
            ReaderHardwareKeyAction.PopupTermNavigation(PopupTermNavigationDirection.Next),
            readerHardwareKeyActionForKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_UP,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                settings = settings,
                sasayakiEnabled = false,
                hasSasayakiAudio = false,
                hasLookupPopup = true,
            ),
        )
        assertEquals(
            ReaderHardwareKeyAction.PopupTermNavigation(PopupTermNavigationDirection.Previous),
            readerHardwareKeyActionForKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                settings = settings,
                sasayakiEnabled = false,
                hasSasayakiAudio = false,
                hasLookupPopup = true,
            ),
        )
    }

    @Test
    fun popupTermNavigationFallsBackWhenDisabledOrNoPopupExists() {
        val settings = ReaderSettings(
            volumeKeysTurnPages = true,
            volumeKeysNavigatePopupTerms = true,
        )

        assertEquals(
            ReaderHardwareKeyAction.ReaderNavigation(ReaderNavigationDirection.Forward),
            readerHardwareKeyActionForKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                settings = settings,
                sasayakiEnabled = false,
                hasSasayakiAudio = false,
                hasLookupPopup = false,
            ),
        )
        assertEquals(
            ReaderHardwareKeyAction.ReaderNavigation(ReaderNavigationDirection.Forward),
            readerHardwareKeyActionForKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                settings = settings.copy(volumeKeysNavigatePopupTerms = false),
                sasayakiEnabled = false,
                hasSasayakiAudio = false,
                hasLookupPopup = true,
            ),
        )
    }

    @Test
    fun popupTermNavigationRepeatsAndConsumesKeyUpWithoutAction() {
        val settings = ReaderSettings(volumeKeysNavigatePopupTerms = true)

        assertEquals(
            ReaderHardwareKeyAction.PopupTermNavigation(PopupTermNavigationDirection.Next),
            readerHardwareKeyActionForKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 3,
                settings = settings,
                sasayakiEnabled = false,
                hasSasayakiAudio = false,
                hasLookupPopup = true,
            ),
        )
        val keyUp = readerHardwareKeyEventForKeyEvent(
            keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
            action = KeyEvent.ACTION_UP,
            repeatCount = 0,
            settings = settings,
            sasayakiEnabled = false,
            hasSasayakiAudio = false,
            hasLookupPopup = true,
        )
        assertTrue(keyUp.consumed)
        assertNull(keyUp.action)
    }

    @Test
    fun sasayakiSeekFallsBackToVolumePageTurnsWhenAudioIsNotLoaded() {
        val settings = ReaderSettings(
            volumeKeysTurnPages = true,
            volumeKeysSeekSasayaki = true,
        )

        assertEquals(
            ReaderHardwareKeyAction.ReaderNavigation(ReaderNavigationDirection.Backward),
            readerHardwareKeyActionForKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_UP,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                settings = settings,
                sasayakiEnabled = true,
                hasSasayakiAudio = false,
            ),
        )
    }

    @Test
    fun enabledVolumeKeysUseDefaultReaderDirection() {
        val settings = ReaderSettings(volumeKeysTurnPages = true, reverseVolumeKeyDirection = false)

        assertEquals(
            ReaderNavigationDirection.Forward,
            readerNavigationDirectionForKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                settings = settings,
            ),
        )
        assertEquals(
            ReaderNavigationDirection.Backward,
            readerNavigationDirectionForKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_UP,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                settings = settings,
            ),
        )
    }

    @Test
    fun enabledVolumeKeysCanBeReversedWithoutChangingPageKeys() {
        val settings = ReaderSettings(volumeKeysTurnPages = true, reverseVolumeKeyDirection = true)

        assertEquals(
            ReaderNavigationDirection.Backward,
            readerNavigationDirectionForKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                settings = settings,
            ),
        )
        assertEquals(
            ReaderNavigationDirection.Forward,
            readerNavigationDirectionForKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_UP,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                settings = settings,
            ),
        )
        assertEquals(
            ReaderNavigationDirection.Forward,
            readerNavigationDirectionForKeyEvent(
                keyCode = KeyEvent.KEYCODE_PAGE_DOWN,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                settings = settings,
            ),
        )
    }

    @Test
    fun pageKeysIgnoreKeyUpAndRepeatedKeyDownEvents() {
        val settings = ReaderSettings(volumeKeysTurnPages = true, volumeKeysSeekSasayaki = true)

        assertNull(
            readerNavigationDirectionForKeyEvent(
                keyCode = KeyEvent.KEYCODE_PAGE_DOWN,
                action = KeyEvent.ACTION_UP,
                repeatCount = 0,
                settings = settings,
            ),
        )
        assertNull(
            readerNavigationDirectionForKeyEvent(
                keyCode = KeyEvent.KEYCODE_PAGE_DOWN,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 1,
                settings = settings,
            ),
        )
    }

    @Test
    fun enabledVolumePageTurnKeysRepeatReaderNavigation() {
        val settings = ReaderSettings(volumeKeysTurnPages = true)

        assertEquals(
            ReaderHardwareKeyAction.ReaderNavigation(ReaderNavigationDirection.Forward),
            readerHardwareKeyActionForKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 3,
                settings = settings,
                sasayakiEnabled = false,
                hasSasayakiAudio = false,
            ),
        )
        assertEquals(
            ReaderHardwareKeyAction.ReaderNavigation(ReaderNavigationDirection.Backward),
            readerHardwareKeyActionForKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_UP,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 2,
                settings = settings,
                sasayakiEnabled = false,
                hasSasayakiAudio = false,
            ),
        )
    }

    @Test
    fun enabledSasayakiVolumeSeekKeysRepeatSeekActions() {
        val settings = ReaderSettings(volumeKeysSeekSasayaki = true)

        assertEquals(
            ReaderHardwareKeyAction.SasayakiSeekBackward,
            readerHardwareKeyActionForKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_UP,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 4,
                settings = settings,
                sasayakiEnabled = true,
                hasSasayakiAudio = true,
            ),
        )
        assertEquals(
            ReaderHardwareKeyAction.SasayakiSeekForward,
            readerHardwareKeyActionForKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 1,
                settings = settings,
                sasayakiEnabled = true,
                hasSasayakiAudio = true,
            ),
        )
    }

    @Test
    fun enabledVolumeKeysConsumeKeyUpWithoutAction() {
        val settings = ReaderSettings(volumeKeysTurnPages = true)

        val result = readerHardwareKeyEventForKeyEvent(
            keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
            action = KeyEvent.ACTION_UP,
            repeatCount = 0,
            settings = settings,
            sasayakiEnabled = false,
            hasSasayakiAudio = false,
        )

        assertTrue(result.consumed)
        assertNull(result.action)
    }

    @Test
    fun disabledVolumeKeysAreNotConsumed() {
        val settings = ReaderSettings(volumeKeysTurnPages = false, volumeKeysSeekSasayaki = false)

        val result = readerHardwareKeyEventForKeyEvent(
            keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
            action = KeyEvent.ACTION_DOWN,
            repeatCount = 1,
            settings = settings,
            sasayakiEnabled = true,
            hasSasayakiAudio = true,
        )

        assertFalse(result.consumed)
        assertNull(result.action)
    }

    @Test
    fun enabledSasayakiVolumeKeysConsumeKeyUpWithoutAction() {
        val settings = ReaderSettings(volumeKeysSeekSasayaki = true)

        val result = readerHardwareKeyEventForKeyEvent(
            keyCode = KeyEvent.KEYCODE_VOLUME_UP,
            action = KeyEvent.ACTION_UP,
            repeatCount = 0,
            settings = settings,
            sasayakiEnabled = true,
            hasSasayakiAudio = true,
        )

        assertTrue(result.consumed)
        assertNull(result.action)
    }
}
