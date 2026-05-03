package moe.antimony.hoshi.features.sasayaki

import android.content.Context
import java.io.File

interface SasayakiMediaSessionHandle {
    fun activate()

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
    title: String,
    artworkFile: File?,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSkipToPrevious: () -> Unit,
    onSkipToNext: () -> Unit,
    onSeekTo: (Long) -> Unit,
) : SasayakiMediaSessionHandle {
    private val session = SasayakiMediaSession(
        context = context,
        title = title,
        artwork = SasayakiMediaSession.loadCoverArt(artworkFile),
        onPlay = onPlay,
        onPause = onPause,
        onSkipToPrevious = onSkipToPrevious,
        onSkipToNext = onSkipToNext,
        onSeekTo = onSeekTo,
    )

    override fun activate() {
        session.activate()
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
