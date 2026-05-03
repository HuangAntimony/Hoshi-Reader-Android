package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SasayakiPlaybackEventCoordinatorSourceTest {
    @Test
    fun eventCoordinatorOwnsSeekCompleteSequencing() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlaybackEventCoordinator.kt").readText()
        val seekComplete = source.substringAfter("fun handleSeekComplete(")
            .substringBefore("fun tick(")

        assertTrue(source.contains("private val playbackState: SasayakiPlaybackStateCoordinator"))
        assertTrue(source.contains("private val playbackPersistence: SasayakiPlaybackPersistenceState"))
        assertTrue(source.contains("private val cueDisplay: SasayakiCueDisplayCoordinator"))
        assertTrue(seekComplete.contains("val seek = playbackState.completeSeek() ?: return"))
        assertTrue(seekComplete.contains("if (seek.savePosition)"))
        assertTrue(seekComplete.contains("playbackPersistence.savePosition(seek.seconds)"))
        assertTrue(seekComplete.contains("if (seek.updateCue)"))
        assertTrue(seekComplete.contains("updateCue("))
        assertTrue(seekComplete.contains("time = seek.seconds"))
        assertTrue(seekComplete.contains("seek.displayCue?.let { cue ->"))
        assertTrue(seekComplete.contains("cueDisplay.displaySelectedCue("))
        assertTrue(seekComplete.contains("reveal = autoScroll && (hasPlayedOnce || seek.startPlayback)"))
        assertTrue(seekComplete.contains("if (seek.startPlayback) startPlayback()"))
        assertTrue(seekComplete.contains("updateMediaSession()"))
        assertTrue(seekComplete.indexOf("playbackPersistence.savePosition(seek.seconds)") < seekComplete.indexOf("if (seek.updateCue)"))
        assertTrue(seekComplete.indexOf("seek.displayCue?.let { cue ->") < seekComplete.indexOf("if (seek.startPlayback) startPlayback()"))
        assertTrue(seekComplete.indexOf("if (seek.startPlayback) startPlayback()") < seekComplete.indexOf("updateMediaSession()"))
    }

    @Test
    fun eventCoordinatorOwnsTickPersistenceStopAndCueRefreshSequencing() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlaybackEventCoordinator.kt").readText()
        val tick = source.substringAfter("fun tick(")
            .substringBefore("fun updateCue(")

        assertTrue(source.contains("private val playbackLifecycle: SasayakiPlaybackLifecycleController"))
        assertTrue(tick.contains("val tick = playbackLifecycle.updateTick() ?: return"))
        assertTrue(tick.contains("if (tick.shouldSavePosition)"))
        assertTrue(tick.contains("playbackPersistence.savePosition(playbackState.currentTime)"))
        assertTrue(tick.contains("if (tick.shouldStopPlayback)"))
        assertTrue(tick.contains("pausePlayback()"))
        assertTrue(tick.contains("updateCue("))
        assertTrue(tick.contains("time = playbackState.currentTime"))
        assertTrue(tick.contains("updateMediaSession()"))
        assertTrue(tick.indexOf("tick.shouldSavePosition") < tick.indexOf("tick.shouldStopPlayback"))
        assertTrue(tick.indexOf("pausePlayback()") < tick.indexOf("updateCue("))
        assertTrue(tick.indexOf("updateCue(") < tick.indexOf("updateMediaSession()"))
    }

    @Test
    fun eventCoordinatorOwnsCueUpdateDecision() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlaybackEventCoordinator.kt").readText()
        val updateCue = source.substringAfter("fun updateCue(")

        assertTrue(source.contains("private val cueNavigation: SasayakiCueNavigationController"))
        assertTrue(updateCue.contains("if (!hasAudio || !hasMatch) return"))
        assertTrue(updateCue.contains("cueNavigation.cueAtPlaybackTime(time = time, delay = delay)"))
        assertTrue(updateCue.contains("cueDisplay.update("))
        assertTrue(updateCue.contains("currentChapterIndex = currentChapterIndex"))
        assertTrue(updateCue.contains("autoScroll = autoScroll"))
        assertTrue(updateCue.contains("hasPlayedOnce = hasPlayedOnce"))
        assertTrue(updateCue.contains("forceDisplay = forceDisplay"))
        assertTrue(updateCue.contains("applyCueDisplayAction("))
        assertFalse(source.contains("mutableStateOf"))
    }
}
