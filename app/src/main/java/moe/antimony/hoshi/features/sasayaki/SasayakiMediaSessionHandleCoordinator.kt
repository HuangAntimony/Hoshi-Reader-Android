package moe.antimony.hoshi.features.sasayaki

class SasayakiMediaSessionHandleCoordinator {
    private var mediaSession: SasayakiMediaSessionHandle? = null

    fun replace(handle: SasayakiMediaSessionHandle) {
        mediaSession = handle
    }

    fun activate() {
        mediaSession?.activate()
    }

    fun setSystemMediaControls(mode: SasayakiSystemMediaControlsMode) {
        mediaSession?.setSystemMediaControls(mode)
    }

    fun update(
        isPlaying: Boolean,
        currentTimeMs: Long,
        durationMs: Long,
        rate: Float,
    ) {
        mediaSession?.update(
            isPlaying = isPlaying,
            currentTimeMs = currentTimeMs,
            durationMs = durationMs,
            rate = rate,
        )
    }

    fun releaseExisting() {
        mediaSession?.release()
    }

    fun releaseAndClear() {
        mediaSession?.release()
        mediaSession = null
    }
}
