package moe.antimony.hoshi.features.sasayaki

class SasayakiTemporaryPlaybackRestoreCoordinator(
    private val playbackState: SasayakiPlaybackStateCoordinator,
    private val playbackLifecycle: SasayakiPlaybackLifecycleController,
) {
    fun restoreIfNeeded(updateCue: (Double) -> Unit) {
        val returnPosition = playbackState.restoreTemporaryPlaybackPositionIfNeeded() ?: return
        playbackLifecycle.seekTo((returnPosition * 1000.0).toInt())
        updateCue(returnPosition)
    }
}
