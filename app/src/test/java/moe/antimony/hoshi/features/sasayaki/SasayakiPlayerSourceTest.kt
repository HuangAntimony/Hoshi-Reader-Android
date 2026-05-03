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
        assertTrue(importAudio.contains("audioSourceRepository.importedPlayback(playback, audioUri, copiedAudioFileName)"))
        assertTrue(restoreAudio.contains("audioSourceRepository.playbackSource(playback) ?: return"))
        assertFalse(restoreAudio.contains("Uri.parse("))
        assertFalse(restoreAudio.contains("SasayakiPlaybackSource.ExternalUri"))
        assertFalse(restoreAudio.contains("SasayakiPlaybackSource.PrivateFile"))
    }

    @Test
    fun audioCanBeClearedAndDescribesStorageMode() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlayer.kt").readText()
        val clearAudio = source.substringAfter("fun clearAudio()")
            .substringBefore("fun togglePlayback()")

        assertTrue(source.contains("val audioStorageSummary: String"))
        assertTrue(source.contains("get() = audioSourceRepository.storageSummary(playback)"))
        assertTrue(clearAudio.contains("audioSourceRepository.clearAudioSource(playback, appContext.contentResolver)"))
        assertFalse(clearAudio.contains("releasePersistableUriPermission"))
        assertTrue(clearAudio.contains("audioUri = null"))
        assertTrue(clearAudio.contains("audioFileName = null"))
        assertTrue(clearAudio.contains("hasAudio = false"))
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
        assertTrue(setRate.contains("if (isPlaying)"))
        assertTrue(setRate.contains("playbackEngine?.setRate(value)"))
        assertTrue(startPlayback.contains("val engine = playbackEngine ?: return"))
        assertTrue(startPlayback.contains("engine.start(rate)"))
        assertTrue(startPlayback.contains("hasPlayedOnce = true"))
    }

    @Test
    fun popupCuePlaybackMatchesIosStopSemantics() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlayer.kt").readText()
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
        assertTrue(playCue.contains("playbackState.clearStopPlaybackTime()"))
        assertTrue(playCue.contains("if (isPlaying) pausePlayback(restoreTemporaryPosition = false)"))
        assertTrue(playCue.contains("playbackState.setTemporaryPlaybackReturnPosition(if (stop) playback.lastPosition else null)"))
        assertTrue(playCue.contains("cue.startTime + delay"))
        assertTrue(playCue.contains("playbackState.setStopPlaybackTime(if (stop) cue.endTime + delay else null)"))
        assertTrue(playCue.contains("savePosition = !stop"))
        assertTrue(seek.contains("savePosition: Boolean = true"))
        assertTrue(source.contains("if (seek.savePosition)"))
        assertTrue(tick.contains("tick.shouldStopPlayback"))
        assertTrue(tick.contains("pausePlayback()"))
        assertTrue(tick.indexOf("tick.shouldSavePosition") < tick.indexOf("tick.shouldStopPlayback"))
        assertTrue(source.contains("private fun restoreTemporaryPlaybackPositionIfNeeded()"))
        assertTrue(source.contains("playbackState.restoreTemporaryPlaybackPositionIfNeeded() ?: return"))
    }

    @Test
    fun seekWaitsForMediaPlayerCompletionBeforeUpdatingCueLikeIos() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlayer.kt").readText()
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
        assertTrue(seek.contains("playbackState.beginSeek("))
        assertTrue(seek.contains("handler.removeCallbacks(tickRunnable)"))
        assertTrue(seek.contains("engine.seekTo((seconds * 1000.0).toInt())"))
        assertFalse(seek.substringBefore("private fun handleSeekComplete(").contains("if (updateCue) updateCue(seconds)"))
        assertFalse(seek.substringBefore("private fun handleSeekComplete(").contains("if (startPlayback) startPlayback()"))
        assertTrue(complete.contains("val seek = playbackState.completeSeek() ?: return"))
        assertTrue(complete.contains("if (seek.updateCue) updateCue(seek.seconds)"))
        assertTrue(complete.contains("if (seek.startPlayback) startPlayback()"))
        assertTrue(tick.contains("if (playbackState.hasPendingSeek) return"))
    }

    @Test
    fun popupCuePlaybackDisplaysSelectedCueAfterSeekCompletes() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlayer.kt").readText()
        val playCue = source.substringAfter("fun playCue(cue: SasayakiMatch, stop: Boolean)")
            .substringBefore("fun release()")
        val seek = source.substringAfter("private fun seek(")
            .substringBefore("private fun handleSeekComplete(")
        val complete = source.substringAfter("private fun handleSeekComplete(")
            .substringBefore("private fun restoreAudio()")

        assertTrue(source.contains("displayCue: SasayakiMatch? = null"))
        assertTrue(playCue.contains("displayCue = cue"))
        assertTrue(seek.contains("displayCue = displayCue"))
        assertFalse(source.contains("private fun displayCue(cue: SasayakiMatch, reveal: Boolean)"))
        assertTrue(complete.contains("seek.displayCue?.let { cue ->"))
        assertTrue(complete.contains("cueDisplay.displaySelectedCue("))
        assertTrue(complete.contains("reveal = autoScroll && (hasPlayedOnce || seek.startPlayback)"))
        assertTrue(complete.contains("applyCueDisplayAction("))
        assertTrue(complete.indexOf("seek.displayCue?.let { cue ->") < complete.indexOf("if (seek.startPlayback) startPlayback()"))
    }

    @Test
    fun startPlaybackRedisplaysCurrentCueLikeIos() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlayer.kt").readText()
        val startPlayback = source.substringAfter("private fun startPlayback()")
            .substringBefore("private fun seek(")
        val updateCue = source.substringAfter("private fun updateCue(")
            .substringBefore("private fun applyCueDisplayAction(")

        assertTrue(startPlayback.contains("hasPlayedOnce = true"))
        assertTrue(startPlayback.contains("updateCue(currentTime, forceDisplay = true)"))
        assertTrue(updateCue.contains("forceDisplay: Boolean = false"))
        assertTrue(updateCue.contains("cueDisplay.update("))
        assertTrue(updateCue.contains("forceDisplay = forceDisplay"))
    }

    @Test
    fun autoScrollCrossChapterCueClearsDisplayedCueBeforeLoadingChapter() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlayer.kt").readText()
        val updateCue = source.substringAfter("private fun updateCue(")
            .substringBefore("private fun applyCueDisplayAction(")
        val applyAction = source.substringAfter("private fun applyCueDisplayAction(")
            .substringBefore("private fun savePlayback(")

        assertTrue(source.contains("private val cueDisplay = SasayakiCueDisplayCoordinator()"))
        assertFalse(source.contains("private var currentCue: SasayakiMatch? = null"))
        assertTrue(updateCue.contains("cueDisplay.update("))
        assertTrue(updateCue.contains("currentChapterIndex = getCurrentChapterIndex()"))
        assertTrue(updateCue.contains("autoScroll = autoScroll"))
        assertTrue(updateCue.contains("hasPlayedOnce = hasPlayedOnce"))
        assertTrue(applyAction.contains("SasayakiCueDisplayAction.Clear -> onClearCue()"))
        assertTrue(applyAction.contains("is SasayakiCueDisplayAction.Display -> onCue(action.cue, action.reveal)"))
        assertTrue(applyAction.contains("is SasayakiCueDisplayAction.ClearAndLoadChapter -> {"))
        assertTrue(applyAction.contains("onClearCue()"))
        assertTrue(applyAction.contains("onLoadChapter(action.chapterIndex)"))
        assertTrue(applyAction.indexOf("onClearCue()") < applyAction.indexOf("onLoadChapter(action.chapterIndex)"))
    }

    @Test
    fun playbackPositionPersistsOnDelayRateSeekAndWholeSecondTicks() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlayer.kt").readText()
        val setDelay = source.substringAfter("fun setDelay(value: Double)")
            .substringBefore("fun setRate(value: Float)")
        val setRate = source.substringAfter("fun setRate(value: Float)")
            .substringBefore("fun importAudio(")
        val complete = source.substringAfter("private fun handleSeekComplete(")
            .substringBefore("private fun restoreAudio()")
        val tick = source.substringAfter("private fun tick()")
            .substringBefore("private fun updateCue(")

        assertTrue(setDelay.contains("playback = playback.copy(delay = value)"))
        assertTrue(setDelay.contains("savePlayback()"))
        assertTrue(setRate.contains("playback = playback.copy(rate = value)"))
        assertTrue(setRate.contains("savePlayback()"))
        assertTrue(complete.contains("playback = playback.copy(lastPosition = seek.seconds)"))
        assertTrue(complete.contains("savePlayback()"))
        assertTrue(tick.contains("val tick = playbackState.updateTick("))
        assertTrue(tick.contains("currentPositionMs = engine.currentPositionMs"))
        assertTrue(tick.contains("durationMs = engine.durationMs"))
        assertTrue(tick.contains("if (tick.shouldSavePosition)"))
        assertTrue(tick.contains("playback = playback.copy(lastPosition = currentTime)"))
        assertTrue(tick.contains("savePlayback()"))
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
        assertTrue(source.contains("private var playbackEngine: SasayakiPlaybackEngine? = null"))
        assertTrue(restoreAudio.contains("AndroidSasayakiPlaybackEngine.prepare("))
        assertTrue(restoreAudio.contains("startPositionMs = (playback.lastPosition * 1000.0).toInt()"))
        assertTrue(restoreAudio.contains("onCompletion = {"))
        assertTrue(restoreAudio.contains("onSeekComplete = ::handleSeekComplete"))
        assertTrue(restoreAudio.contains("playbackEngine = engine"))
        assertTrue(tick.contains("val engine = playbackEngine ?: return"))
        assertTrue(tick.contains("currentPositionMs = engine.currentPositionMs"))
        assertTrue(tick.contains("durationMs = engine.durationMs"))
        assertTrue(teardown.contains("playbackEngine?.release()"))
        assertTrue(teardown.contains("playbackEngine = null"))
    }
}
