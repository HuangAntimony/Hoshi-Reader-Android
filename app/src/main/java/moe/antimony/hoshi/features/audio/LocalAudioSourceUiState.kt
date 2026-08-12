package moe.antimony.hoshi.features.audio

internal data class LocalAudioSourceUiState(
    val config: LocalAudioSourceConfig? = null,
    val saveFailed: Boolean = false,
) {
    fun withSaveResult(result: Result<LocalAudioSourceConfig>): LocalAudioSourceUiState =
        result.fold(
            onSuccess = { copy(config = it, saveFailed = false) },
            onFailure = { copy(saveFailed = true) },
        )
}
