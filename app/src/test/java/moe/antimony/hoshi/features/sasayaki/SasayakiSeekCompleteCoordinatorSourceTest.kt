package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SasayakiSeekCompleteCoordinatorSourceTest {
    @Test
    fun handleSequencingPreservesSeekCompleteInputs() {
        val source = File(
            "src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiSeekCompleteCoordinator.kt",
        ).readText()
        val handle = source.substringAfter("fun handle(")

        assertTrue(source.contains("class SasayakiSeekCompleteCoordinator("))
        assertTrue(source.contains("private val playbackEvents: SasayakiPlaybackEventCoordinator"))
        assertTrue(source.contains("private val cuePresentation: SasayakiCuePresentationState"))
        assertTrue(source.contains("private val getCurrentChapterIndex: () -> Int"))
        assertFalse(source.contains("mutableStateOf"))

        assertTrue(handle.contains("hasAudio: Boolean"))
        assertTrue(handle.contains("hasMatch: Boolean"))
        assertTrue(handle.contains("delay: Double"))
        assertTrue(handle.contains("startPlayback: () -> Unit"))
        assertTrue(handle.contains("updateMediaSession: () -> Unit"))
        assertTrue(handle.contains("applyCueDisplayAction: (SasayakiCueDisplayAction) -> Unit"))
        assertTrue(handle.contains("playbackEvents.handleSeekComplete("))
        assertTrue(handle.contains("hasAudio = hasAudio"))
        assertTrue(handle.contains("hasMatch = hasMatch"))
        assertTrue(handle.contains("delay = delay"))
        assertTrue(handle.contains("currentChapterIndex = getCurrentChapterIndex()"))
        assertTrue(handle.contains("autoScroll = cuePresentation.autoScroll"))
        assertTrue(handle.contains("hasPlayedOnce = cuePresentation.hasPlayedOnce"))
        assertTrue(handle.contains("startPlayback = startPlayback"))
        assertTrue(handle.contains("updateMediaSession = updateMediaSession"))
        assertTrue(handle.contains("applyCueDisplayAction = applyCueDisplayAction"))
        assertTrue(handle.indexOf("currentChapterIndex = getCurrentChapterIndex()") < handle.indexOf("autoScroll = cuePresentation.autoScroll"))
        assertTrue(handle.indexOf("autoScroll = cuePresentation.autoScroll") < handle.indexOf("hasPlayedOnce = cuePresentation.hasPlayedOnce"))
        assertTrue(handle.indexOf("hasPlayedOnce = cuePresentation.hasPlayedOnce") < handle.indexOf("startPlayback = startPlayback"))
    }
}
