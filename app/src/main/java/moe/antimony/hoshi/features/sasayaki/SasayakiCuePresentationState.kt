package moe.antimony.hoshi.features.sasayaki

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class SasayakiCuePresentationState {
    var autoScroll by mutableStateOf(true)

    var hasPlayedOnce by mutableStateOf(false)
        private set

    fun markPlayedOnce() {
        hasPlayedOnce = true
    }
}
