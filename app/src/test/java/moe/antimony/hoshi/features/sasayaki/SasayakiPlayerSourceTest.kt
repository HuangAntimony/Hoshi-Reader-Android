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
        assertTrue(importAudio.contains("if (copiedAudioFileName == null)"))
        assertTrue(importAudio.contains("audioUri.toString()"))
        assertTrue(importAudio.contains("audioFileName = copiedAudioFileName"))
        assertTrue(restoreAudio.contains("Uri.parse("))
        assertTrue(restoreAudio.contains("setDataSource(appContext, uri)"))
        assertTrue(restoreAudio.contains("setDataSource(requireNotNull(file).absolutePath)"))
    }

    @Test
    fun audioCanBeClearedAndDescribesStorageMode() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlayer.kt").readText()
        val clearAudio = source.substringAfter("fun clearAudio()")
            .substringBefore("fun togglePlayback()")

        assertTrue(source.contains("val audioStorageSummary: String"))
        assertTrue(source.contains("playback.audioFileName != null"))
        assertTrue(source.contains("playback.audioUri != null"))
        assertTrue(clearAudio.contains("audioRepository.deleteAudio(playback)"))
        assertTrue(clearAudio.contains("releasePersistableUriPermission"))
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

        assertFalse(restoreAudio.contains("playbackParams = playbackParams(rate)"))
        assertTrue(setRate.contains("if (isPlaying)"))
        assertTrue(setRate.contains("mediaPlayer?.playbackParams = playbackParams(value)"))
        assertTrue(startPlayback.contains("player.playbackParams = playbackParams(rate)"))
        assertTrue(startPlayback.contains("player.start()"))
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

        assertTrue(source.contains("private var stopPlaybackTime: Double? = null"))
        assertTrue(source.contains("private var temporaryPlaybackReturnPosition: Double? = null"))
        assertTrue(source.contains("fun findCue(chapterIndex: Int, offset: Int): SasayakiMatch?"))
        assertTrue(playCue.contains("stopPlaybackTime = null"))
        assertTrue(playCue.contains("if (isPlaying) pausePlayback(restoreTemporaryPosition = false)"))
        assertTrue(playCue.contains("temporaryPlaybackReturnPosition = if (stop) playback.lastPosition else null"))
        assertTrue(playCue.contains("cue.startTime + delay"))
        assertTrue(playCue.contains("stopPlaybackTime = if (stop) cue.endTime + delay else null"))
        assertTrue(playCue.contains("savePosition = !stop"))
        assertTrue(seek.contains("savePosition: Boolean = true"))
        assertTrue(source.contains("if (seek.savePosition)"))
        assertTrue(tick.contains("stopPlaybackTime?.let"))
        assertTrue(tick.contains("pausePlayback()"))
        assertTrue(tick.substringBefore("stopPlaybackTime?.let").contains("if (temporaryPlaybackReturnPosition == null && second != lastSavedSecond)"))
        assertTrue(source.contains("private fun restoreTemporaryPlaybackPositionIfNeeded()"))
        assertTrue(source.contains("temporaryPlaybackReturnPosition = null"))
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

        assertTrue(source.contains("private data class PendingSeek"))
        assertTrue(source.contains("private var pendingSeek: PendingSeek? = null"))
        assertTrue(restoreAudio.contains("setOnSeekCompleteListener { handleSeekComplete() }"))
        assertTrue(seek.contains("pendingSeek = PendingSeek("))
        assertTrue(seek.contains("handler.removeCallbacks(tickRunnable)"))
        assertFalse(seek.substringBefore("private fun handleSeekComplete(").contains("if (updateCue) updateCue(seconds)"))
        assertFalse(seek.substringBefore("private fun handleSeekComplete(").contains("if (startPlayback) startPlayback()"))
        assertTrue(complete.contains("val seek = pendingSeek ?: return"))
        assertTrue(complete.contains("pendingSeek = null"))
        assertTrue(complete.contains("currentTime = seek.seconds"))
        assertTrue(complete.contains("if (seek.updateCue) updateCue(seek.seconds)"))
        assertTrue(complete.contains("if (seek.startPlayback) startPlayback()"))
        assertTrue(tick.contains("if (pendingSeek != null) return"))
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

        assertTrue(source.contains("val displayCue: SasayakiMatch? = null"))
        assertTrue(playCue.contains("displayCue = cue"))
        assertTrue(seek.contains("displayCue = displayCue"))
        assertTrue(source.contains("private fun displayCue(cue: SasayakiMatch, reveal: Boolean)"))
        assertTrue(complete.contains("seek.displayCue?.let { cue ->"))
        assertTrue(complete.contains("displayCue(cue, reveal = autoScroll && (hasPlayedOnce || seek.startPlayback))"))
        assertTrue(complete.indexOf("seek.displayCue?.let { cue ->") < complete.indexOf("if (seek.startPlayback) startPlayback()"))
    }

    @Test
    fun startPlaybackRedisplaysCurrentCueLikeIos() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlayer.kt").readText()
        val startPlayback = source.substringAfter("private fun startPlayback()")
            .substringBefore("private fun seek(")
        val updateCue = source.substringAfter("private fun updateCue(")
            .substringBefore("private fun displayCue(")

        assertTrue(startPlayback.contains("hasPlayedOnce = true"))
        assertTrue(startPlayback.contains("updateCue(currentTime, forceDisplay = true)"))
        assertTrue(updateCue.contains("forceDisplay: Boolean = false"))
        assertTrue(updateCue.contains("if (!forceDisplay && cue.id == currentCue?.id) return"))
    }
}
