package moe.antimony.hoshi.features.statistics

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.roundToInt

internal fun selectedStatisticsRange(
    mode: StatisticsRangeMode,
    anchor: LocalDate,
    today: LocalDate,
    firstActivity: LocalDate? = null,
    locale: Locale = Locale.getDefault(),
): StatisticsDateRange {
    val boundedAnchor = minOf(anchor, today)
    return when (mode) {
        StatisticsRangeMode.Year -> StatisticsDateRange(
            LocalDate.of(boundedAnchor.year, 1, 1), LocalDate.of(boundedAnchor.year, 12, 31),
        )
        StatisticsRangeMode.Month -> YearMonth.from(boundedAnchor).let { month ->
            StatisticsDateRange(month.atDay(1), month.atEndOfMonth())
        }
        StatisticsRangeMode.Week -> statisticsStartOfWeek(boundedAnchor, locale).let { start ->
            StatisticsDateRange(start, start.plusDays(6))
        }
        StatisticsRangeMode.All -> StatisticsDateRange(minOf(firstActivity ?: today, today), today)
    }
}

internal fun statisticsStartOfWeek(date: LocalDate, locale: Locale = Locale.getDefault()): LocalDate {
    val firstDay = WeekFields.of(locale).firstDayOfWeek
    return date.minusDays(((date.dayOfWeek.value - firstDay.value + 7) % 7).toLong())
}

internal fun shiftedStatisticsAnchor(
    mode: StatisticsRangeMode,
    anchor: LocalDate,
    offset: Int,
    locale: Locale = Locale.getDefault(),
): LocalDate? =
    when (mode) {
        StatisticsRangeMode.Week -> statisticsStartOfWeek(anchor, locale).plusWeeks(offset.toLong())
        StatisticsRangeMode.Month -> anchor.withDayOfMonth(1).plusMonths(offset.toLong())
        StatisticsRangeMode.Year -> anchor.withDayOfYear(1).plusYears(offset.toLong())
        StatisticsRangeMode.All -> null
    }

internal fun overviewRangeSummary(
    days: List<StatisticsDayAggregate>,
    settings: StatisticsTargetSettings,
    mode: StatisticsRangeMode,
    anchor: LocalDate,
    today: LocalDate,
    locale: Locale = Locale.getDefault(),
): StatisticsRangeSummary {
    val activity = days.filter { !it.date.isAfter(today) && it.isActiveReadingDay() }
    val firstActivity = activity.minOfOrNull { it.date }
    fun period(reference: LocalDate): Pair<StatisticsRangeSummary, Double> {
        val range = selectedStatisticsRange(mode, reference, today, firstActivity, locale)
        val summary = aggregateRange(activity.filter { range.contains(it.date) }, settings)
        val elapsedEnd = minOf(range.end, today)
        val count = when (mode) {
            StatisticsRangeMode.Year, StatisticsRangeMode.All ->
                ChronoUnit.MONTHS.between(YearMonth.from(range.start), YearMonth.from(elapsedEnd)) + 1
            else -> ChronoUnit.DAYS.between(range.start, elapsedEnd) + 1
        }.coerceAtLeast(1)
        return summary to summary.readingSeconds / count
    }
    val (summary, average) = period(anchor)
    val previous = shiftedStatisticsAnchor(mode, anchor, -1, locale)?.let { period(it).second }
    return summary.copy(
        averageReadingSecondsPerBucket = average,
        averageReadingTimeChangePercent = previous?.takeIf { it > 0.0 }?.let { (average - it) / it * 100.0 },
    )
}

internal fun statisticsHistorySummary(
    days: List<StatisticsDayAggregate>,
    today: LocalDate,
    settings: StatisticsTargetSettings,
): StatisticsHistoryUi {
    val activity = days.filter { !it.date.isAfter(today) && it.isActiveReadingDay() }.sortedBy { it.date }
    var current = StatisticsGoalStreak()
    var longest = StatisticsGoalStreak()
    var metDays = 0
    activity.filter { it.targetRatio(settings) >= 1.0 }.forEach { day ->
        metDays += 1
        val previousRange = current.range
        current = if (previousRange?.end?.plusDays(1) == day.date) {
            StatisticsGoalStreak(current.count + 1, StatisticsDateRange(previousRange.start, day.date))
        } else {
            StatisticsGoalStreak(1, StatisticsDateRange(day.date, day.date))
        }
        if (current.count > longest.count) longest = current
    }
    if (current.range?.end?.let { it < today.minusDays(1) } != false) current = StatisticsGoalStreak()
    val best = activity.maxByOrNull { day ->
        when (settings.dailyTargetType) {
            DailyTargetType.Characters -> day.totalCharacters.toDouble()
            DailyTargetType.Duration -> day.readingSeconds
        }
    }
    return StatisticsHistoryUi(current, longest, metDays, activity.size, best)
}

