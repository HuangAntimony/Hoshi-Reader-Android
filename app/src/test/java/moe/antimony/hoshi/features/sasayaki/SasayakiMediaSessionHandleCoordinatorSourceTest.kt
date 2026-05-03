package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SasayakiMediaSessionHandleCoordinatorSourceTest {
    @Test
    fun coordinatorOwnsCurrentMediaSessionHandleLifecycle() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiMediaSessionHandleCoordinator.kt").readText()
        val replace = source.substringAfter("fun replace(")
            .substringBefore("fun activate(")
        val activate = source.substringAfter("fun activate(")
            .substringBefore("fun update(")
        val update = source.substringAfter("fun update(")
            .substringBefore("fun releaseExisting(")
        val releaseExisting = source.substringAfter("fun releaseExisting(")
            .substringBefore("fun releaseAndClear(")
        val releaseAndClear = source.substringAfter("fun releaseAndClear(")

        assertTrue(source.contains("private var mediaSession: SasayakiMediaSessionHandle? = null"))
        assertTrue(replace.contains("mediaSession = handle"))
        assertTrue(activate.contains("mediaSession?.activate()"))
        assertTrue(update.contains("mediaSession?.update("))
        assertTrue(update.contains("isPlaying = isPlaying"))
        assertTrue(update.contains("currentTimeMs = currentTimeMs"))
        assertTrue(update.contains("durationMs = durationMs"))
        assertTrue(update.contains("rate = rate"))
        assertTrue(releaseExisting.contains("mediaSession?.release()"))
        assertFalse(releaseExisting.contains("mediaSession = null"))
        assertTrue(releaseAndClear.contains("mediaSession?.release()"))
        assertTrue(releaseAndClear.contains("mediaSession = null"))
    }
}
