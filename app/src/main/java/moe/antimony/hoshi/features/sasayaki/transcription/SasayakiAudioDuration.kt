package moe.antimony.hoshi.features.sasayaki.transcription

import java.io.IOException
import kotlinx.coroutines.CancellationException

internal fun resolveSasayakiAudioDuration(
    extractorDuration: () -> Double?,
    containerDuration: () -> Double?,
    metadataRetrieverDuration: () -> Double?,
): Double {
    val failures = mutableListOf<Exception>()

    fun readDuration(probe: () -> Double?): Double? = try {
        probe()?.also { duration ->
            if (!duration.isFinite() || duration <= 0.0) {
                failures += IOException("Invalid audio duration")
            }
        }?.takeIf { it.isFinite() && it > 0.0 }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        failures += error
        null
    }

    readDuration(extractorDuration)?.let { return it }
    readDuration(containerDuration)?.let { return it }
    readDuration(metadataRetrieverDuration)?.let { return it }

    val failure = IOException("Audio duration is unavailable", failures.firstOrNull())
    failures.drop(1).forEach(failure::addSuppressed)
    throw failure
}
