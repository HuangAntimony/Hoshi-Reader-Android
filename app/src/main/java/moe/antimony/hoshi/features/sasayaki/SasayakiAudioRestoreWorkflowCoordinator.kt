package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiPlaybackData

class SasayakiAudioRestoreWorkflowCoordinator(
    private val audioRestore: SasayakiAudioRestoreController,
    private val audioRestoreCallbacks: SasayakiAudioRestoreCallbacksCoordinator,
    private val audioRestoreResult: SasayakiAudioRestoreResultCoordinator,
) {
    fun restore(
        playback: SasayakiPlaybackData,
        currentTime: Double,
        releaseExistingMediaSession: () -> Unit,
        updateMediaSession: () -> Unit,
        handleSeekComplete: () -> Unit,
        startPlayback: () -> Unit,
        pausePlayback: () -> Unit,
        previousCue: () -> Unit,
        nextCue: () -> Unit,
        isPlaying: () -> Boolean,
        updateCue: (Double) -> Unit,
    ) {
        val result = runCatching {
            audioRestore.restore(
                playback = playback,
                releaseExistingMediaSession = releaseExistingMediaSession,
                callbacks = audioRestoreCallbacks.build(
                    updateMediaSession = updateMediaSession,
                    handleSeekComplete = handleSeekComplete,
                    startPlayback = startPlayback,
                    pausePlayback = pausePlayback,
                    previousCue = previousCue,
                    nextCue = nextCue,
                    isPlaying = isPlaying,
                ),
            )
        }.onFailure(audioRestoreResult::handleFailure).getOrNull() ?: return
        audioRestoreResult.handleSuccess(
            result = result,
            currentTime = currentTime,
            updateCue = updateCue,
            updateMediaSession = updateMediaSession,
        )
    }
}