internal fun StatisticsDayAggregate.targetRatio(settings: StatisticsTargetSettings): Double =
    when (settings.dailyTargetType) {
        DailyTargetType.Characters -> if (settings.dailyCharacterTarget > 0) {
            totalCharacters.toDouble() / settings.dailyCharacterTarget.toDouble()
        } else {
            0.0
        }
        DailyTargetType.Duration -> {
            val targetSeconds = settings.dailyDurationTargetMinutes * 60.0
            if (targetSeconds > 0.0) readingSeconds / targetSeconds else 0.0
        }
    }

internal fun StatisticsDayAggregate.isActiveReadingDay(): Boolean =
    totalCharacters > 0 || readingSeconds > 0.0

internal fun aggregateRange(
    days: List<StatisticsDayAggregate>,
    settings: StatisticsTargetSettings,
): StatisticsRangeSummary {
    val totalCharacters = days.sumOf { it.totalCharacters }
    val readingSeconds = days.sumOf { it.readingSeconds }
    return StatisticsRangeSummary(
        totalCharacters = totalCharacters,
        readingSeconds = readingSeconds,
        averageSpeedPerHour = averageSpeedPerHour(totalCharacters, readingSeconds),
        targetDays = days.count { it.targetRatio(settings) >= 1.0 },
        targetProgressPercent = if (days.size == 1) {
            (days.single().targetRatio(settings) * 100.0).roundToInt()
        } else {
            0
        },
    )
}

internal fun todaySummary(
    daysByDate: Map<LocalDate, StatisticsDayAggregate>,
    today: LocalDate,
    settings: StatisticsTargetSettings,
): TodayStatisticsUi {
    val aggregate = daysByDate[today] ?: emptyDayAggregate(today)
    return TodayStatisticsUi(
        date = today,
        targetPercent = (aggregate.targetRatio(settings) * 100.0).roundToInt(),
        totalCharacters = aggregate.totalCharacters,
        readingSeconds = aggregate.readingSeconds,
        averageSpeedPerHour = averageSpeedPerHour(aggregate.totalCharacters, aggregate.readingSeconds),
        dailyStreakDays = dailyGoalStreak(daysByDate, today, settings),
    )
}

internal fun dailyGoalStreak(
    daysByDate: Map<LocalDate, StatisticsDayAggregate>,
    today: LocalDate,
    settings: StatisticsTargetSettings,
): Int {
    var cursor = today
    val todayMet = daysByDate[today]?.targetRatio(settings) ?: 0.0 >= 1.0
    if (!todayMet) {
        cursor = cursor.minusDays(1)
    }
    var streak = 0
    while ((daysByDate[cursor]?.targetRatio(settings) ?: 0.0) >= 1.0) {
        streak += 1
        cursor = cursor.minusDays(1)
    }
    return streak
}

internal fun trendPoints(
    rangeMode: StatisticsRangeMode,
    range: StatisticsDateRange,
    days: List<StatisticsDayAggregate>,
): List<StatisticsTrendPoint> =
    when (rangeMode) {
        StatisticsRangeMode.Year, StatisticsRangeMode.All -> {
            val daysByMonth = days.filter { range.contains(it.date) }.groupBy { YearMonth.from(it.date) }
            val startMonth = YearMonth.from(range.start)
            val endMonth = YearMonth.from(range.end)
            generateSequence(startMonth) { month ->
                month.plusMonths(1).takeIf { !it.isAfter(endMonth) }
            }.map { month ->
                val groupedDays = daysByMonth[month].orEmpty()
                StatisticsTrendPoint(
                    key = month.toString(),
                    characters = groupedDays.sumOf { it.totalCharacters },
                    readingSeconds = groupedDays.sumOf { it.readingSeconds },
                )
            }.toList()
        }
        StatisticsRangeMode.Month,
        StatisticsRangeMode.Week -> {
            val daysByDate = days.associateBy { it.date }
            generateSequence(range.start) { date ->
                date.plusDays(1).takeIf { !it.isAfter(range.end) }
            }.map { date ->
                val day = daysByDate[date]
                StatisticsTrendPoint(
                    key = date.toString(),
                    characters = day?.totalCharacters ?: 0,
                    readingSeconds = day?.readingSeconds ?: 0.0,
                )
            }.toList()
        }
    }

