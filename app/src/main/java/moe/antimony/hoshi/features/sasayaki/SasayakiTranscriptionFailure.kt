package moe.antimony.hoshi.features.sasayaki

import android.util.Log
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

/** Carries an operation category across repository and coordinator boundaries. */
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
        SasayakiFailureKind.Unknown -> error("Unknown Sasayaki failures require the original exception")
    },
)

internal fun Throwable.toSasayakiFailureText(fallback: SasayakiFailureKind): UiText {
    val failure = this as? SasayakiOperationFailure
    val kind = failure?.kind ?: fallback
    if (kind != SasayakiFailureKind.Unknown) return sasayakiFailureText(kind)

    val original = if (failure?.kind == SasayakiFailureKind.Unknown) failure.cause ?: failure else this
    val details = buildString {
        var current: Throwable? = original
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            val cause = requireNotNull(current)
            if (isNotEmpty()) append("\nCaused by: ")
            append(cause)
            current = cause.cause
            depth++
        }
        if (current != null) append("\nCaused by: …")
    }
    return UiText.Literal(details.take(MAX_ERROR_LENGTH).let { text ->
        if (details.length > MAX_ERROR_LENGTH) "$text…" else text
    })
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
private const val MAX_CAUSE_DEPTH = 8
private const val MAX_ERROR_LENGTH = 2000
