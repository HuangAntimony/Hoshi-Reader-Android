package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SasayakiMediaNotificationActionsTest {
    @Test
    fun publishesThreeCompactActionsWhenPlaying() {
        val actions = SasayakiMediaNotificationActions.forPlaybackState(isPlaying = true)

        assertEquals(
            listOf(
                SasayakiMediaNotificationActions.ActionPrevious,
                SasayakiMediaNotificationActions.ActionPause,
                SasayakiMediaNotificationActions.ActionNext,
            ),
            actions.map { it.action },
        )
        assertEquals(listOf(0, 1, 2), SasayakiMediaNotificationActions.CompactViewIndices.toList())
        assertTrue(actions.all { it.iconResId != 0 })
    }

    @Test
    fun usesPlayActionWhenPaused() {
        val actions = SasayakiMediaNotificationActions.forPlaybackState(isPlaying = false)

        assertEquals(
            listOf(
                SasayakiMediaNotificationActions.ActionPrevious,
                SasayakiMediaNotificationActions.ActionPlay,
                SasayakiMediaNotificationActions.ActionNext,
            ),
            actions.map { it.action },
        )
        assertTrue(actions.all { it.iconResId != 0 })
    }
}
