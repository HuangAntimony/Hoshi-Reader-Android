package moe.antimony.hoshi.features.sasayaki

import java.nio.charset.StandardCharsets

object SasayakiParser {
    fun parseCues(data: ByteArray): List<SasayakiCue> =
        String(data, StandardCharsets.UTF_8)
            .replace("\r\n", "\n")
            .split("\n\n")
            .mapIndexedNotNull { index, block ->
                val lines = block.lines()
                if (lines.size < 3 || !lines[1].contains("-->")) return@mapIndexedNotNull null
                val times = lines[1].split("-->", limit = 2)
                if (times.size != 2) return@mapIndexedNotNull null
                SasayakiCue(
                    id = index.toString(),
                    startTime = parseTimestamp(times[0]),
                    endTime = parseTimestamp(times[1]),
                    text = lines[2].trim(),
                )
            }

    private fun parseTimestamp(timestamp: String): Double {
        val parts = timestamp.trim().replace(',', '.').split(':')
        require(parts.size == 3) { "Invalid SRT timestamp: $timestamp" }
        return parts[0].toDouble() * 3600.0 + parts[1].toDouble() * 60.0 + parts[2].toDouble()
    }
}
