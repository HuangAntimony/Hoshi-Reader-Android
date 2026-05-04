package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiPlaybackData

import android.content.ContentResolver
import android.net.Uri

class SasayakiAudioCommandCoordinator(
    private val audioSourceRepository: SasayakiAudioRepository,
    private val playbackPersistence: SasayakiPlaybackPersistenceState,
    private val playbackState: SasayakiPlaybackStateCoordinator,
    private val audioAvailability: SasayakiAudioAvailabilityState,
    private val contentResolver: ContentResolver,
) {
    fun importAudio(
        audioUri: Uri,
        copiedAudioFileName: String?,
        teardownPlayer: (clearCue: Boolean) -> Unit,
        restoreAudio: () -> Unit,
    ) {
        teardownPlayer(false)
        playbackPersistence.importAudio(audioUri, copiedAudioFileName)
        restoreAudio()
    }

    fun clearAudio(
        playback: SasayakiPlaybackData,
        teardownPlayer: (clearCue: Boolean) -> Unit,
    ) {
        audioSourceRepository.clearAudioSource(playback, contentResolver)
        teardownPlayer(true)
        playbackPersistence.clearAudioMetadata()
        playbackState.clearAudioState()
        audioAvailability.markAudioCleared()
    }
}
