package moe.antimony.hoshi.features.sasayaki

import kotlinx.serialization.Serializable

@Serializable
data class SasayakiToken(val text: String, val start: Double, val end: Double)

@Serializable
data class SasayakiTranscript(
    val through: Double,
    val duration: Double,
    val tokens: List<SasayakiToken>,
    val source: String? = null,
) {
    // Android commits fully processed audio and marks EOF with the exact duration.
    // Legacy iOS files lack source and can stop at the final recognition timestamp.
    val isComplete: Boolean get() = duration > 0 && through > 0 &&
        if (source == null) through + 1.5 >= duration else through >= duration
}
