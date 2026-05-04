package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SasayakiCueUpdateCoordinatorSourceTest {
    @Test
    fun updateSequencingPreservesCuePresentationInputs() {
        val source = File(
            "src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiCueUpdateCoordinator.kt",
        ).readText()
        val update = source.substringAfter("fun update(")

        assertTrue(source.contains("class SasayakiCueUpdateCoordinator("))
        assertTrue(source.contains("private val playbackEvents: SasayakiPlaybackEventCoordinator"))
        assertTrue(source.contains("private val cuePresentation: SasayakiCuePresentationState"))
        assertTrue(source.contains("private val getCurrentChapterIndex: () -> Int"))
        assertFalse(source.contains("mutableStateOf"))

        assertTrue(update.contains("hasAudio: Boolean"))
        assertTrue(update.contains("hasMatch: Boolean"))
        assertTrue(update.contains("time: Double"))
        assertTrue(update.contains("delay: Double"))
        assertTrue(update.contains("forceDisplay: Boolean"))
        assertTrue(update.contains("applyCueDisplayAction: (SasayakiCueDisplayAction) -> Unit"))
        assertTrue(update.contains("playbackEvents.updateCue("))
        assertTrue(update.contains("hasAudio = hasAudio"))
        assertTrue(update.contains("hasMatch = hasMatch"))
        assertTrue(update.contains("time = time"))
        assertTrue(update.contains("delay = delay"))
        assertTrue(update.contains("currentChapterIndex = getCurrentChapterIndex()"))
        assertTrue(update.contains("autoScroll = cuePresentation.autoScroll"))
        assertTrue(update.contains("hasPlayedOnce = cuePresentation.hasPlayedOnce"))
        assertTrue(update.contains("forceDisplay = forceDisplay"))
        assertTrue(update.contains("applyCueDisplayAction = applyCueDisplayAction"))
        assertTrue(update.indexOf("currentChapterIndex = getCurrentChapterIndex()") < update.indexOf("autoScroll = cuePresentation.autoScroll"))
        assertTrue(update.indexOf("autoScroll = cuePresentation.autoScroll") < update.indexOf("hasPlayedOnce = cuePresentation.hasPlayedOnce"))
        assertTrue(update.indexOf("hasPlayedOnce = cuePresentation.hasPlayedOnce") < update.indexOf("forceDisplay = forceDisplay"))
    }
}
