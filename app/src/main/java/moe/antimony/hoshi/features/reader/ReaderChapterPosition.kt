package moe.antimony.hoshi.features.reader

import org.json.JSONObject

internal data class ReaderChapterPosition(
    val index: Int,
    val progress: Double = 0.0,
) {
    fun nextOrNull(lastIndex: Int): ReaderChapterPosition? =
        if (index < lastIndex) copy(index = index + 1, progress = 0.0) else null

    fun previousOrNull(): ReaderChapterPosition? =
        if (index > 0) copy(index = index - 1, progress = 1.0) else null

    fun withProgress(progress: Double): ReaderChapterPosition =
        copy(progress = progress.coerceIn(0.0, 1.0))
}

internal fun continuousPositionResult(result: String?, chapterCount: Int): ReaderChapterPosition? {
    val json = result?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
    val index = json.optInt("chapterIndex", -1)
    val progress = json.optDouble("progress")
    if (index !in 0 until chapterCount || !progress.isFinite()) return null
    return ReaderChapterPosition(index, progress.coerceIn(0.0, 1.0))
}
