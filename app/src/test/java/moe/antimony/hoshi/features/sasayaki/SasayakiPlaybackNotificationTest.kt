package moe.antimony.hoshi.features.sasayaki

import androidx.media3.session.CommandButton
import moe.antimony.hoshi.R
import org.junit.Assert.assertEquals
import org.junit.Test

class SasayakiPlaybackNotificationTest {
    @Test
    fun mediaButtonPreferencesPlaceCueCommandsInBackAndForwardSlots() {
        val specs = sasayakiServiceMediaButtonSpecs()

        assertEquals(2, specs.size)
        assertEquals(
            "The left system media button must be Sasayaki previous-cue.",
            SasayakiPreviousCueAction,
            specs[0].customAction,
        )
        assertEquals(CommandButton.SLOT_BACK, specs[0].slot)
        assertEquals(
            "The right system media button must be Sasayaki next-cue, not Media3 seek-to-next.",
            SasayakiNextCueAction,
            specs[1].customAction,
        )
        assertEquals(CommandButton.SLOT_FORWARD, specs[1].slot)
    }

    @Test
    fun restrictedNotificationUsesCueControlsAroundPlaybackToggle() {
        val specs = sasayakiRestrictedNotificationActionSpecs(isPlaying = true)

        assertEquals(3, specs.size)
        assertEquals(SasayakiNotificationPreviousCueAction, specs[0].action)
        assertEquals(SasayakiNotificationTogglePlaybackAction, specs[1].action)
        assertEquals(SasayakiNotificationNextCueAction, specs[2].action)
        assertEquals(R.string.sasayaki_pause, specs[1].titleResId)
    }

    @Test
    fun restrictedNotificationToggleLabelReflectsPausedPlayback() {
        val specs = sasayakiRestrictedNotificationActionSpecs(isPlaying = false)

        assertEquals(SasayakiNotificationTogglePlaybackAction, specs[1].action)
        assertEquals(R.string.sasayaki_play, specs[1].titleResId)
    }
}
