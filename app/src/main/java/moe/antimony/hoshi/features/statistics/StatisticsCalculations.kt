package moe.antimony.hoshi.features.statistics

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

internal fun recentYearStatisticsWindow(today: LocalDate): StatisticsDateRange =
    StatisticsDateRange(
        start = today.minusYears(1).plusDays(1),
        end = today,
    )

internal fun fixedYearStatisticsWindow(year: Int, today: LocalDate): StatisticsDateRange =
    StatisticsDateRange(
        start = LocalDate.of(year, 1, 1),
        end = if (year == today.year) today else LocalDate.of(year, 12, 31),
    )

internal fun selectedStatisticsRange(
    mode: StatisticsRangeMode,
    anchor: LocalDate,
    window: StatisticsDateRange,
): StatisticsDateRange {
    val clampedAnchor = window.coerce(anchor)
    val unclipped = when (mode) {
        StatisticsRangeMode.Year -> window
        StatisticsRangeMode.Month -> {
            val month = YearMonth.from(clampedAnchor)
            StatisticsDateRange(month.atDay(1), month.atEndOfMonth())
        }
        StatisticsRangeMode.Week -> {
            val start = mondayStartOfWeek(clampedAnchor)
            StatisticsDateRange(start, start.plusDays(6))
        }
        StatisticsRangeMode.Day -> StatisticsDateRange(clampedAnchor, clampedAnchor)
    }
    return StatisticsDateRange(
        start = maxOf(unclipped.start, window.start),
        end = minOf(unclipped.end, window.end),
    )
}

internal fun mondayStartOfWeek(date: LocalDate): LocalDate =
    date.minusDays((date.dayOfWeek.value - 1).toLong())

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

internal fun currentWeekSummary(
    days: List<StatisticsDayAggregate>,
    today: LocalDate,
    settings: StatisticsTargetSettings,
): WeekStatisticsUi {
    val daysByDate = days.associateBy { it.date }
    val start = mondayStartOfWeek(today)
    val end = start.plusDays(6)
    val weekRange = StatisticsDateRange(start, end)
    val weekDates = (0L..6L).map { start.plusDays(it) }
    val aggregates = weekDates.map { date -> daysByDate[date] ?: emptyDayAggregate(date) }
    val elapsedDays = (ChronoUnit.DAYS.between(start, today).toInt() + 1).coerceIn(1, 7)
    val summary = aggregateRange(aggregates, settings)
    return WeekStatisticsUi(
        range = weekRange,
        elapsedDays = elapsedDays,
        totalCharacters = summary.totalCharacters,
        readingSeconds = summary.readingSeconds,
        averageSpeedPerHour = summary.averageSpeedPerHour,
        targetDays = settings.weeklyTargetDays,
        metTargetDays = aggregates.count { it.targetRatio(settings) >= 1.0 },
        dailyStreakDays = dailyGoalStreak(daysByDate, today, settings),
        weeklyStreakWeeks = weeklyGoalStreak(daysByDate, today, settings),
        averageCharactersPerElapsedDay = (summary.totalCharacters.toDouble() / elapsedDays.toDouble()).roundToInt(),
        averageReadingSecondsPerElapsedDay = summary.readingSeconds / elapsedDays.toDouble(),
        days = weekDates.map { date ->
            val aggregate = daysByDate[date]
            val isFuture = date.isAfter(today)
            val ratio = aggregate?.targetRatio(settings) ?: 0.0
            WeekDayGoalUi(
                date = date,
                isToday = date == today,
                isFuture = isFuture,
                percent = if (aggregate != null && aggregate.isActiveReadingDay() && !isFuture) {
                    (ratio * 100.0).roundToInt()
                } else {
                    null
                },
                metTarget = !isFuture && ratio >= 1.0,
            )
        },
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

internal fun weeklyGoalStreak(
    daysByDate: Map<LocalDate, StatisticsDayAggregate>,
    today: LocalDate,
    settings: StatisticsTargetSettings,
): Int {
    var weekStart = mondayStartOfWeek(today)
    if (!weekMet(daysByDate, weekStart, settings)) {
        weekStart = weekStart.minusWeeks(1)
    }
    var streak = 0
    while (weekMet(daysByDate, weekStart, settings)) {
        streak += 1
        weekStart = weekStart.minusWeeks(1)
    }
    return streak
}

internal fun trendPoints(
    rangeMode: StatisticsRangeMode,
    range: StatisticsDateRange,
    days: List<StatisticsDayAggregate>,
): List<StatisticsTrendPoint> =
    when (rangeMode) {
        StatisticsRangeMode.Day -> emptyList()
        StatisticsRangeMode.Year -> {
            val daysByMonth = days.groupBy { YearMonth.from(it.date) }
            val startMonth = YearMonth.from(range.start)
            val endMonth = YearMonth.from(range.end)
            generateSequence(startMonth) { month ->
                month.plusMonths(1).takeIf { !it.isAfter(endMonth) }
            }.map { month ->
                val groupedDays = daysByMonth[month].orEmpty()
                StatisticsTrendPoint(
                    key = month.toString(),
                    label = "${month.monthValue}",
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
                    label = date.dayOfMonth.toString(),
                    characters = day?.totalCharacters ?: 0,
                    readingSeconds = day?.readingSeconds ?: 0.0,
                )
            }.toList()
        }
    }

internal fun distributionRows(
    days: List<StatisticsDayAggregate>,
    settings: StatisticsTargetSettings,
): List<BookDistributionRow> {
    val grouped = linkedMapOf<String, MutableList<StatisticsBookContribution>>()
    days.flatMap { it.bookContributions }
        .filter { it.characters > 0 || it.readingSeconds > 0.0 }
        .forEach { contribution ->
            grouped.getOrPut(contribution.bookId) { mutableListOf() } += contribution
        }
    val totals = grouped.values.map { contributions ->
        val first = contributions.first()
        StatisticsBookContribution(
            bookId = first.bookId,
            title = first.title,
            coverPath = first.coverPath,
            characters = contributions.sumOf { it.characters },
            readingSeconds = contributions.sumOf { it.readingSeconds },
        )
    }
    val percentBase = when (settings.dailyTargetType) {
        DailyTargetType.Characters -> totals.sumOf { it.characters }.toDouble()
        DailyTargetType.Duration -> totals.sumOf { it.readingSeconds }
    }
    return totals
        .sortedWith(
            compareByDescending<StatisticsBookContribution> {
                when (settings.dailyTargetType) {
                    DailyTargetType.Characters -> it.characters.toDouble()
                    DailyTargetType.Duration -> it.readingSeconds
                }
            }.thenBy { it.title.lowercase() },
        )
        .map { contribution ->
            val percentValue = when (settings.dailyTargetType) {
                DailyTargetType.Characters -> contribution.characters.toDouble()
                DailyTargetType.Duration -> contribution.readingSeconds
            }
            BookDistributionRow(
                title = contribution.title,
                coverPath = contribution.coverPath,
                characters = contribution.characters,
                readingSeconds = contribution.readingSeconds,
                percent = if (percentBase > 0.0) {
                    ((percentValue / percentBase) * 100.0).roundToInt().coerceIn(0, 100)
                } else {
                    0
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

private fun weekMet(
    daysByDate: Map<LocalDate, StatisticsDayAggregate>,
    weekStart: LocalDate,
    settings: StatisticsTargetSettings,
): Boolean =
    (0L..6L).count { offset ->
        (daysByDate[weekStart.plusDays(offset)]?.targetRatio(settings) ?: 0.0) >= 1.0
    } >= settings.weeklyTargetDays