internal fun distributionRows(
    days: List<StatisticsDayAggregate>,
): List<BookDistributionRow> {
    val grouped = linkedMapOf<String, MutableList<StatisticsBookContribution>>()
    days.flatMap { it.bookContributions }
        .filter { it.characters > 0 || it.readingSeconds > 0.0 }
        .forEach { contribution ->
            grouped.getOrPut(contribution.folder) { mutableListOf() } += contribution
        }
    val totals = grouped.values.map { contributions ->
        val first = contributions.first()
        StatisticsBookContribution(
            bookId = first.bookId,
            folder = first.folder,
            isArchived = contributions.all { it.isArchived },
            title = first.title,
            coverPath = first.coverPath,
            characters = contributions.sumOf { it.characters },
            readingSeconds = contributions.sumOf { it.readingSeconds },
        )
    }
    val maxReadingSeconds = totals.maxOfOrNull { it.readingSeconds } ?: 0.0
    return totals
        .sortedWith(compareByDescending<StatisticsBookContribution> { it.readingSeconds }.thenBy { it.title.lowercase() })
        .map { contribution ->
            BookDistributionRow(
                bookId = contribution.bookId,
                folder = contribution.folder,
                isArchived = contribution.isArchived,
                title = contribution.title,
                coverPath = contribution.coverPath,
                characters = contribution.characters,
                readingSeconds = contribution.readingSeconds,
                timeFraction = if (maxReadingSeconds > 0.0) {
                    (contribution.readingSeconds / maxReadingSeconds).toFloat().coerceIn(0f, 1f)
                } else {
                    0f
                },
            )
        }
}

internal fun emptyDayAggregate(date: LocalDate): StatisticsDayAggregate =
    StatisticsDayAggregate(
        date = date,
        totalCharacters = 0,
        readingSeconds = 0.0,
        activeBookCount = 0,
        bookContributions = emptyList(),
    )

internal fun datesInRange(range: StatisticsDateRange): List<LocalDate> =
    (0L until range.dayCount.toLong()).map { range.start.plusDays(it) }

internal fun averageSpeedPerHour(characters: Int, readingSeconds: Double): Int =
    if (readingSeconds > 0.0) {
        (characters.toDouble() / readingSeconds * 3_600.0).roundToInt()
    } else {
        0
    }

/** Chart selection drills into a bucket without changing the enclosing reading-time period. */
internal fun statisticsTrendBucket(mode: StatisticsRangeMode, key: String): StatisticsDateRange? =
    runCatching {
        when (mode) {
            StatisticsRangeMode.Week, StatisticsRangeMode.Month -> LocalDate.parse(key).let { StatisticsDateRange(it, it) }
            StatisticsRangeMode.Year, StatisticsRangeMode.All -> YearMonth.parse(key).let {
                StatisticsDateRange(it.atDay(1), it.atEndOfMonth())
            }
        }
    }.getOrNull()

internal fun statisticsPeriodPageCount(mode: StatisticsRangeMode, firstDate: LocalDate, today: LocalDate): Int =
    when (mode) {
        StatisticsRangeMode.All -> 1
        StatisticsRangeMode.Week -> ChronoUnit.WEEKS.between(statisticsStartOfWeek(firstDate), statisticsStartOfWeek(today)).toInt() + 1
        StatisticsRangeMode.Month -> ChronoUnit.MONTHS.between(YearMonth.from(firstDate), YearMonth.from(today)).toInt() + 1
        StatisticsRangeMode.Year -> today.year - firstDate.year + 1
    }.coerceAtLeast(1)

internal fun statisticsPeriodPageAnchor(mode: StatisticsRangeMode, page: Int, pageCount: Int, today: LocalDate): LocalDate =
    shiftedStatisticsAnchor(mode, today, page - pageCount + 1) ?: today

internal const val ReadingHeatActiveLevelCount = 7

internal fun readingHeatLevels(days: List<StatisticsDayAggregate>): Map<LocalDate, Int> {
    val activeCharacterValues = days
        .map { day -> day.totalCharacters }
        .filter { characters -> characters > 0 }
        .distinct()
        .sorted()
    val levelByCharacters = activeCharacterValues.adaptiveHeatLevels()
    return days.associate { day ->
        day.date to (levelByCharacters[day.totalCharacters] ?: 0)
    }
}

private fun List<Int>.adaptiveHeatLevels(): Map<Int, Int> {
    if (isEmpty()) {
        return emptyMap()
    }
    if (size == 1) {
        return mapOf(single() to ReadingHeatActiveLevelCount)
    }
    val maxIndex = lastIndex.toDouble()
    return mapIndexed { index, characters ->
        val normalizedRank = index.toDouble() / maxIndex
        val level = 1 + (normalizedRank * (ReadingHeatActiveLevelCount - 1)).roundToInt()
        characters to level.coerceIn(1, ReadingHeatActiveLevelCount)
    }.toMap()
}
