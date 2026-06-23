package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiPlaybackData

import android.os.Handler
import android.os.Looper
import java.io.File

data class SasayakiAudioRestoreCallbacks(
    val onPrepared: (Int) -> Unit,
    val onCompletion: () -> Unit,
    val onSeekComplete: () -> Unit,
    val onPlaybackActiveChanged: (Boolean) -> Unit,
    val onPositionChanged: (Int, Int) -> Unit,
    val onError: (Throwable) -> Unit,
)

data class SasayakiAudioRestoreResult(
    val durationMs: Int,
)

data class SasayakiPreparedPlayback(
    val engine: SasayakiPlaybackEngine,
    val durationMs: Int,
)

fun interface SasayakiPlaybackPreparer {
    fun prepare(
        source: SasayakiPlaybackSource,
        startPositionMs: Int,
        title: String,
        artworkFile: File?,
        callbacks: SasayakiAudioRestoreCallbacks,
    ): SasayakiPreparedPlayback
}

internal class ServiceOwnedSasayakiPlaybackPreparer(
    private val playerProvider: () -> Media3SasayakiPlayerHandle,
) : SasayakiPlaybackPreparer {
    override fun prepare(
        source: SasayakiPlaybackSource,
        startPositionMs: Int,
        title: String,
        artworkFile: File?,
        callbacks: SasayakiAudioRestoreCallbacks,
    ): SasayakiPreparedPlayback {
        val player = playerProvider()
        val engine = Media3SasayakiPlaybackEngine.prepare(
            player = player,
            mediaItem = sasayakiMediaItem(
                source = source,
                title = title,
                artworkFile = artworkFile,
            ),
            startPositionMs = startPositionMs,
            onPrepared = callbacks.onPrepared,
            onCompletion = callbacks.onCompletion,
            onSeekComplete = callbacks.onSeekComplete,
            onPlaybackActiveChanged = callbacks.onPlaybackActiveChanged,
            onPositionChanged = callbacks.onPositionChanged,
            onError = callbacks.onError,
            postSeekCompletion = ::postOnMainThread,
            releasePlayer = ::releaseServiceOwnedPlayer,
        )
        return SasayakiPreparedPlayback(
            engine = engine,
            durationMs = engine.durationMs,
        )
    }

    private fun releaseServiceOwnedPlayer(player: Media3SasayakiPlayerHandle) {
        player.stop()
        player.clearMediaItems()
    }
}

class SasayakiAudioRestoreController(
    private val bookRoot: File,
    private val bookTitle: String?,
    private val bookCoverFile: File?,
    private val audioSourceRepository: SasayakiAudioRepository,
    private val playbackLifecycle: SasayakiPlaybackLifecycleController,
    private val playbackPreparer: SasayakiPlaybackPreparer,
) {
    fun restore(
        playback: SasayakiPlaybackData,
        callbacks: SasayakiAudioRestoreCallbacks,
    ): SasayakiAudioRestoreResult? {
        val source = audioSourceRepository.playbackSource(playback) ?: return null
        val prepared = playbackPreparer.prepare(
            source = source,
            startPositionMs = (playback.lastPosition * 1000.0).toInt(),
            title = bookTitle ?: bookRoot.name,
            artworkFile = bookCoverFile,
            callbacks = callbacks,
        )
        playbackLifecycle.attachEngine(prepared.engine)
        return SasayakiAudioRestoreResult(
            durationMs = prepared.durationMs,
        )
    }
}

private fun postOnMainThread(block: () -> Unit) {
    Handler(Looper.getMainLooper()).post(block)
}
