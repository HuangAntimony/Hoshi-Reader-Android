package moe.antimony.hoshi.features.sasayaki

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class SasayakiPlaybackService : MediaSessionService() {
    @Inject internal lateinit var runtime: SasayakiPlaybackServiceRuntime

    override fun onCreate() {
        super.onCreate()
        addSession(runtime.createSession())
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        runtime.currentSession()

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (runtime.currentSession()?.player?.hasForegroundPlayback() != true) {
            super.onTaskRemoved(rootIntent)
        }
    }

    override fun onDestroy() {
        runtime.release()
        super.onDestroy()
    }

    companion object {
        internal const val SessionId = "hoshi-sasayaki-playback"
    }
}

private fun Player.hasForegroundPlayback(): Boolean =
    isPlaying ||
        (
            playWhenReady &&
                (
                    playbackState == Player.STATE_READY ||
                        playbackState == Player.STATE_BUFFERING
                )
        )
