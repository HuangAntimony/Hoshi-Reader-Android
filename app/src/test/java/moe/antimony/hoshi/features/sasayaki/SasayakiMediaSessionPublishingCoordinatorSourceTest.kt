package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SasayakiMediaSessionPublishingCoordinatorSourceTest {
    @Test
    fun publishingCoordinatorOwnsActivationAndPlaybackStatePublishing() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiMediaSessionPublishingCoordinator.kt").readText()
        val activate = source.substringAfter("fun activate(")
            .substringBefore("fun update(")
        val update = source.substringAfter("fun update(")

        assertTrue(source.contains("private val mediaSessionHandle: SasayakiMediaSessionHandleCoordinator"))
        assertTrue(activate.contains("mediaSessionHandle.activate()"))
        assertTrue(update.contains("mediaSessionHandle.update("))
        assertTrue(update.contains("isPlaying = isPlaying"))
        assertTrue(update.contains("currentTimeMs = (currentTime * 1000.0).toLong()"))
        assertTrue(update.contains("durationMs = (duration * 1000.0).toLong()"))
        assertTrue(update.contains("rate = rate"))
        assertFalse(source.contains("mutableStateOf"))
    }
}
