package moe.antimony.hoshi.features.tts

import kotlinx.coroutines.CancellationException
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.withErrorDetails

/** Retains the recovery category together with the diagnostic shown to the user. */
data class TtsError(val failure: TtsFailure, val message: UiText)

internal fun TtsFailure.toError(error: Throwable? = null): TtsError {
    val summary = UiText.Resource(messageRes())
    val diagnostic = when {
        error is kotlinx.coroutines.TimeoutCancellationException -> java.util.concurrent.TimeoutException(error.localizedMessage).apply {
            error.cause?.let(::initCause)
        }
        error is TtsException && error.message.isNullOrBlank() -> error.cause
        else -> error
    }
    return TtsError(this, diagnostic?.let(summary::withErrorDetails) ?: summary)
}

internal fun Throwable.toTtsError(fallback: TtsFailure): TtsError {
    if (this is CancellationException && this !is kotlinx.coroutines.TimeoutCancellationException) throw this
    return ((this as? TtsException)?.failure ?: fallback).toError(this)
}
