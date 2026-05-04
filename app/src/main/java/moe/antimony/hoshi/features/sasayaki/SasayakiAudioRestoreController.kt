package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiPlaybackData

import android.content.Context
import java.io.File

data class SasayakiAudioRestoreCallbacks(
    val onCompletion: () -> Unit,
    val onSeekComplete: () -> Unit,
    val onPlay: () -> Unit,
    val onPause: () -> Unit,
    val onSkipToPrevious: () -> Unit,
    val onSkipToNext: () -> Unit,
    val onSeekTo: (Long) -> Unit,
)

data class SasayakiAudioRestoreResult(
    val mediaSession: SasayakiMediaSessionHandle,
    val durationMs: Int,
)

class SasayakiAudioRestoreController(
    context: Context,
    private val bookRoot: File,
    private val bookTitle: String?,
    private val bookCoverFile: File?,
    private val audioSourceRepository: SasayakiAudioRepository,
    private val playbackLifecycle: SasayakiPlaybackLifecycleController,
) {
    private val appContext = context.applicationContext

    fun restore(
        playback: SasayakiPlaybackData,
        releaseExistingMediaSession: () -> Unit,
        callbacks: SasayakiAudioRestoreCallbacks,
    ): SasayakiAudioRestoreResult? {
        val source = audioSourceRepository.playbackSource(playback) ?: return null
        val engine = AndroidSasayakiPlaybackEngine.prepare(
            context = appContext,
            source = source,
            startPositionMs = (playback.lastPosition * 1000.0).toInt(),
            onCompletion = callbacks.onCompletion,
            onSeekComplete = callbacks.onSeekComplete,
        )
        playbackLifecycle.attachEngine(engine)
        releaseExistingMediaSession()
        return SasayakiAudioRestoreResult(
            mediaSession = AndroidSasayakiMediaSessionHandle(
                context = appContext,
                title = bookTitle ?: bookRoot.name,
                artworkFile = bookCoverFile,
                onPlay = callbacks.onPlay,
                onPause = callbacks.onPause,
                onSkipToPrevious = callbacks.onSkipToPrevious,
                onSkipToNext = callbacks.onSkipToNext,
                onSeekTo = callbacks.onSeekTo,
            ),
            durationMs = engine.durationMs,
        )
    }
}
