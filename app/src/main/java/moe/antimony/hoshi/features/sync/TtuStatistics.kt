package moe.antimony.hoshi.features.sync

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.math.roundToLong
import moe.antimony.hoshi.epub.ReadingSession
import moe.antimony.hoshi.epub.ReadingSessions
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.StatisticsDay
import moe.antimony.hoshi.epub.deduplicateReadingStatistics
import moe.antimony.hoshi.epub.hasActivity

object TtuStatistics {
    fun legacyId(key: String, dateKey: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest("${key.syncKey()}\n$dateKey\nlegacy".toByteArray(Charsets.UTF_8))
        val bytes = ByteBuffer.wrap(hash)
        return UUID(bytes.long, bytes.long).toString().uppercase()
    }

    fun legacySessions(statistics: List<ReadingStatistics>, key: String, resetTime: Int, zone: ZoneId = ZoneId.systemDefault()): ReadingSessions =
        statistics.deduplicateReadingStatistics().filter { it.hasActivity }.associate {
            legacyId(key, it.dateKey) to Timestamped(it.lastStatisticModified, session(it, resetTime, zone))
        }

    fun export(sessions: ReadingSessions, title: String, resetTime: Int, zone: ZoneId = ZoneId.systemDefault()): List<ReadingStatistics> =
        StatisticsDay.grouped(sessions, resetTime, zone).map { day ->
            val total = day.total
            ReadingStatistics(
                title, day.date.toString(), total.charactersRead, total.readingTime,
                total.readingSpeed, total.readingSpeed, total.readingSpeed, total.readingSpeed,
                day.sessions.values.maxOfOrNull { it.modified } ?: 0,
            )
        }.filter { it.hasActivity }

    fun importHistory(
        statistics: List<ReadingStatistics>,
        original: ReadingSessions,
        key: String,
        resetTime: Int,
        mode: StatisticsSyncMode = StatisticsSyncMode.Merge,
        zone: ZoneId = ZoneId.systemDefault(),
        now: () -> Long = System::currentTimeMillis,
        newId: () -> String = { UUID.randomUUID().toString().uppercase() },
    ): ReadingSessions {
        if (statistics.isEmpty()) return original
        val sessions = original.toMutableMap()
        val days = StatisticsDay.grouped(sessions, resetTime, zone).associate { it.date.toString() to it.sessions }.toMutableMap()
        for (imported in statistics.deduplicateReadingStatistics()) {
            val previous = days.remove(imported.dateKey).orEmpty()
            val modified = imported.lastStatisticModified
            if (mode == StatisticsSyncMode.Merge && previous.values.maxOfOrNull { it.modified }?.let { it >= modified } == true) continue
            var id = previous.keys.minOrNull() ?: legacyId(key, imported.dateKey)
            if (previous[id] == null && sessions[id] != null) id = newId()
            val value = if (imported.hasActivity) session(imported, resetTime, zone) else null
            if (mode == StatisticsSyncMode.Replace && previous.size == 1 && previous[id]?.value == value) continue
            val timestamp = if (mode == StatisticsSyncMode.Replace) now() else modified
            previous.keys.filter { it != id || value == null }.forEach { sessions[it] = Timestamped(timestamp, null) }
            if (value != null) sessions[id] = Timestamped(timestamp, value)
        }
        if (mode == StatisticsSyncMode.Replace) {
            days.values.forEach { day -> day.keys.forEach { sessions[it] = Timestamped(now(), null) } }
        }
        return sessions
    }

    private fun session(statistic: ReadingStatistics, resetTime: Int, zone: ZoneId): ReadingSession {
        val day = LocalDate.parse(statistic.dateKey)
        var start = day.atTime(resetTime / 60, resetTime % 60).atZone(zone).toInstant().toEpochMilli()
        val estimatedEnd = statistic.lastStatisticModified
        val estimatedStart = (estimatedEnd.toDouble() - statistic.readingTime * 1000).roundToLong()
        if (StatisticsDay.date(estimatedStart, resetTime, zone) == day && StatisticsDay.date(estimatedEnd, resetTime, zone) == day) {
            start = estimatedStart
        }
        return ReadingSession(start, (start.toDouble() + statistic.readingTime * 1000).roundToLong(), statistic.charactersRead, statistic.readingTime)
    }
}
