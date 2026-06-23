package moe.antimony.hoshi.features.sasayaki

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText

class SasayakiAudioAvailabilityState(
    initialHasAudio: Boolean = false,
) {
    var errorMessage by mutableStateOf<UiText?>(null)
        private set

    var hasAudio by mutableStateOf(initialHasAudio)
        private set

    fun markRestoreFailed(error: Throwable) {
        errorMessage = error.localizedMessage?.let(UiText::Literal)
            ?: UiText.Resource(R.string.sasayaki_import_audiobook_failed)
        hasAudio = false
    }

    fun markRestoreSucceeded() {
        markAudioAvailable()
    }

    fun markAudioAvailable() {
        hasAudio = true
        errorMessage = null
    }

    fun markAudioCleared() {
        hasAudio = false
        errorMessage = null
    }

    fun markAudioUnavailable() {
        hasAudio = false
    }
}
