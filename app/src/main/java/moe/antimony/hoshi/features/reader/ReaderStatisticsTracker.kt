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
    private val onSaveSessions: (ReadingSessions) -> Unit = {},
) {
    private var history = initialStatistics
    private var pendingCompletedSessions: ReadingSessions = emptyMap()
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
        val characters = maxOf(currentCharacter - lastCharacterCount, -currentSession.charactersRead)
        val dayChanged = StatisticsDay.date(currentSession.startedAt, resetMinutes) != StatisticsDay.date(now, resetMinutes)
        if (dayChanged) {
            if (currentSession.hasActivity && history[sessionId]?.value != currentSession) {
                pendingCompletedSessions = pendingCompletedSessions +
                    (sessionId to Timestamped<ReadingSession?>(now, currentSession))
            }
            sessionId = UUID.randomUUID().toString().uppercase()
            currentSession = ReadingSession(now, now)
            regroupSessions()
        }
        currentSession = currentSession.track(characters, time, now)
        lastTimestampMillis = now
        lastCharacterCount = currentCharacter
        if (dayChanged && pendingCompletedSessions.isNotEmpty()) onSaveSessions(pendingCompletedSessions)
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

    fun statisticsForPersistenceOrNull(): ReadingSessions? {
        var pending = pendingCompletedSessions
        if (currentSession.hasActivity && history[sessionId]?.value != currentSession) {
            pending = pending + (sessionId to Timestamped(clock.currentTimeMillis(), currentSession))
        }
        return pending.takeIf { it.isNotEmpty() }
    }

    fun applySessions(sessions: ReadingSessions) {
        // Keep finished segments until storage confirms their latest values or a deletion.
        // An older in-flight save can be read back after a day rollover.
        pendingCompletedSessions = pendingCompletedSessions.filter { (id, pending) ->
            val stored = sessions[id]
            stored == null || (stored.value != null && stored.value != pending.value)
        }
        if (sessions[sessionId]?.let { it.value == null } == true) {
            sessionId = UUID.randomUUID().toString().uppercase()
            currentSession = ReadingSession(clock.currentTimeMillis(), clock.currentTimeMillis())
        }
        history = sessions
        regroupSessions()
    }

    private fun regroupSessions() {
        historyDays = StatisticsDay.grouped((history + pendingCompletedSessions) - sessionId, resetMinutes)
            .associate { it.date to it.total }
        historyTotal = historyDays.values.fold(ReadingTotal()) { total, day ->
            ReadingTotal(total.charactersRead + day.charactersRead, total.readingTime + day.readingTime)
        }
    }
}
