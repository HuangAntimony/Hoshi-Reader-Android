package moe.antimony.hoshi.features.sasayaki

import kotlinx.coroutines.CancellationException
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText

internal enum class SasayakiFailureKind {
    AudioSource,
    ModelResources,
    Recognition,
    BookMatch,
    Storage,
    Unknown,
}

/** Carries an operation category across repository and coordinator boundaries without exposing details in the UI. */
internal class SasayakiOperationFailure(
    val kind: SasayakiFailureKind,
    cause: Throwable,
) : Exception("Sasayaki operation failed: ${kind.name}", cause)

internal fun Throwable.asSasayakiFailure(kind: SasayakiFailureKind): SasayakiOperationFailure {
    if (this is CancellationException) throw this
    return this as? SasayakiOperationFailure ?: SasayakiOperationFailure(kind, this)
}

internal fun sasayakiFailureText(kind: SasayakiFailureKind): UiText.Resource = UiText.Resource(
    when (kind) {
        SasayakiFailureKind.AudioSource -> R.string.sasayaki_transcription_error_audio
        SasayakiFailureKind.ModelResources -> R.string.sasayaki_transcription_error_resources
        SasayakiFailureKind.Recognition -> R.string.sasayaki_transcription_error_recognition
        SasayakiFailureKind.BookMatch -> R.string.sasayaki_transcription_error_match
        SasayakiFailureKind.Storage -> R.string.sasayaki_transcription_error_storage
        SasayakiFailureKind.Unknown -> R.string.sasayaki_transcription_failed
    },
)

internal fun Throwable.toSasayakiFailureText(fallback: SasayakiFailureKind): UiText.Resource =
    sasayakiFailureText((this as? SasayakiOperationFailure)?.kind ?: fallback)
