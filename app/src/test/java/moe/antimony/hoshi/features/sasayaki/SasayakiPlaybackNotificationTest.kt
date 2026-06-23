package moe.antimony.hoshi.features.sasayaki

import androidx.media3.session.CommandButton
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
}
