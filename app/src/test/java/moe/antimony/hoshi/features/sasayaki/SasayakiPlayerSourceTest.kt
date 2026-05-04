package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SasayakiPlayerSourceTest {
    @Test
    fun importedAudioUsesPersistedUriInsteadOfPrivateCopyLikeIos() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlayer.kt").readText()
        val importAudio = source.substringAfter("fun importAudio(")
            .substringBefore("fun togglePlayback()")
        val restoreAudio = source.substringAfter("private fun restoreAudio()")
            .substringBefore("private fun tick()")

        assertTrue(source.contains("import android.net.Uri"))
        assertTrue(importAudio.contains("audioUri: Uri, copiedAudioFileName: String? = null"))
        assertTrue(importAudio.contains("playbackPersistence.importAudio(audioUri, copiedAudioFileName)"))
        assertFalse(importAudio.contains("audioSourceRepository.importedPlayback(playback, audioUri, copiedAudioFileName)"))
        assertTrue(source.contains("private val audioRestore = SasayakiAudioRestoreController("))
        assertTrue(restoreAudio.contains("audioRestore.restore("))
        assertTrue(restoreAudio.contains("onFailure(audioAvailability::markRestoreFailed)"))
        assertTrue(restoreAudio.contains("audioAvailability.markRestoreSucceeded()"))
        assertFalse(restoreAudio.contains("audioSourceRepository.playbackSource(playback) ?: return"))
        assertFalse(restoreAudio.contains("Uri.parse("))
        assertFalse(restoreAudio.contains("SasayakiPlaybackSource.ExternalUri"))
        assertFalse(restoreAudio.contains("SasayakiPlaybackSource.PrivateFile"))
        assertFalse(restoreAudio.contains("hasAudio = true"))
        assertFalse(restoreAudio.contains("errorMessage = null"))
    }

    @Test
    fun audioCanBeClearedAndDescribesStorageMode() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlayer.kt").readText()
        val clearAudio = source.substringAfter("fun clearAudio()")
            .substringBefore("fun togglePlayback()")

        assertTrue(source.contains("private val audioAvailability = SasayakiAudioAvailabilityState()"))
        assertTrue(source.contains("val errorMessage: String? get() = audioAvailability.errorMessage"))
        assertTrue(source.contains("val hasAudio: Boolean get() = audioAvailability.hasAudio"))
        assertTrue(source.contains("val audioStorageSummary: String"))
        assertTrue(source.contains("get() = playbackPersistence.audioStorageSummary"))
        assertTrue(clearAudio.contains("audioSourceRepository.clearAudioSource(playback, appContext.contentResolver)"))
        assertFalse(clearAudio.contains("releasePersistableUriPermission"))
        assertTrue(clearAudio.contains("playbackPersistence.clearAudioMetadata()"))
        assertFalse(clearAudio.contains("audioUri = null"))
        assertFalse(clearAudio.contains("audioFileName = null"))
        assertTrue(clearAudio.contains("audioAvailability.markAudioCleared()"))
        assertFalse(clearAudio.contains("hasAudio = false"))
        assertFalse(clearAudio.contains("errorMessage = null"))
    }

    @Test
    fun restoredAudioStaysPausedUntilExplicitPlaybackLikeIos() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlayer.kt").readText()
        val restoreAudio = source.substringAfter("private fun restoreAudio()")
            .substringBefore("private fun tick()")
        val setRate = source.substringAfter("fun setRate(value: Float)")
            .substringBefore("fun importAudio(")
        val startPlayback = source.substringAfter("private fun startPlayback()")
            .substringBefore("private fun seek(")

        assertFalse(restoreAudio.contains("engine.start(rate)"))
        assertTrue(restoreAudio.contains("audioRestore.restore("))
        assertTrue(setRate.contains("playbackLifecycle.setRateIfPlaying(value)"))
        assertFalse(setRate.contains("playbackEngine?.setRate(value)"))
        assertTrue(startPlayback.contains("playbackCommands.start("))
        assertTrue(startPlayback.contains("rate = rate"))
        assertTrue(startPlayback.contains("markPlayedOnce = cuePresentation::markPlayedOnce"))
    }

    @Test
    fun popupCuePlaybackMatchesIosStopSemantics() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlayer.kt").readText()
        val eventSource = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlaybackEventCoordinator.kt").readText()
        val playCue = source.substringAfter("fun playCue(cue: SasayakiMatch, stop: Boolean)")
            .substringBefore("fun release()")
        val tick = source.substringAfter("private fun tick()")
            .substringBefore("private fun updateCue(")
        val seek = source.substringAfter("private fun seek(")
            .substringBefore("private fun restoreAudio()")

        assertTrue(source.contains("private val playbackState = SasayakiPlaybackStateCoordinator("))
        assertFalse(source.contains("private var stopPlaybackTime: Double? = null"))
        assertFalse(source.contains("private var temporaryPlaybackReturnPosition: Double? = null"))
        assertTrue(source.contains("fun findCue(chapterIndex: Int, offset: Int): SasayakiMatch?"))
        assertTrue(source.contains("private val cueNavigation = SasayakiCueNavigationController(matchData)"))
        assertTrue(playCue.contains("playbackCommands.playCue("))
        assertTrue(playCue.contains("cue = cue"))
        assertTrue(playCue.contains("stop = stop"))
        assertTrue(playCue.contains("isPlaying = isPlaying"))
        assertTrue(playCue.contains("lastPosition = playback.lastPosition"))
        assertTrue(playCue.contains("delay = delay"))
        assertTrue(playCue.contains("pauseWithoutRestore = { pausePlayback(restoreTemporaryPosition = false) }"))
        assertTrue(seek.contains("savePosition: Boolean = true"))
        assertTrue(eventSource.contains("if (seek.savePosition)"))
        assertTrue(tick.contains("playbackEvents.tick("))
        assertTrue(tick.contains("pausePlayback = { pausePlayback() }"))
        assertTrue(eventSource.contains("tick.shouldStopPlayback"))
        assertTrue(eventSource.contains("pausePlayback()"))
        assertTrue(eventSource.indexOf("tick.shouldSavePosition") < eventSource.indexOf("tick.shouldStopPlayback"))
        assertTrue(source.contains("private fun restoreTemporaryPlaybackPositionIfNeeded()"))
        assertTrue(source.contains("playbackState.restoreTemporaryPlaybackPositionIfNeeded() ?: return"))
    }

    @Test
    fun seekWaitsForMediaPlayerCompletionBeforeUpdatingCueLikeIos() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlayer.kt").readText()
        val eventSource = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlaybackEventCoordinator.kt").readText()
        val seek = source.substringAfter("private fun seek(")
            .substringBefore("private fun restoreAudio()")
        val complete = source.substringAfter("private fun handleSeekComplete(")
            .substringBefore("private fun restoreAudio()")
        val restoreAudio = source.substringAfter("private fun restoreAudio()")
            .substringBefore("private fun tick()")
        val tick = source.substringAfter("private fun tick()")
            .substringBefore("private fun updateCue(")

        assertFalse(source.contains("private data class PendingSeek"))
        assertFalse(source.contains("private var pendingSeek"))
        assertTrue(restoreAudio.contains("onSeekComplete = ::handleSeekComplete"))
        assertTrue(seek.contains("playbackCommands.seek("))
        assertTrue(seek.contains("seconds = seconds"))
        assertTrue(seek.contains("displayCue = displayCue"))
        assertFalse(seek.contains("handler.removeCallbacks(tickRunnable)"))
        assertFalse(seek.contains("engine.seekTo((seconds * 1000.0).toInt())"))
        assertFalse(seek.substringBefore("private fun handleSeekComplete(").contains("if (updateCue) updateCue(seconds)"))
        assertFalse(seek.substringBefore("private fun handleSeekComplete(").contains("if (startPlayback) startPlayback()"))
        assertTrue(complete.contains("playbackEvents.handleSeekComplete("))
        assertTrue(complete.contains("startPlayback = ::startPlayback"))
        assertTrue(eventSource.contains("val seek = playbackState.completeSeek() ?: return"))
        assertTrue(eventSource.contains("if (seek.updateCue)"))
        assertTrue(eventSource.contains("time = seek.seconds"))
        assertTrue(eventSource.contains("if (seek.startPlayback) startPlayback()"))
        assertTrue(tick.contains("playbackEvents.tick("))
        assertTrue(eventSource.contains("val tick = playbackLifecycle.updateTick() ?: return"))
        assertFalse(tick.contains("if (playbackState.hasPendingSeek) return"))
    }

    @Test
    fun popupCuePlaybackDisplaysSelectedCueAfterSeekCompletes() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlayer.kt").readText()
        val eventSource = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlaybackEventCoordinator.kt").readText()
        val playCue = source.substringAfter("fun playCue(cue: SasayakiMatch, stop: Boolean)")
            .substringBefore("fun release()")
        val seek = source.substringAfter("private fun seek(")
            .substringBefore("private fun handleSeekComplete(")
        val complete = source.substringAfter("private fun handleSeekComplete(")
            .substringBefore("private fun restoreAudio()")

        assertTrue(source.contains("displayCue: SasayakiMatch? = null"))
        assertTrue(playCue.contains("playbackCommands.playCue("))
        assertTrue(seek.contains("displayCue = displayCue"))
        assertFalse(source.contains("private fun displayCue(cue: SasayakiMatch, reveal: Boolean)"))
        assertTrue(complete.contains("playbackEvents.handleSeekComplete("))
        assertTrue(complete.contains("applyCueDisplayAction = ::applyCueDisplayAction"))
        assertTrue(eventSource.contains("seek.displayCue?.let { cue ->"))
        assertTrue(eventSource.contains("cueDisplay.displaySelectedCue("))
        assertTrue(eventSource.contains("reveal = autoScroll && (hasPlayedOnce || seek.startPlayback)"))
        assertTrue(eventSource.indexOf("seek.displayCue?.let { cue ->") < eventSource.indexOf("if (seek.startPlayback) startPlayback()"))
    }

    @Test
    fun startPlaybackRedisplaysCurrentCueLikeIos() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlayer.kt").readText()
        val startPlayback = source.substringAfter("private fun startPlayback()")
            .substringBefore("private fun seek(")
        val updateCue = source.substringAfter("private fun updateCue(")
            .substringBefore("private fun applyCueDisplayAction(")

        assertTrue(source.contains("private val cuePresentation = SasayakiCuePresentationState()"))
        assertTrue(source.contains("var autoScroll: Boolean"))
        assertTrue(source.contains("get() = cuePresentation.autoScroll"))
        assertTrue(source.contains("cuePresentation.autoScroll = value"))
        assertFalse(source.contains("private var hasPlayedOnce = false"))
        assertTrue(startPlayback.contains("markPlayedOnce = cuePresentation::markPlayedOnce"))
        assertTrue(startPlayback.contains("updateCue(currentTime, forceDisplay = true)"))
        assertTrue(updateCue.contains("forceDisplay: Boolean = false"))
        assertTrue(updateCue.contains("playbackEvents.updateCue("))
        assertTrue(updateCue.contains("forceDisplay = forceDisplay"))
    }

    @Test
    fun autoScrollCrossChapterCueClearsDisplayedCueBeforeLoadingChapter() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlayer.kt").readText()
        val eventSource = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlaybackEventCoordinator.kt").readText()
        val updateCue = source.substringAfter("private fun updateCue(")
            .substringBefore("private fun applyCueDisplayAction(")
        val applyAction = source.substringAfter("private fun applyCueDisplayAction(")
            .substringBefore("private fun updateMediaSession(")

        assertTrue(source.contains("private val cueDisplay = SasayakiCueDisplayCoordinator()"))
        assertFalse(source.contains("private var currentCue: SasayakiMatch? = null"))
        assertTrue(updateCue.contains("playbackEvents.updateCue("))
        assertTrue(updateCue.contains("currentChapterIndex = getCurrentChapterIndex()"))
        assertTrue(updateCue.contains("autoScroll = cuePresentation.autoScroll"))
        assertTrue(updateCue.contains("hasPlayedOnce = cuePresentation.hasPlayedOnce"))
        assertTrue(eventSource.contains("cueDisplay.update("))
        assertTrue(applyAction.contains("SasayakiCueDisplayAction.Clear -> onClearCue()"))
        assertTrue(applyAction.contains("is SasayakiCueDisplayAction.Display -> onCue(action.cue, action.reveal)"))
        assertTrue(applyAction.contains("is SasayakiCueDisplayAction.ClearAndLoadChapter -> {"))
        assertTrue(applyAction.contains("onClearCue()"))
        assertTrue(applyAction.contains("onLoadChapter(action.chapterIndex)"))
        assertTrue(applyAction.indexOf("onClearCue()") < applyAction.indexOf("onLoadChapter(action.chapterIndex)"))
    }

    @Test
    fun playerUsesCueNavigationBoundaryForTimelineOperations() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlayer.kt").readText()
        val eventSource = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlaybackEventCoordinator.kt").readText()
        val nextCue = source.substringAfter("fun nextCue()")
            .substringBefore("fun previousCue()")
        val previousCue = source.substringAfter("fun previousCue()")
            .substringBefore("fun findCue(")
        val findCue = source.substringAfter("fun findCue(chapterIndex: Int, offset: Int): SasayakiMatch?")
            .substringBefore("fun playCue(")
        val updateCue = source.substringAfter("private fun updateCue(")
            .substringBefore("private fun applyCueDisplayAction(")

        assertTrue(source.contains("private val cueNavigation = SasayakiCueNavigationController(matchData)"))
        assertFalse(source.contains("private val timeline = CueTimeline(matchData)"))
        assertFalse(source.contains("import kotlin.math.max"))
        assertTrue(source.contains("private val playbackCommands = SasayakiPlaybackCommandCoordinator("))
        assertTrue(nextCue.contains("playbackCommands.nextCue("))
        assertTrue(nextCue.contains("currentTime = currentTime"))
        assertTrue(nextCue.contains("delay = delay"))
        assertTrue(nextCue.contains("isPlaying = isPlaying"))
        assertFalse(nextCue.contains("seek(next, startPlayback = isPlaying)"))
        assertFalse(nextCue.contains("timeline.nextCue("))
        assertTrue(previousCue.contains("playbackCommands.previousCue("))
        assertTrue(previousCue.contains("currentTime = currentTime"))
        assertTrue(previousCue.contains("delay = delay"))
        assertTrue(previousCue.contains("isPlaying = isPlaying"))
        assertFalse(previousCue.contains("seek(previous, startPlayback = isPlaying)"))
        assertFalse(previousCue.contains("timeline.previousCue("))
        assertTrue(findCue.contains("cueNavigation.findCue(chapterIndex = chapterIndex, offset = offset)"))
        assertTrue(updateCue.contains("playbackEvents.updateCue("))
        assertTrue(eventSource.contains("cueNavigation.cueAtPlaybackTime(time = time, delay = delay)"))
        assertFalse(updateCue.contains("timeline.cueAt("))
    }

    @Test
    fun playbackPositionPersistsOnDelayRateSeekAndWholeSecondTicks() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlayer.kt").readText()
        val eventSource = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlaybackEventCoordinator.kt").readText()
        val setDelay = source.substringAfter("fun setDelay(value: Double)")
            .substringBefore("fun setRate(value: Float)")
        val setRate = source.substringAfter("fun setRate(value: Float)")
            .substringBefore("fun importAudio(")
        val complete = source.substringAfter("private fun handleSeekComplete(")
            .substringBefore("private fun restoreAudio()")
        val tick = source.substringAfter("private fun tick()")
            .substringBefore("private fun updateCue(")

        assertTrue(source.contains("private val playbackPersistence = SasayakiPlaybackPersistenceState("))
        assertTrue(source.contains("val playback: SasayakiPlaybackData get() = playbackPersistence.playback"))
        assertTrue(setDelay.contains("playbackPersistence.setDelay(value)"))
        assertFalse(setDelay.contains("playback = playback.copy(delay = value)"))
        assertFalse(setDelay.contains("savePlayback()"))
        assertTrue(setRate.contains("playbackPersistence.setRate(value)"))
        assertFalse(setRate.contains("playback = playback.copy(rate = value)"))
        assertFalse(setRate.contains("savePlayback()"))
        assertTrue(complete.contains("playbackEvents.handleSeekComplete("))
        assertTrue(eventSource.contains("playbackPersistence.savePosition(seek.seconds)"))
        assertFalse(complete.contains("playback = playback.copy(lastPosition = seek.seconds)"))
        assertFalse(complete.contains("savePlayback()"))
        assertTrue(tick.contains("playbackEvents.tick("))
        assertTrue(eventSource.contains("val tick = playbackLifecycle.updateTick() ?: return"))
        assertTrue(eventSource.contains("if (tick.shouldSavePosition)"))
        assertTrue(eventSource.contains("playbackPersistence.savePosition(playbackState.currentTime)"))
        assertFalse(tick.contains("playback = playback.copy(lastPosition = currentTime)"))
        assertFalse(tick.contains("savePlayback()"))
    }

    @Test
    fun playerUsesPlaybackEngineBoundaryForMediaPlayerOperations() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlayer.kt").readText()
        val restoreAudio = source.substringAfter("private fun restoreAudio()")
            .substringBefore("private fun tick()")
        val tick = source.substringAfter("private fun tick()")
            .substringBefore("private fun updateCue(")
        val teardown = source.substringAfter("private fun teardownPlayer(clearCue: Boolean)")

        assertFalse(source.contains("import android.media.MediaPlayer"))
        assertFalse(source.contains("import android.media.AudioAttributes"))
        assertFalse(source.contains("import android.media.PlaybackParams"))
        assertFalse(source.contains("MediaPlayer()"))
        assertTrue(source.contains("private val audioSourceRepository = SasayakiAudioRepository(bookRoot)"))
        assertTrue(source.contains("private val audioRestore = SasayakiAudioRestoreController("))
        assertTrue(source.contains("private val playbackLifecycle = SasayakiPlaybackLifecycleController("))
        assertFalse(source.contains("private var playbackEngine: SasayakiPlaybackEngine? = null"))
        assertFalse(restoreAudio.contains("AndroidSasayakiPlaybackEngine.prepare("))
        assertFalse(restoreAudio.contains("startPositionMs = (playback.lastPosition * 1000.0).toInt()"))
        assertTrue(restoreAudio.contains("SasayakiAudioRestoreCallbacks("))
        assertTrue(restoreAudio.contains("onCompletion = {"))
        assertTrue(restoreAudio.contains("onSeekComplete = ::handleSeekComplete"))
        assertFalse(restoreAudio.contains("playbackLifecycle.attachEngine(engine)"))
        assertTrue(restoreAudio.contains("mediaSessionHandle.replace(result.mediaSession)"))
        assertTrue(restoreAudio.contains("playbackState.updateDuration(result.durationMs)"))
        assertTrue(tick.contains("playbackEvents.tick("))
        assertFalse(tick.contains("val engine = playbackEngine ?: return"))
        assertFalse(tick.contains("currentPositionMs = engine.currentPositionMs"))
        assertFalse(tick.contains("durationMs = engine.durationMs"))
        assertTrue(teardown.contains("playbackLifecycle.releaseEngine()"))
        assertTrue(teardown.contains("audioAvailability.markAudioUnavailable()"))
        assertFalse(teardown.contains("playbackEngine?.release()"))
        assertFalse(teardown.contains("playbackEngine = null"))
    }
}
