package moe.antimony.hoshi.features.sasayaki

import android.util.Log
import kotlinx.coroutines.CancellationException
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.withErrorDetails

internal enum class SasayakiFailureKind {
    AudioSource,
    ModelResources,
    NakuruUnavailable,
    Recognition,
    BookMatch,
    Storage,
    Unknown,
}

/** Carries an operation category across repository and coordinator boundaries. */
internal class SasayakiOperationFailure(
    val kind: SasayakiFailureKind,
    cause: Throwable,
) : Exception(cause.localizedMessage, cause)

internal fun Throwable.asSasayakiFailure(kind: SasayakiFailureKind): SasayakiOperationFailure {
    if (this is CancellationException) throw this
    return this as? SasayakiOperationFailure ?: SasayakiOperationFailure(kind, this)
}

internal fun sasayakiFailureText(kind: SasayakiFailureKind): UiText.Resource = UiText.Resource(
    when (kind) {
        SasayakiFailureKind.AudioSource -> R.string.sasayaki_transcription_error_audio
        SasayakiFailureKind.NakuruUnavailable -> R.string.sasayaki_nakuru_unavailable
        SasayakiFailureKind.ModelResources -> R.string.sasayaki_transcription_error_resources
        SasayakiFailureKind.Recognition -> R.string.sasayaki_transcription_error_recognition
        SasayakiFailureKind.BookMatch -> R.string.sasayaki_transcription_error_match
        SasayakiFailureKind.Storage -> R.string.sasayaki_transcription_error_storage
        SasayakiFailureKind.Unknown -> R.string.sasayaki_transcription_error_unknown
    },
)

internal fun Throwable.toSasayakiFailureText(fallback: SasayakiFailureKind): UiText {
    val failure = this as? SasayakiOperationFailure
    val kind = failure?.kind ?: fallback
    val original = failure?.cause ?: this
    return sasayakiFailureText(kind).withErrorDetails(original)
}

internal fun reportSasayakiFailure(
    stage: String,
    error: Throwable,
    fallback: SasayakiFailureKind,
): UiText {
    val kind = (error as? SasayakiOperationFailure)?.kind ?: fallback
    runCatching { Log.e(LOG_TAG, "stage=$stage category=${kind.name}", error) }
    return error.toSasayakiFailureText(fallback)
}

private const val LOG_TAG = "HoshiSasayakiTranscription"
