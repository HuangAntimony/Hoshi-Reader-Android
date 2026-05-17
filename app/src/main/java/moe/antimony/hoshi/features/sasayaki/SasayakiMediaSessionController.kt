package moe.antimony.hoshi.features.sasayaki

import android.content.Context
import androidx.media3.common.Player
import java.io.File

interface SasayakiMediaSessionHandle {
    fun activate()

    fun setSystemMediaControls(mode: SasayakiSystemMediaControlsMode)

    fun update(
        isPlaying: Boolean,
        currentTimeMs: Long,
        durationMs: Long,
        rate: Float,
    )

    fun release()
}

class AndroidSasayakiMediaSessionHandle(
    context: Context,
    player: Player,
    title: String,
    artworkFile: File?,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSkipToPrevious: () -> Unit,
    onSkipToNext: () -> Unit,
    onSeekTo: (Long) -> Unit,
    systemMediaControls: SasayakiSystemMediaControlsMode,
) : SasayakiMediaSessionHandle {
    private val session = SasayakiMediaSession(
        context = context,
        player = player,
        title = title,
        artwork = SasayakiMediaSession.loadCoverArt(artworkFile),
        onPlay = onPlay,
        onPause = onPause,
        onSkipToPrevious = onSkipToPrevious,
        onSkipToNext = onSkipToNext,
        onSeekTo = onSeekTo,
        systemMediaControls = systemMediaControls,
    )

    override fun activate() {
        session.activate()
    }

    override fun setSystemMediaControls(mode: SasayakiSystemMediaControlsMode) {
        session.setSystemMediaControls(mode)
    }

    override fun update(
        isPlaying: Boolean,
        currentTimeMs: Long,
        durationMs: Long,
        rate: Float,
    ) {
        session.update(
            isPlaying = isPlaying,
            currentTimeMs = currentTimeMs,
            durationMs = durationMs,
            rate = rate,
        )
    }

    override fun release() {
        session.release()
    }
}
