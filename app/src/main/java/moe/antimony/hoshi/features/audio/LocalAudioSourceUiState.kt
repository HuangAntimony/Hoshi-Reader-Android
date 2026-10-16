package moe.antimony.hoshi.features.audio

import kotlinx.coroutines.CancellationException
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.withErrorDetails

internal data class LocalAudioSourceUiState(
    val config: LocalAudioSourceConfig? = null,
    val saveFailed: Boolean = false,
    val errorMessage: UiText? = null,
) {
    fun withSaveResult(result: Result<LocalAudioSourceConfig>): LocalAudioSourceUiState =
        result.fold(
            onSuccess = { copy(config = it, saveFailed = false, errorMessage = null) },
            onFailure = { error ->
                if (error is CancellationException) throw error
                copy(saveFailed = true, errorMessage = UiText.Resource(R.string.audio_local_source_save_failed).withErrorDetails(error))
            },
        )
}
