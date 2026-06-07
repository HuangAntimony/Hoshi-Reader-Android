package moe.antimony.hoshi.features.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookInfo
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.ReadingStatistics
import org.junit.Assert.assertEquals
import org.junit.Test

class TtuSyncRulesTest {
    @Test
    fun sanitizeTtuFilenameMatchesIosAndTtuRules() {
        assertEquals("Book~ttu-spc~", TtuSyncRules.sanitizeTtuFilename("Book "))
        assertEquals("Book~ttu-dend~", TtuSyncRules.sanitizeTtuFilename("Book."))
        assertEquals(
            "a~ttu-star~b%2Fc%3Fd%3Ce%3Ef%5Cg%3Ah%7Ci%25j%22k",
            TtuSyncRules.sanitizeTtuFilename("a*b/c?d<e>f\\g:h|i%j\"k"),
        )
    }

    @Test
    fun appleReferenceSecondsConvertToUnixMillis() {
        assertEquals(978_307_200_000L, TtuSyncRules.appleReferenceSecondsToUnixMillis(0.0))
        assertEquals(978_307_201_234L, TtuSyncRules.appleReferenceSecondsToUnixMillis(1.234))
        assertEquals(1.234, TtuSyncRules.unixMillisToAppleReferenceSeconds(978_307_201_234L), 0.000001)
    }

    @Test
    fun automaticDirectionUsesLocalBookmarkAndRemoteProgressTimestamp() {
        assertEquals(
            SyncDirection.Synced,
            TtuSyncRules.determineDirection(local = null, remoteProgressFile = null),
        )
        assertEquals(
            SyncDirection.ImportFromTtu,
            TtuSyncRules.determineDirection(
                local = null,
                remoteProgressFile = DriveFile(id = "remote", name = "progress_1_6_2000_0.2.json"),
            ),
        )
        assertEquals(
            SyncDirection.ExportToTtu,
            TtuSyncRules.determineDirection(
                local = Bookmark(0, 0.1, 10, TtuSyncRules.unixMillisToAppleReferenceSeconds(3_000)),
                remoteProgressFile = DriveFile(id = "remote", name = "progress_1_6_2000_0.2.json"),
            ),
        )
        assertEquals(
            SyncDirection.ImportFromTtu,
            TtuSyncRules.determineDirection(
                local = Bookmark(0, 0.1, 10, TtuSyncRules.unixMillisToAppleReferenceSeconds(1_000)),
                remoteProgressFile = DriveFile(id = "remote", name = "progress_1_6_2000_0.2.json"),
            ),
        )
        assertEquals(
            SyncDirection.Synced,
            TtuSyncRules.determineDirection(
                local = Bookmark(0, 0.1, 10, TtuSyncRules.unixMillisToAppleReferenceSeconds(2_000)),
                remoteProgressFile = DriveFile(id = "remote", name = "progress_1_6_2000_0.2.json"),
            ),
        )
    }

    @Test
    fun bookInfoResolvesRemoteCharacterPositionLikeIos() {
        val bookInfo = BookInfo(
            characterCount = 300,
            chapterInfo = mapOf(
                "intro" to BookInfo.ChapterInfo(spineIndex = 0, currentTotal = 0, chapterCount = 100),
                "body" to BookInfo.ChapterInfo(spineIndex = 1, currentTotal = 100, chapterCount = 200),
            ),
        )

        assertEquals(ResolvedBookPosition(spineIndex = 1, progress = 0.25), bookInfo.resolveTtuCharacterPosition(150))
        assertEquals(ResolvedBookPosition(spineIndex = 1, progress = 0.995), bookInfo.resolveTtuCharacterPosition(999))
    }

