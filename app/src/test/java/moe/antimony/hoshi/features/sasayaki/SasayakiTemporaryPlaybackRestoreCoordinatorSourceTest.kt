package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SasayakiTemporaryPlaybackRestoreCoordinatorSourceTest {
    @Test
    fun restoreSequencingPreservesPopupReturnBehavior() {
        val source = File(
            "src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiTemporaryPlaybackRestoreCoordinator.kt",
        ).readText()

        assertTrue(source.contains("class SasayakiTemporaryPlaybackRestoreCoordinator("))
        assertTrue(source.contains("private val playbackState: SasayakiPlaybackStateCoordinator"))
        assertTrue(source.contains("private val playbackLifecycle: SasayakiPlaybackLifecycleController"))
        assertTrue(source.contains("fun restoreIfNeeded("))
        assertTrue(source.contains("updateCue: (Double) -> Unit"))
        assertTrue(source.contains("updateMediaSession: () -> Unit"))
        assertFalse(source.contains("mutableStateOf"))

        val restore = source.substringAfter("fun restoreIfNeeded(")
        assertTrue(restore.contains("val returnPosition = playbackState.restoreTemporaryPlaybackPositionIfNeeded() ?: return"))
        assertTrue(restore.contains("playbackLifecycle.seekTo((returnPosition * 1000.0).toInt())"))
        assertTrue(restore.contains("updateCue(returnPosition)"))
        assertTrue(restore.contains("updateMediaSession()"))
        assertTrue(
            restore.indexOf("playbackState.restoreTemporaryPlaybackPositionIfNeeded() ?: return") <
                restore.indexOf("playbackLifecycle.seekTo((returnPosition * 1000.0).toInt())"),
        )
        assertTrue(
            restore.indexOf("playbackLifecycle.seekTo((returnPosition * 1000.0).toInt())") <
                restore.indexOf("updateCue(returnPosition)"),
        )
        assertTrue(restore.indexOf("updateCue(returnPosition)") < restore.indexOf("updateMediaSession()"))
    }
}
