package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SasayakiPlaybackCommandCoordinatorSourceTest {
    @Test
    fun commandCoordinatorOwnsToggleStartPauseAndSeekSequencing() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlaybackCommandCoordinator.kt").readText()
        val toggle = source.substringAfter("fun toggle(")
            .substringBefore("fun start(")
        val start = source.substringAfter("fun start(")
            .substringBefore("fun pause(")
        val pause = source.substringAfter("fun pause(")
            .substringBefore("fun nextCue(")
        val seek = source.substringAfter("fun seek(")

        assertTrue(source.contains("private val playbackState: SasayakiPlaybackStateCoordinator"))
        assertTrue(source.contains("private val playbackLifecycle: SasayakiPlaybackLifecycleController"))
        assertTrue(toggle.contains("if (isPlaying) pausePlayback() else startPlayback()"))
        assertTrue(start.contains("playbackLifecycle.start("))
        assertTrue(start.contains("rate = rate"))
        assertTrue(start.contains("markPlayedOnce = markPlayedOnce"))
        assertTrue(start.contains("afterMarkedPlaying = afterMarkedPlaying"))
        assertTrue(pause.contains("playbackLifecycle.pause("))
        assertTrue(pause.contains("restoreTemporaryPosition = restoreTemporaryPosition"))
        assertTrue(pause.contains("updateMediaSession = updateMediaSession"))
        assertTrue(pause.contains("restoreTemporaryPositionIfNeeded = restoreTemporaryPositionIfNeeded"))
        assertTrue(seek.contains("playbackLifecycle.beginSeek("))
        assertTrue(seek.contains("seconds = seconds"))
        assertTrue(seek.contains("startPlayback = startPlayback"))
        assertTrue(seek.contains("updateCue = updateCue"))
        assertTrue(seek.contains("savePosition = savePosition"))
        assertTrue(seek.contains("displayCue = displayCue"))
    }

    @Test
    fun commandCoordinatorOwnsCueNavigationAndPopupCuePlaybackSetup() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlaybackCommandCoordinator.kt").readText()
        val nextCue = source.substringAfter("fun nextCue(")
            .substringBefore("fun previousCue(")
        val previousCue = source.substringAfter("fun previousCue(")
            .substringBefore("fun playCue(")
        val playCue = source.substringAfter("fun playCue(")
            .substringBefore("fun mediaSessionSeek(")
        val mediaSessionSeek = source.substringAfter("fun mediaSessionSeek(")
            .substringBefore("fun seek(")

        assertTrue(source.contains("private val cueNavigation: SasayakiCueNavigationController"))
        assertTrue(source.contains("private val cueDisplay: SasayakiCueDisplayCoordinator"))
        assertTrue(nextCue.contains("cueNavigation.nextCueSeekTime("))
        assertTrue(nextCue.contains("currentCueStartTime = cueDisplay.currentCueStartTime"))
        assertTrue(nextCue.contains("playbackState.clearStopPlaybackTime()"))
        assertTrue(nextCue.contains("seek(next, startPlayback = isPlaying)"))
        assertTrue(previousCue.contains("cueNavigation.previousCueSeekTime("))
        assertTrue(previousCue.contains("currentCueStartTime = cueDisplay.currentCueStartTime"))
        assertTrue(previousCue.contains("playbackState.clearStopPlaybackTime()"))
        assertTrue(previousCue.contains("seek(previous, startPlayback = isPlaying)"))
        assertTrue(playCue.contains("playbackState.clearStopPlaybackTime()"))
        assertTrue(playCue.contains("if (isPlaying) pauseWithoutRestore()"))
        assertTrue(playCue.contains("playbackState.setTemporaryPlaybackReturnPosition(if (stop) lastPosition else null)"))
        assertTrue(playCue.contains("playbackState.setStopPlaybackTime(if (stop) cue.endTime + delay else null)"))
        assertTrue(playCue.contains("seconds = cue.startTime + delay"))
        assertTrue(playCue.contains("startPlayback = true"))
        assertTrue(playCue.contains("updateCue = false"))
        assertTrue(playCue.contains("savePosition = !stop"))
        assertTrue(playCue.contains("displayCue = cue"))
        assertTrue(mediaSessionSeek.contains("playbackState.clearStopPlaybackTime()"))
        assertTrue(mediaSessionSeek.contains("seek(positionMs.toDouble() / 1000.0, startPlayback = isPlaying)"))
        assertFalse(source.contains("mutableStateOf"))
    }
}