    @Test
    fun progressAudioAndStatisticsFileNamesMatchIos() {
        val progress = TtuProgress(
            dataId = 7,
            exploredCharCount = 300,
            progress = 0.375,
            lastBookmarkModified = 1_700_000_123_456L,
        )
        assertEquals(
            "progress_1_6_1700000123456_0.375.json",
            TtuSyncRules.progressFileName(progress),
        )
        assertEquals(
            """{"dataId":7,"exploredCharCount":300,"progress":0.375,"lastBookmarkModified":1700000123456}""",
            Json.encodeToString(progress),
        )

        val audioBook = TtuAudioBook(
            title = "Title",
            playbackPosition = 12.5,
            lastAudioBookModified = 1_700_000_123_999L,
        )
        assertEquals(
            "audioBook_1_6_1700000123999_12.5.json",
            TtuSyncRules.audioBookFileName(audioBook),
        )

        val stats = listOf(
            ReadingStatistics(
                title = "Title",
                dateKey = "2026-05-12",
                charactersRead = 100,
                readingTime = 50.0,
                minReadingSpeed = 10,
                altMinReadingSpeed = 8,
                lastReadingSpeed = 7200,
                maxReadingSpeed = 9000,
                lastStatisticModified = 1_000,
            ),
            ReadingStatistics(
                title = "Title",
                dateKey = "2026-05-13",
                charactersRead = 200,
                readingTime = 100.0,
                minReadingSpeed = 12,
                altMinReadingSpeed = 7,
                lastReadingSpeed = 7200,
                maxReadingSpeed = 9300,
                lastStatisticModified = 3_000,
            ),
        )

        assertEquals(
            "statistics_1_6_3000_300_150.0_10_7_7200.0_7200_75.0_84.0_150.0_167.0_7200.0_7158.0_na.json",
            TtuSyncRules.statisticsFileName(stats),
        )
    }

    @Test
    fun driveSyncFilesPreferLatestTimestampedTtuFiles() {
        val syncFiles = listOf(
            DriveFile(id = "old-bookdata", name = "bookdata_1_6_200_1000_500.zip"),
            DriveFile(id = "new-bookdata", name = "bookdata_1_6_200_3000_500.zip"),
            DriveFile(id = "old-progress", name = "progress_1_6_1000_0.2.json"),
            DriveFile(id = "new-progress", name = "progress_1_6_4000_0.8.json"),
            DriveFile(id = "old-statistics", name = "statistics_1_6_1000_10_10.0_0_0_0.0_0_0.0_0.0_0.0_0.0_0.0_0.0_na.json"),
            DriveFile(id = "new-statistics", name = "statistics_1_6_5000_10_10.0_0_0_0.0_0_0.0_0.0_0.0_0.0_0.0_0.0_na.json"),
            DriveFile(id = "old-audio", name = "audioBook_1_6_1000_4.0.json"),
            DriveFile(id = "new-audio", name = "audioBook_1_6_6000_8.0.json"),
            DriveFile(id = "cover", name = "cover_1_6.jpeg"),
        ).toDriveSyncFiles()

        assertEquals("new-bookdata", syncFiles.bookData?.id)
        assertEquals("new-progress", syncFiles.progress?.id)
        assertEquals("new-statistics", syncFiles.statistics?.id)
        assertEquals("new-audio", syncFiles.audioBook?.id)
        assertEquals("cover", syncFiles.cover?.id)
    }

    @Test
    fun statisticsMergeAndReplaceMatchIosSemantics() {
        val local = listOf(
            ReadingStatistics(title = "Title", dateKey = "2026-05-12", charactersRead = 100, lastStatisticModified = 100),
            ReadingStatistics(title = "Title", dateKey = "2026-05-13", charactersRead = 200, lastStatisticModified = 100),
        )
        val external = listOf(
            ReadingStatistics(title = "Title", dateKey = "2026-05-13", charactersRead = 250, lastStatisticModified = 200),
            ReadingStatistics(title = "Title", dateKey = "2026-05-14", charactersRead = 300, lastStatisticModified = 50),
        )

        assertEquals(external, TtuSyncRules.mergeStatistics(local, external, StatisticsSyncMode.Replace))
        assertEquals(
            mapOf(
                "2026-05-12" to 100,
                "2026-05-13" to 250,
                "2026-05-14" to 300,
            ),
            TtuSyncRules.mergeStatistics(local, external, StatisticsSyncMode.Merge)
                .associate { it.dateKey to it.charactersRead },
        )
    }
}
