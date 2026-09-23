package moe.antimony.hoshi.features.sasayaki

import kotlinx.serialization.Serializable
import moe.antimony.hoshi.epub.SasayakiMatchData

data class SasayakiCue(
    val id: String,
    val startTime: Double,
    val endTime: Double,
    val text: String,
)

@Serializable
data class SasayakiCueRange(
    val id: String,
    val start: Int,
    val length: Int,
)

internal fun SasayakiMatchData.characterCoverageText(characterCount: Int): String {
    val matched = matches.sumOf { it.length.toLong() }
    val percentage = if (characterCount > 0) matched.toDouble() / characterCount * 100.0 else 0.0
    return "$matched/$characterCount (${String.format(java.util.Locale.US, "%.1f%%", percentage)})"
}
