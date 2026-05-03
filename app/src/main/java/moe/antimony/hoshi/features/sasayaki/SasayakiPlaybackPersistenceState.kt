package moe.antimony.hoshi.features.sasayaki

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class SasayakiPlaybackPersistenceState(
    private val playbackRepository: SasayakiPlaybackRepository,
    private val audioSourceRepository: SasayakiAudioRepository,
) {
    var playback by mutableStateOf(playbackRepository.load() ?: SasayakiPlaybackData(lastPosition = 0.0))
        private set

    val delay: Double get() = playback.delay
    val rate: Float get() = playback.rate
    val audioStorageSummary: String
        get() = audioSourceRepository.storageSummary(playback)

    fun setDelay(value: Double) {
        playback = playback.copy(delay = value)
        save()
    }

    fun setRate(value: Float) {
        playback = playback.copy(rate = value)
        save()
    }

    fun importAudio(audioUri: Uri, copiedAudioFileName: String?) {
        playback = audioSourceRepository.importedPlayback(playback, audioUri, copiedAudioFileName)
        save()
    }

    fun clearAudioMetadata() {
        playback = playback.copy(
            lastPosition = 0.0,
            audioUri = null,
            audioFileName = null,
        )
        save()
    }

    fun savePosition(seconds: Double) {
        playback = playback.copy(lastPosition = seconds)
        save()
    }

    private fun save() {
        playbackRepository.save(playback)
    }
}
