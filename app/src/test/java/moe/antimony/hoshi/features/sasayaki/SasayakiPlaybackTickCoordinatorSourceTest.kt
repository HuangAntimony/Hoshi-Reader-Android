package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SasayakiPlaybackTickCoordinatorSourceTest {
    @Test
    fun tickSequencingPreservesPlaybackEventInputs() {
        val source = File(
            "src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlaybackTickCoordinator.kt",
        ).readText()
        val tick = source.substringAfter("fun tick(")

        assertTrue(source.contains("class SasayakiPlaybackTickCoordinator("))
        assertTrue(source.contains("private val playbackEvents: SasayakiPlaybackEventCoordinator"))
        assertTrue(source.contains("private val cuePresentation: SasayakiCuePresentationState"))
        assertTrue(source.contains("private val getCurrentChapterIndex: () -> Int"))
        assertFalse(source.contains("mutableStateOf"))

        assertTrue(tick.contains("hasAudio: Boolean"))
        assertTrue(tick.contains("hasMatch: Boolean"))
        assertTrue(tick.contains("delay: Double"))
        assertTrue(tick.contains("pausePlayback: () -> Unit"))
        assertTrue(tick.contains("updateMediaSession: () -> Unit"))
        assertTrue(tick.contains("applyCueDisplayAction: (SasayakiCueDisplayAction) -> Unit"))
        assertTrue(tick.contains("playbackEvents.tick("))
        assertTrue(tick.contains("hasAudio = hasAudio"))
        assertTrue(tick.contains("hasMatch = hasMatch"))
        assertTrue(tick.contains("delay = delay"))
        assertTrue(tick.contains("currentChapterIndex = getCurrentChapterIndex()"))
        assertTrue(tick.contains("autoScroll = cuePresentation.autoScroll"))
        assertTrue(tick.contains("hasPlayedOnce = cuePresentation.hasPlayedOnce"))
        assertTrue(tick.contains("pausePlayback = pausePlayback"))
        assertTrue(tick.contains("updateMediaSession = updateMediaSession"))
        assertTrue(tick.contains("applyCueDisplayAction = applyCueDisplayAction"))
        assertTrue(tick.indexOf("currentChapterIndex = getCurrentChapterIndex()") < tick.indexOf("autoScroll = cuePresentation.autoScroll"))
        assertTrue(tick.indexOf("autoScroll = cuePresentation.autoScroll") < tick.indexOf("hasPlayedOnce = cuePresentation.hasPlayedOnce"))
    }
}
