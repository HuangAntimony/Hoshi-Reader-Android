package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SasayakiPlaybackSettingsCoordinatorSourceTest {
    @Test
    fun settingsCoordinatorOwnsDelayAndRateUpdateSequencing() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlaybackSettingsCoordinator.kt").readText()
        val setDelay = source.substringAfter("fun setDelay(")
            .substringBefore("fun setRate(")
        val setRate = source.substringAfter("fun setRate(")

        assertTrue(source.contains("private val playbackPersistence: SasayakiPlaybackPersistenceState"))
        assertTrue(source.contains("private val playbackLifecycle: SasayakiPlaybackLifecycleController"))
        assertTrue(setDelay.contains("playbackPersistence.setDelay(value)"))
        assertTrue(setDelay.contains("updateCue(currentTime)"))
        assertTrue(setDelay.indexOf("playbackPersistence.setDelay(value)") < setDelay.indexOf("updateCue(currentTime)"))
        assertTrue(setRate.contains("playbackPersistence.setRate(value)"))
        assertTrue(setRate.contains("playbackLifecycle.setRateIfPlaying(value)"))
        assertTrue(setRate.contains("updateMediaSession()"))
        assertTrue(setRate.indexOf("playbackPersistence.setRate(value)") < setRate.indexOf("playbackLifecycle.setRateIfPlaying(value)"))
        assertTrue(setRate.indexOf("playbackLifecycle.setRateIfPlaying(value)") < setRate.indexOf("updateMediaSession()"))
        assertFalse(source.contains("mutableStateOf"))
    }
}
