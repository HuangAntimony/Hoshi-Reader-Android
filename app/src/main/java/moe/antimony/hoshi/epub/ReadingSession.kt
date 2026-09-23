package moe.antimony.hoshi.epub

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.Serializable
import moe.antimony.hoshi.features.sync.Timestamped

typealias ReadingSessions = Map<String, Timestamped<ReadingSession?>>

@Serializable
data class ReadingSession(
    val startedAt: Long,
    val endedAt: Long,
    val charactersRead: Int = 0,
    val readingTime: Double = 0.0,
) {
    val hasActivity: Boolean get() = charactersRead > 0 || readingTime > 0
    val readingSpeed: Int get() = ReadingTotal.speed(charactersRead, readingTime)

    fun track(characters: Int, time: Double, until: Long): ReadingSession = copy(
        charactersRead = maxOf(charactersRead + characters, 0), readingTime = readingTime + time, endedAt = until,
    )
}

data class ReadingTotal(val charactersRead: Int = 0, val readingTime: Double = 0.0) {
    val readingSpeed: Int get() = speed(charactersRead, readingTime)
    operator fun plus(session: ReadingSession): ReadingTotal =
        ReadingTotal(charactersRead + session.charactersRead, readingTime + session.readingTime)

    companion object {
        fun speed(characters: Int, time: Double): Int = if (time > 0) (characters / time * 3600).toInt() else 0
    }
}

data class StatisticsDay(val date: LocalDate, val sessions: ReadingSessions) {
    val total: ReadingTotal get() = sessions.values.fold(ReadingTotal()) { total, change -> total + change.value!! }

    companion object {
        fun date(timestamp: Long, resetTime: Int, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
            Instant.ofEpochMilli(timestamp).minusSeconds(resetTime.toLong() * 60).atZone(zone).toLocalDate()

        fun grouped(sessions: ReadingSessions, resetTime: Int, zone: ZoneId = ZoneId.systemDefault()): List<StatisticsDay> =
            sessions.filterValues { it.value != null }.entries.groupBy { date(it.value.value!!.startedAt, resetTime, zone) }
                .toSortedMap().map { (date, entries) -> StatisticsDay(date, entries.associate { it.toPair() }) }
    }
}
