package moe.antimony.hoshi.features.sasayaki

import androidx.media3.session.CommandButton
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
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
    fun oemRestrictedFallbackNotificationUsesCueControlsAroundPlaybackToggle() {
        val specs = sasayakiOemRestrictedNotificationActionSpecs(isPlaying = true)

        assertEquals(3, specs.size)
        assertEquals(SasayakiOemRestrictedNotificationPreviousCueAction, specs[0].action)
        assertEquals(SasayakiOemRestrictedNotificationTogglePlaybackAction, specs[1].action)
        assertEquals(SasayakiOemRestrictedNotificationNextCueAction, specs[2].action)
        assertEquals(R.string.sasayaki_pause, specs[1].titleResId)
    }

    @Test
    fun oemRestrictedFallbackNotificationToggleLabelReflectsPausedPlayback() {
        val specs = sasayakiOemRestrictedNotificationActionSpecs(isPlaying = false)

        assertEquals(SasayakiOemRestrictedNotificationTogglePlaybackAction, specs[1].action)
        assertEquals(R.string.sasayaki_play, specs[1].titleResId)
    }

    @Test
    fun serviceSessionCommandDispatchesPreviousCue() {
        val commands = mutableListOf<String>()
        val result = dispatchSasayakiServiceSessionAction(
            customAction = SasayakiPreviousCueAction,
            previousCue = { commands += "previous" },
            nextCue = { commands += "next" },
        )

        assertEquals(SessionResult.RESULT_SUCCESS, result)
        assertEquals(listOf("previous"), commands)
    }

    @Test
    fun serviceSessionCommandDispatchesNextCue() {
        val commands = mutableListOf<String>()
        val result = dispatchSasayakiServiceSessionAction(
            customAction = SasayakiNextCueAction,
            previousCue = { commands += "previous" },
            nextCue = { commands += "next" },
        )

        assertEquals(SessionResult.RESULT_SUCCESS, result)
        assertEquals(listOf("next"), commands)
    }

    @Test
    fun serviceSessionCommandRejectsUnknownAction() {
        val commands = mutableListOf<String>()
        val result = dispatchSasayakiServiceSessionAction(
            customAction = "unknown",
            previousCue = { commands += "previous" },
            nextCue = { commands += "next" },
        )

        assertEquals(SessionError.ERROR_NOT_SUPPORTED, result)
        assertEquals(emptyList<String>(), commands)
    }
}
