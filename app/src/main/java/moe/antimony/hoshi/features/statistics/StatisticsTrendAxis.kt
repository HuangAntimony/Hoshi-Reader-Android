package moe.antimony.hoshi.features.statistics

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil

/** The plot and hit testing share a continuous calendar scale, including unequal months. */
internal fun trendDateFraction(date: LocalDate, range: StatisticsDateRange): Float =
    ChronoUnit.DAYS.between(range.start, date).toFloat() / range.dayCount

internal fun trendBucketIndex(offsetX: Float, plotWidth: Float, buckets: List<StatisticsDateRange>): Int? {
    if (buckets.isEmpty() || plotWidth <= 0 || offsetX < 0 || offsetX >= plotWidth) return null
    val range = StatisticsDateRange(buckets.first().start, buckets.last().end)
    val fraction = offsetX / plotWidth
    return buckets.indexOfLast { fraction >= trendDateFraction(it.start, range) }.takeIf { it >= 0 }
}

/** Calendar-aligned ticks; long all-time histories thin complete three-month intervals. */
internal fun trendAxisDates(
    mode: StatisticsRangeMode,
    range: StatisticsDateRange,
    locale: Locale = Locale.getDefault(),
    maxLabels: Int = Int.MAX_VALUE,
): List<LocalDate> {
    val first = when (mode) {
        StatisticsRangeMode.Month -> statisticsStartOfWeek(range.start, locale).let {
            if (it < range.start) it.plusWeeks(1) else it
        }
        else -> range.start
    }
    val monthStep = if (mode == StatisticsRangeMode.All) {
        val months = ChronoUnit.MONTHS.between(range.start.withDayOfMonth(1), range.end.withDayOfMonth(1)) + 1
        3L * ceil(months / 3.0 / maxLabels.coerceAtLeast(1)).toLong().coerceAtLeast(1)
    } else 2L
    return generateSequence(first) { date ->
        when (mode) {
            StatisticsRangeMode.Week -> date.plusDays(1)
            StatisticsRangeMode.Month -> date.plusWeeks(1)
            StatisticsRangeMode.Year, StatisticsRangeMode.All -> date.plusMonths(monthStep)
        }
    }.takeWhile { it <= range.end }.toList()
}
