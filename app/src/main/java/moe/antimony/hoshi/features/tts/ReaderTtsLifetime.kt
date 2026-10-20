package moe.antimony.hoshi.features.tts

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Reader route ownership survives configuration recreation, but ends on explicit route removal. */
@HiltViewModel
class ReaderTtsLifetime @Inject constructor(private val playback: TtsPlaybackController) : ViewModel() {
    var bookId: String? = null
    override fun onCleared() {
        if (bookId == playback.state.value.bookId) playback.stop()
    }
}
