package moe.antimony.hoshi.features.sasayaki

import androidx.media3.common.Player
import androidx.media3.session.R as Media3R
import moe.antimony.hoshi.R
import org.junit.Assert.assertEquals
import org.junit.Test

class SasayakiPlaybackNotificationTest {
    @Test
    fun playbackNotificationUsesMedia3PlayerCommands() {
        val specs = sasayakiPlaybackNotificationActionSpecs(isPlaying = true)

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
    fun playbackNotificationToggleLabelReflectsPausedPlayback() {
        val specs = sasayakiPlaybackNotificationActionSpecs(isPlaying = false)

        assertEquals(Player.COMMAND_PLAY_PAUSE, specs[1].playerCommand)
        assertEquals(R.string.sasayaki_play, specs[1].titleResId)
    }
}
