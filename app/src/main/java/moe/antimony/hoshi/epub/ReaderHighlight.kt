package moe.antimony.hoshi.epub

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class HighlightColor(
    val red: Int,
    val green: Int,
    val blue: Int,
    val alpha: Double,
) {
    @SerialName("yellow")
    Yellow(239, 209, 56, 0.35),

    @SerialName("green")
    Green(152, 220, 129, 0.35),

    @SerialName("blue")
    Blue(149, 185, 255, 0.35),

    @SerialName("pink")
    Pink(255, 155, 180, 0.35),

    @SerialName("purple")
    Purple(197, 175, 251, 0.35);

    val rawValue: String
        get() = name.lowercase()

    val cssBackground: String
        get() = "rgba($red, $green, $blue, $alpha)"

    val argb: Long
        get() {
            val alphaByte = (alpha * 255).toLong().coerceIn(0, 255)
            return (alphaByte shl 24) or (red.toLong() shl 16) or (green.toLong() shl 8) or blue.toLong()
        }

    val swatchArgb: Long
        get() = (0xFFL shl 24) or (red.toLong() shl 16) or (green.toLong() shl 8) or blue.toLong()
}

@Serializable
data class ReaderHighlight(
    val id: String,
    val character: Int,
    val offset: Int,
    val text: String,
    val color: HighlightColor,
    val createdAt: Double,
)
