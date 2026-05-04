package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SasayakiPlaybackStartCoordinatorSourceTest {
    @Test
    fun startSequencingPreservesMediaSessionAndCueRedisplayOrder() {
        val source = File(
            "src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlaybackStartCoordinator.kt",
        ).readText()
        val start = source.substringAfter("fun start(")

        assertTrue(source.contains("class SasayakiPlaybackStartCoordinator("))
        assertTrue(source.contains("private val playbackCommands: SasayakiPlaybackCommandCoordinator"))
        assertTrue(source.contains("private val cuePresentation: SasayakiCuePresentationState"))
        assertTrue(source.contains("private val mediaSessionPublishing: SasayakiMediaSessionPublishingCoordinator"))
        assertFalse(source.contains("mutableStateOf"))

        assertTrue(start.contains("rate: Float"))
        assertTrue(start.contains("currentTime: () -> Double"))
        assertTrue(start.contains("updateMediaSession: () -> Unit"))
        assertTrue(start.contains("redisplayCue: (Double) -> Unit"))
        assertTrue(start.contains("playbackCommands.start("))
        assertTrue(start.contains("rate = rate"))
        assertTrue(start.contains("markPlayedOnce = cuePresentation::markPlayedOnce"))
        assertTrue(start.contains("afterMarkedPlaying = {"))
        assertTrue(start.contains("updateMediaSession()"))
        assertTrue(start.contains("mediaSessionPublishing.activate()"))
        assertTrue(start.contains("redisplayCue(currentTime())"))
        assertTrue(start.indexOf("updateMediaSession()") < start.indexOf("mediaSessionPublishing.activate()"))
        assertTrue(start.indexOf("mediaSessionPublishing.activate()") < start.indexOf("redisplayCue(currentTime())"))
    }
}
