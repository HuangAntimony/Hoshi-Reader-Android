package moe.antimony.hoshi.features.sasayaki

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class SasayakiAudioAvailabilityState {
    var errorMessage by mutableStateOf<String?>(null)
        private set

    var hasAudio by mutableStateOf(false)
        private set

    fun markRestoreFailed(error: Throwable) {
        errorMessage = error.localizedMessage ?: "Unable to load audiobook."
        hasAudio = false
    }

    fun markRestoreSucceeded() {
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
