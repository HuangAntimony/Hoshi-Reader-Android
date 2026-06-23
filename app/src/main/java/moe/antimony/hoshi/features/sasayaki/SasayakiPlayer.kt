package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiPlaybackData
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.epub.SasayakiMatch

import android.net.Uri
import moe.antimony.hoshi.ui.UiText
import java.io.File

class SasayakiPlayer private constructor(
    private val controller: SasayakiPlaybackControllerContract,
    private val releaseController: () -> Unit = controller::release,
    private val stopPlaybackController: () -> Unit = controller::release,
) {
    internal constructor(
        bookId: String,
        bookRoot: File,
        playbackRepository: SasayakiPlaybackRepository,
        bookTitle: String?,
        bookCoverFile: File?,
        matchData: SasayakiMatchData?,
        initialPlayback: SasayakiPlaybackData?,
        getCurrentChapterIndex: () -> Int,
        onCue: (SasayakiMatch, Boolean) -> Unit,
        onClearCue: () -> Unit,
        onLoadChapter: (Int) -> Unit,
        playbackServiceRuntime: SasayakiPlaybackRuntime,
    ) : this(
        createControllerBundle(
            bookId = bookId,
            bookRoot = bookRoot,
            playbackRepository = playbackRepository,
            bookTitle = bookTitle,
            bookCoverFile = bookCoverFile,
            matchData = matchData,
            initialPlayback = initialPlayback,
            getCurrentChapterIndex = getCurrentChapterIndex,
            onCue = onCue,
            onClearCue = onClearCue,
            onLoadChapter = onLoadChapter,
            playbackServiceRuntime = playbackServiceRuntime,
        ),
    )

    private constructor(
        controllerBundle: SasayakiPlaybackControllerBundle,
    ) : this(
        controller = controllerBundle.controller,
        releaseController = controllerBundle.releaseController,
        stopPlaybackController = controllerBundle.stopPlaybackController,
    )

    val playback: SasayakiPlaybackData get() = controller.playback
    val currentTime: Double get() = controller.currentTime
    val duration: Double get() = controller.duration
    val isPlaying: Boolean get() = controller.isPlaying
    val errorMessage: UiText? get() = controller.errorMessage
    var autoScroll: Boolean
        get() = controller.autoScroll
        set(value) {
            controller.autoScroll = value
        }
    var readerSkipButtonAction: SasayakiReaderSkipButtonAction
        get() = controller.readerSkipButtonAction
        set(value) {
            controller.readerSkipButtonAction = value
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

    fun skipForward(seconds: Int) {
        controller.skipForward(seconds)
    }

    fun skipBackward(seconds: Int) {
        controller.skipBackward(seconds)
    }

    fun seekTo(seconds: Double) {
        controller.seekTo(seconds)
    }

    fun updateMatchData(matchData: SasayakiMatchData?) {
        controller.updateMatchData(matchData)
    }

    fun findCue(chapterIndex: Int, offset: Int): SasayakiMatch? =
        controller.findCue(chapterIndex = chapterIndex, offset = offset)

    fun playCue(cue: SasayakiMatch, stop: Boolean) {
        controller.playCue(cue = cue, stop = stop)
    }

    fun exportCueAudio(cue: SasayakiMatch, sentence: String): File? =
        controller.exportCueAudio(cue = cue, sentence = sentence)

    fun release() {
        releaseController()
    }

    fun stopPlayback() {
        stopPlaybackController()
    }

    private data class SasayakiPlaybackControllerBundle(
        val controller: SasayakiPlaybackControllerContract,
        val releaseController: () -> Unit,
        val stopPlaybackController: () -> Unit,
    )

    private companion object {
        fun createControllerBundle(
            bookId: String,
            bookRoot: File,
            playbackRepository: SasayakiPlaybackRepository,
            bookTitle: String?,
            bookCoverFile: File?,
            matchData: SasayakiMatchData?,
            initialPlayback: SasayakiPlaybackData?,
            getCurrentChapterIndex: () -> Int,
            onCue: (SasayakiMatch, Boolean) -> Unit,
            onClearCue: () -> Unit,
            onLoadChapter: (Int) -> Unit,
            playbackServiceRuntime: SasayakiPlaybackRuntime,
        ): SasayakiPlaybackControllerBundle {
            val controller = playbackServiceRuntime.load(
                request = SasayakiPlaybackRuntimeLoadRequest(
                    bookId = bookId,
                    bookRoot = bookRoot,
                    playbackRepository = playbackRepository,
                    bookTitle = bookTitle,
                    bookCoverFile = bookCoverFile,
                    matchData = matchData,
                    initialPlayback = initialPlayback,
                ),
                getCurrentChapterIndex = getCurrentChapterIndex,
                onCue = onCue,
                onClearCue = onClearCue,
                onLoadChapter = onLoadChapter,
            )
            return SasayakiPlaybackControllerBundle(
                controller = controller,
                releaseController = playbackServiceRuntime::detachReader,
                stopPlaybackController = playbackServiceRuntime::stopPlayback,
            )
        }
    }
}
