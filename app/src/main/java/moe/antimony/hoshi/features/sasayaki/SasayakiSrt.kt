package moe.antimony.hoshi.features.sasayaki

import java.util.Locale
import kotlin.math.roundToLong
import moe.antimony.hoshi.epub.SasayakiMatchData

/** Exports the current matched cues, independently of their original source. */
internal object SasayakiSrt {
    const val mimeType = "application/x-subrip"

    fun encode(data: SasayakiMatchData): String {
        var count = 0
        val result = buildString {
            data.matches.sortedBy { it.startTime }.forEach { cue ->
                if (!cue.startTime.isFinite() || !cue.endTime.isFinite() ||
                    cue.startTime < 0 || cue.endTime <= cue.startTime || cue.endTime >= Long.MAX_VALUE / 1000.0) return@forEach
                val text = cue.text.lineSequence().filter(String::isNotBlank).joinToString("\n")
                if (text.isEmpty()) return@forEach
                val start = (cue.startTime * 1000).roundToLong()
                val end = (cue.endTime * 1000).roundToLong().coerceAtLeast(start + 1)
                append(++count).append('\n')
                append(timestamp(start)).append(" --> ").append(timestamp(end)).append('\n')
                append(text).append("\n\n")
            }
        }
        require(count > 0) { "No valid matched subtitles to export" }
        return result
    }

    fun fileName(title: String): String {
        val safe = title.replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]"), "_").trim().trim('.')
        // Bound UTF-8 bytes, including supplementary characters, below filesystem limits.
        val base = buildString {
            var bytes = 0
            for (point in safe.codePoints().toArray()) {
                val size = String(Character.toChars(point)).toByteArray(Charsets.UTF_8).size
                if (bytes + size > 180) break
                appendCodePoint(point)
                bytes += size
            }
        }.ifBlank { "subtitles" }
        return "$base.srt"
    }

    private fun timestamp(milliseconds: Long): String = String.format(
        Locale.ROOT, "%02d:%02d:%02d,%03d", milliseconds / 3_600_000,
        milliseconds / 60_000 % 60, milliseconds / 1000 % 60, milliseconds % 1000,
    )
}
