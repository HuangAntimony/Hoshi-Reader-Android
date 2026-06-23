package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SasayakiPlaybackServiceTaskRemovalTest {
    @Test
    fun keepsPlaybackWhenSasayakiPlaybackIsStillRequested() {
        assertFalse(
            sasayakiShouldStopPlaybackOnTaskRemoved(
                isForegroundPlaybackRequested = true,
            ),
        )
    }

    @Test
    fun stopsPlaybackAfterUserPausedBeforeTaskRemoval() {
        assertTrue(
            sasayakiShouldStopPlaybackOnTaskRemoved(
                isForegroundPlaybackRequested = false,
            ),
        )
    }
}
