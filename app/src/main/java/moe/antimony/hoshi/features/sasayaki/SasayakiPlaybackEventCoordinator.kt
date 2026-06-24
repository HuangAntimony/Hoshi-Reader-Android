package moe.antimony.hoshi.features.sasayaki

class SasayakiPlaybackEventCoordinator(
    private val playbackState: SasayakiPlaybackStateCoordinator,
    private val playbackPersistence: SasayakiPlaybackPersistenceState,
    private val cueNavigation: SasayakiCueNavigationController,
    private val cueDisplay: SasayakiCueDisplayCoordinator,
) {
    fun handleSeekComplete(
        hasAudio: Boolean,
        hasMatch: Boolean,
        delay: Double,
        currentChapterIndex: Int,
        autoScroll: Boolean,
        hasPlayedOnce: Boolean,
        startPlayback: () -> Unit,
        applyCueDisplayAction: (SasayakiCueDisplayAction) -> Unit,
    ) {
        val seek = playbackState.completeSeek() ?: return
        if (seek.savePosition) {
            playbackPersistence.savePosition(seek.seconds)
        }
        if (seek.updateCue) {
            val shouldRevealCue = hasPlayedOnce || seek.revealCue
            updateCue(
                hasAudio = hasAudio,
                hasMatch = hasMatch,
                time = seek.seconds,
                delay = delay,
                currentChapterIndex = currentChapterIndex,
                autoScroll = autoScroll,
                hasPlayedOnce = shouldRevealCue,
                source = SasayakiCueRevealSource.DirectJump,
                forceDisplay = false,
                applyCueDisplayAction = applyCueDisplayAction,
            )
        }
        seek.displayCue?.let { cue ->
            applyCueDisplayAction(
                cueDisplay.displaySelectedCue(
                    cue = cue,
                    currentChapterIndex = currentChapterIndex,
                    reveal = autoScroll && (hasPlayedOnce || seek.startPlayback || seek.revealCue),
                ),
            )
        }
        if (seek.startPlayback) startPlayback()
    }

    fun updateCue(
        hasAudio: Boolean,
        hasMatch: Boolean,
        time: Double,
        delay: Double,
        currentChapterIndex: Int,
        autoScroll: Boolean,
        hasPlayedOnce: Boolean,
        source: SasayakiCueRevealSource = SasayakiCueRevealSource.DirectJump,
        forceDisplay: Boolean = false,
        applyCueDisplayAction: (SasayakiCueDisplayAction) -> Unit,
    ) {
        if (!hasAudio || !hasMatch) return
        val cue = cueNavigation.cueAtPlaybackTime(time = time, delay = delay)
        applyCueDisplayAction(
            cueDisplay.update(
                cue = cue,
                currentChapterIndex = currentChapterIndex,
                autoScroll = autoScroll,
                hasPlayedOnce = hasPlayedOnce,
                source = source,
                forceDisplay = forceDisplay,
            ),
        )
    }
}
