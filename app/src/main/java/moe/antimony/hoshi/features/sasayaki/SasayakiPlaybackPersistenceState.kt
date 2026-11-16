package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiPlaybackData

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.SasayakiVolumeGainRange
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.withErrorDetails

class SasayakiPlaybackPersistenceState(
    private val playbackRepository: SasayakiPlaybackRepository,
    private val audioSourceRepository: SasayakiAudioRepository,
    initialPlayback: SasayakiPlaybackData?,
    private val persistenceScope: CoroutineScope,
    private val persistenceDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onVolumeGainChanged: (Int) -> Unit = {},
) {
    var playback by mutableStateOf(initialPlayback?.let {
        it.copy(volumeGainDb = it.volumeGainDb.coerceIn(SasayakiVolumeGainRange))
    } ?: SasayakiPlaybackData(lastPosition = 0.0))
        private set

    private var saveJob: Job? = null
    private val saveLock = Any()
    private var pendingSave: SasayakiPlaybackData? = null
    private var saveWorkerRunning = false

    private var confirmedVolumeGainDb = playback.volumeGainDb
    var volumeGainSaveError by mutableStateOf<UiText?>(null)
        private set
    private var volumeGainSaveJob: Job? = null

    val audioStorageSummary: String
        get() = audioSourceRepository.storageSummary(playback)

    suspend fun flush() {
        saveJob?.join()
        volumeGainSaveJob?.join()
    }

    fun applySyncedPlayback(value: SasayakiPlaybackData) {
        playback = value.copy(volumeGainDb = playback.volumeGainDb)
    }

    fun setDelay(value: Double) {
        if (value == playback.delay) return
        playback = playback.copy(delay = value)
        save()
    }

    fun setRate(value: Float) {
        if (value == playback.rate) return
        playback = playback.copy(rate = value)
        save()
    }

    fun setVolumeGainDb(value: Int) {
        val gain = value.coerceIn(SasayakiVolumeGainRange)
        if (gain == playback.volumeGainDb) return
        volumeGainSaveError = null
        playback = playback.copy(volumeGainDb = gain)
        onVolumeGainChanged(gain)
    }

    fun saveVolumeGain() {
        val gain = playback.volumeGainDb
        volumeGainSaveError = null
        volumeGainSaveJob = persistenceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                playbackRepository.saveVolumeGainDb(gain)
                confirmedVolumeGainDb = gain
            } catch (error: Exception) {
                val message = UiText.Resource(R.string.sasayaki_volume_gain_save_failed).withErrorDetails(error)
                if (playback.volumeGainDb == gain) {
                    setVolumeGainDb(confirmedVolumeGainDb)
                    volumeGainSaveError = message
                }
            }
        }
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
        var shouldStartWorker = false
        synchronized(saveLock) {
            pendingSave = playback
            if (!saveWorkerRunning) {
                saveWorkerRunning = true
                shouldStartWorker = true
            }
        }
        if (shouldStartWorker) {
            saveJob = persistenceScope.launch(start = CoroutineStart.UNDISPATCHED) {
                drainSaves()
            }
        }
    }

    private suspend fun drainSaves() {
        try {
            while (true) {
                val snapshot = synchronized(saveLock) {
                    pendingSave.also {
                        pendingSave = null
                    }
                }
                if (snapshot == null) {
                    val shouldContinue = synchronized(saveLock) {
                        if (pendingSave == null) {
                            saveWorkerRunning = false
                            false
                        } else {
                            true
                        }
                    }
                    if (!shouldContinue) return
                    continue
                }
                withContext(NonCancellable + persistenceDispatcher) {
                    playbackRepository.save(snapshot)
                }
            }
        } catch (error: Throwable) {
            synchronized(saveLock) {
                saveWorkerRunning = false
            }
            throw error
        }
    }
}
