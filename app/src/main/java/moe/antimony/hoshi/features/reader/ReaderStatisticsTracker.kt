package moe.antimony.hoshi.features.reader

import java.util.UUID
import moe.antimony.hoshi.epub.ReadingSession
import moe.antimony.hoshi.epub.ReadingSessions
import moe.antimony.hoshi.epub.ReadingTotal
import moe.antimony.hoshi.epub.StatisticsDay
import moe.antimony.hoshi.features.sync.Timestamped


data class ReaderStatisticsState(
    val isTracking: Boolean,
    val session: ReadingSession,
    val today: ReadingTotal,
    val allTime: ReadingTotal,
)

internal interface ReaderStatisticsClock {
    fun currentTimeMillis(): Long
}

internal object SystemReaderStatisticsClock : ReaderStatisticsClock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}

internal class ReaderStatisticsTracker(
    initialStatistics: ReadingSessions,
    resetMinutes: Int = 0,
    private val clock: ReaderStatisticsClock = SystemReaderStatisticsClock,
) {
    private var history = initialStatistics
    private var historyDays = emptyMap<java.time.LocalDate, ReadingTotal>()
    private var historyTotal = ReadingTotal()
    private var lastTimestampMillis = clock.currentTimeMillis()
    private var lastCharacterCount = 0
    private var isModalPaused = false
    private var sessionId = UUID.randomUUID().toString().uppercase()
    private var currentSession = ReadingSession(lastTimestampMillis, lastTimestampMillis)
    private var isTracking = false
    var resetMinutes = resetMinutes
        set(value) {
            field = value
            regroupSessions()
        }

    init { regroupSessions() }

    val state: ReaderStatisticsState
        get() {
            val today = StatisticsDay.date(clock.currentTimeMillis(), resetMinutes)
            var todayTotal = historyDays[today] ?: ReadingTotal()
            if (StatisticsDay.date(currentSession.startedAt, resetMinutes) == today) todayTotal += currentSession
            return ReaderStatisticsState(isTracking, currentSession, todayTotal, historyTotal + currentSession)
        }

    fun start(currentCharacter: Int) {
        if (!currentSession.hasActivity) currentSession = ReadingSession(clock.currentTimeMillis(), clock.currentTimeMillis())
        isTracking = true
        resetBaseline(currentCharacter)
    }

    fun startForPageTurnIfNeeded(currentCharacter: Int) {
        if (!isTracking) start(currentCharacter)
    }

    fun stop(currentCharacter: Int) { pause(currentCharacter) }

    fun pause(currentCharacter: Int): Boolean {
        if (!isTracking) return false
        update(currentCharacter)
        isTracking = false
        return true
    }

    fun update(currentCharacter: Int) {
        if (!isTracking || isModalPaused) return
        val now = clock.currentTimeMillis()
        val time = (now - lastTimestampMillis).toDouble() / 1000
        if (time <= 0) return
        currentSession = currentSession.track(maxOf(currentCharacter - lastCharacterCount, -currentSession.charactersRead), time, now)
        lastTimestampMillis = now
        lastCharacterCount = currentCharacter
    }

    fun resetBaseline(currentCharacter: Int) {
        lastCharacterCount = currentCharacter
        lastTimestampMillis = clock.currentTimeMillis()
    }

    fun setModalPaused(paused: Boolean, currentCharacter: Int) {
        if (paused == isModalPaused) return
        if (paused && isTracking) update(currentCharacter)
        isModalPaused = paused
        if (!paused && isTracking) resetBaseline(currentCharacter)
    }

    fun statisticsForPersistenceOrNull(): ReadingSessions? =
        if (currentSession.hasActivity && history[sessionId]?.value != currentSession) {
            mapOf(sessionId to Timestamped(clock.currentTimeMillis(), currentSession))
        } else null

    fun applySessions(sessions: ReadingSessions) {
        if (sessions[sessionId]?.let { it.value == null } == true) {
            sessionId = UUID.randomUUID().toString().uppercase()
            currentSession = ReadingSession(clock.currentTimeMillis(), clock.currentTimeMillis())
        }
        if (history != sessions) {
            history = sessions
            regroupSessions()
        }
    }

    private fun regroupSessions() {
        historyDays = StatisticsDay.grouped(history - sessionId, resetMinutes).associate { it.date to it.total }
        historyTotal = historyDays.values.fold(ReadingTotal()) { total, day ->
            ReadingTotal(total.charactersRead + day.charactersRead, total.readingTime + day.readingTime)
        }
    }
}
