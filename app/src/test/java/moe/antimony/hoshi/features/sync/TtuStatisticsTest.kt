package moe.antimony.hoshi.features.sync

import java.time.Instant
import java.time.ZoneId
import moe.antimony.hoshi.epub.ReadingSession
import moe.antimony.hoshi.epub.ReadingStatistics
import org.junit.Assert.*
import org.junit.Test

class TtuStatisticsTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val end = Instant.parse("2026-09-22T20:15:00Z").toEpochMilli()
    private val daily = ReadingStatistics("Title", "2026-09-22", 100, 60.0, lastStatisticModified = end)

    @Test fun legacyIdsMatchPinnedSwiftCryptoKitOutputWithoutUuidBitRewriting() {
        assertEquals("12FAD961-C008-C0A9-9166-FB26E6FF53AC", TtuStatistics.legacyId("義妹生活４【電子特典付き】", daily.dateKey))
        assertEquals("6A9C292D-2C4E-430A-7F14-DBC2AFE81D1E", TtuStatistics.legacyId("て\u3099", daily.dateKey))
        assertEquals(TtuStatistics.legacyId("で", daily.dateKey), TtuStatistics.legacyId("て\u3099", daily.dateKey))
        assertEquals("8F903F33-67FE-FBBB-30E9-E4A00A9E620B", TtuStatistics.legacyId("book-a", daily.dateKey))
    }

    @Test fun conversionUsesEstimatedStartOnlyWhenBothEndsBelongToTheDay() {
        val sessions = TtuStatistics.legacySessions(listOf(daily), "book-a", 240, zone)
        assertEquals(ReadingSession(end - 60000, end, 100, 60.0), sessions.values.single().value)
        val unknown = TtuStatistics.legacySessions(listOf(daily.copy(lastStatisticModified = 0)), "book-a", 240, zone)
        assertEquals(Instant.parse("2026-09-22T02:00:00Z").toEpochMilli(), unknown.values.single().value!!.startedAt)
        assertEquals(0L, unknown.values.single().modified)
    }

    @Test fun exportAggregatesSessionsAndDerivesEverySpeedField() {
        val session = ReadingSession(end - 60000, end, 100, 60.0)
        val exported = TtuStatistics.export(mapOf("A" to Timestamped(end, session), "B" to Timestamped(end + 1, session)), "Title", 0, zone).single()
        assertEquals(200, exported.charactersRead)
        assertEquals(120.0, exported.readingTime, 0.0)
        assertEquals(listOf(6000, 6000, 6000, 6000), listOf(exported.minReadingSpeed, exported.altMinReadingSpeed, exported.lastReadingSpeed, exported.maxReadingSpeed))
        assertEquals(end + 1, exported.lastStatisticModified)
    }

    @Test fun importReusesSmallestLiveIdDeletesOtherContributionsAndIsIdempotent() {
        val session = ReadingSession(end - 60000, end, 50, 30.0)
        val original = mapOf("B" to Timestamped<ReadingSession?>(end - 1, session), "A" to Timestamped<ReadingSession?>(end - 1, session))
        val merged = TtuStatistics.importHistory(listOf(daily), original, "book-a", 0, zone = zone)
        assertEquals(100, merged.getValue("A").value!!.charactersRead)
        assertNull(merged.getValue("B").value)
        assertEquals(merged, TtuStatistics.importHistory(listOf(daily), merged, "book-a", 0, zone = zone))
    }

    @Test fun replacementDeletesOmittedDaysAndNeverResurrectsADeletedId() {
        val id = TtuStatistics.legacyId("book-a", daily.dateKey)
        val old = ReadingSession(end - 86400000, end - 86400000, 1, 1.0)
        val original = mapOf(id to Timestamped<ReadingSession?>(end, null), "old" to Timestamped<ReadingSession?>(end, old))
        val replaced = TtuStatistics.importHistory(listOf(daily), original, "book-a", 0, StatisticsSyncMode.Replace, zone, { end + 10 }, { "NEW" })
        assertNull(replaced.getValue(id).value)
        assertNull(replaced.getValue("old").value)
        assertEquals(100, replaced.getValue("NEW").value!!.charactersRead)
        assertEquals(replaced, TtuStatistics.importHistory(listOf(daily), replaced, "book-a", 0, StatisticsSyncMode.Replace, zone))
        assertEquals(replaced, TtuStatistics.importHistory(emptyList(), replaced, "book-a", 0, StatisticsSyncMode.Replace, zone))
    }
}
