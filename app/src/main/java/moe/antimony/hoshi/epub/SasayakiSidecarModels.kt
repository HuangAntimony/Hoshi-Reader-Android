package moe.antimony.hoshi.epub

import kotlinx.serialization.Serializable

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
