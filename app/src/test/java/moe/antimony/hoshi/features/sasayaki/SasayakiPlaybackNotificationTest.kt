package moe.antimony.hoshi.features.sasayaki

import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.R as Media3R
import moe.antimony.hoshi.R
import org.junit.Assert.assertEquals
import org.junit.Test

class SasayakiPlaybackNotificationTest {
    @Test
    fun mediaButtonPreferencesExposeConfiguredRewindAndFastForwardSlots() {
        val specs = sasayakiServiceMediaButtonSpecs()

        assertEquals(2, specs.size)
        assertEquals(
            "The left system media button must use a seek-back command that routes to Sasayaki's configured backward action.",
            Player.COMMAND_SEEK_BACK,
            specs[0].playerCommand,
        )
        assertEquals(CommandButton.SLOT_BACK, specs[0].slot)
        assertEquals(CommandButton.ICON_REWIND, specs[0].icon)
        assertEquals(R.string.sasayaki_rewind, specs[0].displayNameResId)
        assertEquals(
            "The right system media button must use a seek-forward command that routes to Sasayaki's configured forward action.",
            Player.COMMAND_SEEK_FORWARD,
            specs[1].playerCommand,
        )
        assertEquals(CommandButton.SLOT_FORWARD, specs[1].slot)
        assertEquals(CommandButton.ICON_FAST_FORWARD, specs[1].icon)
        assertEquals(R.string.sasayaki_fast_forward, specs[1].displayNameResId)
    }

    @Test
    fun oemRestrictedFallbackNotificationUsesMedia3PlayerCommands() {
        val specs = sasayakiOemRestrictedNotificationActionSpecs(isPlaying = true)

        assertEquals(3, specs.size)
        assertEquals(Player.COMMAND_SEEK_BACK, specs[0].playerCommand)
        assertEquals(Media3R.drawable.media3_icon_rewind, specs[0].iconResId)
        assertEquals(R.string.sasayaki_rewind, specs[0].titleResId)
        assertEquals(Player.COMMAND_PLAY_PAUSE, specs[1].playerCommand)
        assertEquals(Player.COMMAND_SEEK_FORWARD, specs[2].playerCommand)
        assertEquals(Media3R.drawable.media3_icon_fast_forward, specs[2].iconResId)
        assertEquals(R.string.sasayaki_fast_forward, specs[2].titleResId)
        assertEquals(R.string.sasayaki_pause, specs[1].titleResId)
    }

    @Test
    fun oemRestrictedFallbackNotificationToggleLabelReflectsPausedPlayback() {
        val specs = sasayakiOemRestrictedNotificationActionSpecs(isPlaying = false)

        assertEquals(Player.COMMAND_PLAY_PAUSE, specs[1].playerCommand)
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
    fun standardSeekBackCommandDispatchesPreviousCuePath() {
        val commands = mutableListOf<String>()
        dispatchSasayakiServicePlayerSeekCommand(
            seekCommand = Player.COMMAND_SEEK_BACK,
            positionMs = 5_000L,
            previousCue = { commands += "previous" },
            nextCue = { commands += "next" },
            seekTo = { commands += "seek:$it" },
        )

        assertEquals(listOf("previous"), commands)
    }

    @Test
    fun standardSeekForwardCommandDispatchesNextCuePath() {
        val commands = mutableListOf<String>()
        dispatchSasayakiServicePlayerSeekCommand(
            seekCommand = Player.COMMAND_SEEK_FORWARD,
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
