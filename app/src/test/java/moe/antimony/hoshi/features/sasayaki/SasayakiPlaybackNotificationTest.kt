package moe.antimony.hoshi.features.sasayaki

import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import moe.antimony.hoshi.R
import org.junit.Assert.assertEquals
import org.junit.Test

class SasayakiPlaybackNotificationTest {
    @Test
    fun mediaButtonPreferencesPlaceStandardCueCommandsInBackAndForwardSlots() {
        val specs = sasayakiServiceMediaButtonSpecs()

        assertEquals(2, specs.size)
        assertEquals(
            "The left system media button must use standard previous so platform controls route through Player.",
            Player.COMMAND_SEEK_TO_PREVIOUS,
            specs[0].playerCommand,
        )
        assertEquals(CommandButton.SLOT_BACK, specs[0].slot)
        assertEquals(
            "The right system media button must use standard next so platform controls route through Player.",
            Player.COMMAND_SEEK_TO_NEXT,
            specs[1].playerCommand,
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
    fun standardPreviousSeekCommandDispatchesPreviousCue() {
        val commands = mutableListOf<String>()
        dispatchSasayakiServicePlayerSeekCommand(
            seekCommand = Player.COMMAND_SEEK_TO_PREVIOUS,
            positionMs = 5_000L,
            previousCue = { commands += "previous" },
            nextCue = { commands += "next" },
            seekTo = { commands += "seek:$it" },
        )

        assertEquals(listOf("previous"), commands)
    }

    @Test
    fun standardNextSeekCommandDispatchesNextCue() {
        val commands = mutableListOf<String>()
        dispatchSasayakiServicePlayerSeekCommand(
            seekCommand = Player.COMMAND_SEEK_TO_NEXT,
            positionMs = 5_000L,
            previousCue = { commands += "previous" },
            nextCue = { commands += "next" },
            seekTo = { commands += "seek:$it" },
        )

        assertEquals(listOf("next"), commands)
    }

    @Test
    fun normalSeekCommandDispatchesAbsoluteSeek() {
        val commands = mutableListOf<String>()
        dispatchSasayakiServicePlayerSeekCommand(
            seekCommand = Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            positionMs = 5_000L,
            previousCue = { commands += "previous" },
            nextCue = { commands += "next" },
            seekTo = { commands += "seek:$it" },
        )

        assertEquals(listOf("seek:5000"), commands)
    }
}
