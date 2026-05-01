package moe.antimony.hoshi.features.sasayaki

import kotlinx.serialization.Serializable

data class SasayakiCue(
    val id: String,
    val startTime: Double,
    val endTime: Double,
    val text: String,
)

@Serializable
data class SasayakiMatch(
    val id: String,
    val startTime: Double,
    val endTime: Double,
    val text: String,
    val chapterIndex: Int,
    val start: Int,
    val length: Int,
)

@Serializable
data class SasayakiCueRange(
    val id: String,
    val start: Int,
    val length: Int,
)

@Serializable
data class SasayakiMatchData(
    val matches: List<SasayakiMatch>,
    val unmatched: Int,
)

@Serializable
data class SasayakiPlaybackData(
    val lastPosition: Double,
    val delay: Double = 0.0,
    val rate: Float = 1f,
    val audioUri: String? = null,
    val audioFileName: String? = null,
)

internal fun SasayakiMatchData.matchRateText(): String {
    val matched = matches.size
    val total = matched + unmatched
    val percentage = if (total > 0) matched.toDouble() / total.toDouble() * 100.0 else 0.0
    return "$matched/$total (${String.format(java.util.Locale.US, "%.1f%%", percentage)})"
}
