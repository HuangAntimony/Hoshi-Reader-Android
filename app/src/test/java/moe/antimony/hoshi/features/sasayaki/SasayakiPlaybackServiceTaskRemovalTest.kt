package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SasayakiPlaybackServiceTaskRemovalTest {
    @Test
    fun keepsInternalControllerWhenMedia3ReportsOngoingPlayback() {
        assertFalse(sasayakiShouldReleaseInternalControllerOnTaskRemoved(isPlaybackOngoing = true))
    }

    @Test
    fun releasesInternalControllerBeforeStoppingNonOngoingService() {
        assertTrue(sasayakiShouldReleaseInternalControllerOnTaskRemoved(isPlaybackOngoing = false))
    }
}
