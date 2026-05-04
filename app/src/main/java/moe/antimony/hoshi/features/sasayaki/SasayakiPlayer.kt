package moe.antimony.hoshi.features.sasayaki

import android.content.Context
import android.net.Uri
import java.io.File

class SasayakiPlayer internal constructor(
    private val controller: SasayakiPlaybackControllerContract,
) {
    constructor(
        context: Context,
        bookRoot: File,
        playbackRepository: SasayakiPlaybackRepository,
        bookTitle: String?,
        bookCoverFile: File?,
        matchData: SasayakiMatchData?,
        getCurrentChapterIndex: () -> Int,
        onCue: (SasayakiMatch, Boolean) -> Unit,
        onClearCue: () -> Unit,
        onLoadChapter: (Int) -> Unit,
    ) : this(
        SasayakiPlaybackController(
            context = context,
            bookRoot = bookRoot,
            playbackRepository = playbackRepository,
            bookTitle = bookTitle,
            bookCoverFile = bookCoverFile,
            matchData = matchData,
            getCurrentChapterIndex = getCurrentChapterIndex,
            onCue = onCue,
            onClearCue = onClearCue,
            onLoadChapter = onLoadChapter,
        ),
    )

    val playback: SasayakiPlaybackData get() = controller.playback
    val currentTime: Double get() = controller.currentTime
    val duration: Double get() = controller.duration
    val isPlaying: Boolean get() = controller.isPlaying
    val errorMessage: String? get() = controller.errorMessage
    var autoScroll: Boolean
        get() = controller.autoScroll
        set(value) {
            controller.autoScroll = value
        }
    val hasAudio: Boolean get() = controller.hasAudio
    val hasMatch: Boolean get() = controller.hasMatch
    val delay: Double get() = controller.delay
    val rate: Float get() = controller.rate
    val audioStorageSummary: String get() = controller.audioStorageSummary

    fun setDelay(value: Double) {
        controller.setDelay(value)
    }

    fun setRate(value: Float) {
        controller.setRate(value)
    }

    fun importAudio(audioUri: Uri, copiedAudioFileName: String? = null) {
        controller.importAudio(audioUri = audioUri, copiedAudioFileName = copiedAudioFileName)
    }

    fun clearAudio() {
        controller.clearAudio()
    }

    fun togglePlayback() {
        controller.togglePlayback()
    }

    fun pausePlayback(restoreTemporaryPosition: Boolean = true) {
        controller.pausePlayback(restoreTemporaryPosition = restoreTemporaryPosition)
    }

    fun nextCue() {
        controller.nextCue()
    }

    fun previousCue() {
        controller.previousCue()
    }

    fun findCue(chapterIndex: Int, offset: Int): SasayakiMatch? =
        controller.findCue(chapterIndex = chapterIndex, offset = offset)

    fun playCue(cue: SasayakiMatch, stop: Boolean) {
        controller.playCue(cue = cue, stop = stop)
    }

    fun release() {
        controller.release()
    }
}
