package moe.antimony.hoshi.features.sasayaki

class SasayakiPlaybackTickCoordinator(
    private val playbackEvents: SasayakiPlaybackEventCoordinator,
    private val cuePresentation: SasayakiCuePresentationState,
    private val getCurrentChapterIndex: () -> Int,
) {
    fun tick(
        hasAudio: Boolean,
        hasMatch: Boolean,
        delay: Double,
        pausePlayback: () -> Unit,
        updateMediaSession: () -> Unit,
        applyCueDisplayAction: (SasayakiCueDisplayAction) -> Unit,
    ) {
        playbackEvents.tick(
            hasAudio = hasAudio,
            hasMatch = hasMatch,
            delay = delay,
            currentChapterIndex = getCurrentChapterIndex(),
            autoScroll = cuePresentation.autoScroll,
            hasPlayedOnce = cuePresentation.hasPlayedOnce,
            pausePlayback = pausePlayback,
            updateMediaSession = updateMediaSession,
            applyCueDisplayAction = applyCueDisplayAction,
        )
    }
}
