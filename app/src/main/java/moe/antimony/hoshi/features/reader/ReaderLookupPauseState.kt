package moe.antimony.hoshi.features.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Shared lookup interruption policy for audiobook and TTS playback. */
internal class ReaderLookupPauseState {
    var resumeAction by mutableStateOf<(() -> Unit)?>(null)
        private set
    val pending: Boolean get() = resumeAction != null

    fun pauseIfPlaying(enabled: Boolean, isPlaying: Boolean, pause: () -> (() -> Unit)) {
        if (!enabled) {
            clear()
        } else if (!pending && isPlaying) {
            resumeAction = pause()
        }
    }

    fun resume() {
        val action = resumeAction
        clear()
        action?.invoke()
    }

    fun clear() { resumeAction = null }
}
