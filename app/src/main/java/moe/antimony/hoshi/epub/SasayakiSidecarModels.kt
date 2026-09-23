package moe.antimony.hoshi.epub

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SasayakiMatchSource {
    @SerialName("subtitles") Subtitles,
    @SerialName("transcription") Transcription,
}

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
    val source: SasayakiMatchSource = legacyMatchSource(matches),
)

private fun legacyMatchSource(matches: List<SasayakiMatch>): SasayakiMatchSource =
    // Older Android transcripts used chapter-offset IDs; SRT parsing uses numeric IDs.
    if (matches.isNotEmpty() && matches.all { it.id == "${it.chapterIndex}-${it.start}" }) {
        SasayakiMatchSource.Transcription
    } else {
        SasayakiMatchSource.Subtitles
    }

@Serializable
data class SasayakiPlaybackData(
    val lastPosition: Double,
    val delay: Double = 0.0,
    val rate: Float = 1f,
    val audioUri: String? = null,
    val audioFileName: String? = null,
)
