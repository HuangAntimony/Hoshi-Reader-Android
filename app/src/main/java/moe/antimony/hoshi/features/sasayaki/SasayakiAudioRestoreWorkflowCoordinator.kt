package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiPlaybackData

class SasayakiAudioRestoreWorkflowCoordinator(
    private val audioRestore: SasayakiAudioRestoreController,
    private val audioRestoreCallbacks: SasayakiAudioRestoreCallbacksCoordinator,
    private val audioRestoreResult: SasayakiAudioRestoreResultCoordinator,
) {
    fun restore(
        playback: SasayakiPlaybackData,
        currentTime: () -> Double,
        updateMediaSession: () -> Unit,
        handleSeekComplete: () -> Unit,
        handlePlaybackActiveChanged: (Boolean) -> Unit,
        handlePositionChanged: (Int, Int) -> Unit,
        updateCue: (Double) -> Unit,
    ) {
        val result = runCatching {
            audioRestore.restore(
                playback = playback,
                callbacks = audioRestoreCallbacks.build(
                    updateMediaSession = updateMediaSession,
                    handlePrepared = { durationMs ->
                        audioRestoreResult.handlePrepared(
                            durationMs = durationMs,
                            currentTime = currentTime(),
                            updateCue = updateCue,
                            updateMediaSession = updateMediaSession,
                        )
                    },
                    handleSeekComplete = handleSeekComplete,
                    handlePlaybackActiveChanged = handlePlaybackActiveChanged,
                    handlePositionChanged = handlePositionChanged,
                    handleError = audioRestoreResult::handleFailure,
                ),
            )
        }.onFailure(audioRestoreResult::handleFailure).getOrNull() ?: return
        audioRestoreResult.handleSuccess(
            result = result,
            currentTime = currentTime(),
            updateCue = updateCue,
            updateMediaSession = updateMediaSession,
        )
    }
}
