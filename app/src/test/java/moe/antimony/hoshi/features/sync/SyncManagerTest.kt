package moe.antimony.hoshi.features.sync

import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookInfo
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.SasayakiPlaybackData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncManagerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun importFromTtuUpdatesProgressStatisticsAndOnlySasayakiPosition() = runBlocking {
        val repository = BookRepository(tempFolder.root)
        val entry = repository.createEntry()
        repository.saveBookmark(entry.root, Bookmark(0, 0.1, 10, TtuSyncRules.unixMillisToAppleReferenceSeconds(1_000)))
        repository.saveStatistics(
            entry.root,
            listOf(ReadingStatistics(title = "Title", dateKey = "2026-05-12", charactersRead = 100, lastStatisticModified = 100)),
        )
        repository.saveSasayakiPlayback(
            entry.root,
            SasayakiPlaybackData(lastPosition = 1.0, delay = 0.2, rate = 1.5f, audioUri = "content://audio"),
        )
        val drive = FakeDriveSyncDataSource(
            progress = TtuProgress(7, 150, 0.5, 2_000),
            statistics = listOf(
                ReadingStatistics(title = "Title", dateKey = "2026-05-12", charactersRead = 120, lastStatisticModified = 200),
            ),
            audioBook = TtuAudioBook("Title", 88.5, 2_000),
        )
        val manager = SyncManager(repository, drive, nowUnixMillis = { 9_999 })

        val result = manager.syncBook(
            entry = entry,
            direction = SyncDirection.ImportFromTtu,
            syncStats = true,
            statsSyncMode = StatisticsSyncMode.Merge,
            syncAudioBook = true,
        )

        assertEquals(SyncResult.Imported("Title", 150), result)
        assertEquals(
            Bookmark(1, 0.5, 150, TtuSyncRules.unixMillisToAppleReferenceSeconds(2_000)),
            repository.loadBookmark(entry.root),
        )
        assertEquals(120, repository.loadStatistics(entry.root).single().charactersRead)
        assertEquals(
            SasayakiPlaybackData(lastPosition = 88.5, delay = 0.2, rate = 1.5f, audioUri = "content://audio"),
            repository.loadSasayakiPlayback(entry.root),
        )
    }

    @Test
    fun exportToTtuUploadsIosCompatibleSidecarsAndRoundsLocalBookmarkTimestamp() = runBlocking {
        val repository = BookRepository(tempFolder.root)
        val entry = repository.createEntry()
        repository.saveBookmark(entry.root, Bookmark(0, 0.5, 100, TtuSyncRules.unixMillisToAppleReferenceSeconds(4_321)))
        repository.saveStatistics(
            entry.root,
            listOf(ReadingStatistics(title = "Title", dateKey = "2026-05-12", charactersRead = 220, lastStatisticModified = 200)),
        )
        repository.saveSasayakiPlayback(entry.root, SasayakiPlaybackData(lastPosition = 45.0))
        val drive = FakeDriveSyncDataSource(
            progress = TtuProgress(9, 80, 0.4, 3_000),
            statistics = listOf(
                ReadingStatistics(title = "Title", dateKey = "2026-05-12", charactersRead = 100, lastStatisticModified = 100),
            ),
        )
        val manager = SyncManager(repository, drive, nowUnixMillis = { 9_999 })

        val result = manager.syncBook(
            entry = entry,
            direction = SyncDirection.ExportToTtu,
            syncStats = true,
            statsSyncMode = StatisticsSyncMode.Merge,
            syncAudioBook = true,
        )

        assertEquals(SyncResult.Exported("Title", 100), result)
        assertEquals(TtuProgress(9, 100, 0.5, 4_321), drive.updatedProgress)
        assertEquals(220, drive.updatedStatistics.single().charactersRead)
        assertEquals(TtuAudioBook("Title", 45.0, 9_999), drive.updatedAudioBook)
        assertEquals(4_321, TtuSyncRules.appleReferenceSecondsToUnixMillis(repository.loadBookmark(entry.root)!!.lastModified!!))
    }

    @Test
    fun staleDriveCacheIsClearedAndSyncRetriesOnce() = runBlocking {
        val repository = BookRepository(tempFolder.root)
        val entry = repository.createEntry()
        val drive = FakeDriveSyncDataSource(staleOnFirstList = true)
        val manager = SyncManager(repository, drive)

        val result = manager.syncBook(
            entry = entry,
            direction = null,
            syncStats = false,
            statsSyncMode = StatisticsSyncMode.Merge,
            syncAudioBook = false,
        )

        assertEquals(SyncResult.Synced("Title"), result)
        assertEquals(1, drive.clearCacheCalls)
        assertEquals(2, drive.listSyncFilesCalls)
    }

    private suspend fun BookRepository.createEntry(): BookEntry {
        val root = createBookDirectory("book")
        val metadata = BookMetadata(
            id = "book-id",
            title = "Title",
            cover = null,
            folder = root.name,
            lastAccess = 0.0,
        )
        saveMetadata(root, metadata)
        saveBookInfo(
            root,
            BookInfo(
                characterCount = 200,
                chapterInfo = mapOf(
                    "c0" to BookInfo.ChapterInfo(spineIndex = 0, currentTotal = 0, chapterCount = 100),
                    "c1" to BookInfo.ChapterInfo(spineIndex = 1, currentTotal = 100, chapterCount = 100),
                ),
            ),
        )
        return BookEntry(root, metadata)
    }
}

private class FakeDriveSyncDataSource(
    progress: TtuProgress? = null,
    statistics: List<ReadingStatistics>? = null,
    audioBook: TtuAudioBook? = null,
    private val staleOnFirstList: Boolean = false,
) : DriveSyncDataSource {
    private val progressFile = progress?.let { DriveFile("progress", TtuSyncRules.progressFileName(it)) }
    private val statisticsFile = statistics?.let { DriveFile("statistics", TtuSyncRules.statisticsFileName(it)) }
    private val audioBookFile = audioBook?.let { DriveFile("audio", TtuSyncRules.audioBookFileName(it)) }
    private val progressById = progressFile?.let { mapOf(it.id to requireNotNull(progress)) }.orEmpty()
    private val statisticsById = statisticsFile?.let { mapOf(it.id to requireNotNull(statistics)) }.orEmpty()
    private val audioBookById = audioBookFile?.let { mapOf(it.id to requireNotNull(audioBook)) }.orEmpty()

    var clearCacheCalls = 0
    var listSyncFilesCalls = 0
    var updatedProgress: TtuProgress? = null
    var updatedStatistics: List<ReadingStatistics> = emptyList()
    var updatedAudioBook: TtuAudioBook? = null

    override suspend fun findRootFolder(): String = "root"

    override suspend fun ensureBookFolder(
        bookTitle: String,
        rootFolderId: String,
        coverImageDataProvider: (suspend () -> ByteArray?)?,
    ): String {
        assertEquals("Title", bookTitle)
        return "book-folder"
    }

    override suspend fun listSyncFiles(folderId: String): DriveSyncFiles {
        assertEquals("book-folder", folderId)
        listSyncFilesCalls += 1
        if (staleOnFirstList && listSyncFilesCalls == 1) {
            throw GoogleDriveApiException("Not found", statusCode = 404)
        }
        return DriveSyncFiles(
            progress = progressFile,
            statistics = statisticsFile,
            audioBook = audioBookFile,
        )
    }

    override suspend fun getProgressFile(fileId: String): TtuProgress = progressById.getValue(fileId)

    override suspend fun getStatsFile(fileId: String): List<ReadingStatistics> = statisticsById.getValue(fileId)

    override suspend fun getAudioBookFile(fileId: String): TtuAudioBook = audioBookById.getValue(fileId)

    override suspend fun updateProgressFile(folderId: String, fileId: String?, progress: TtuProgress) {
        assertEquals("book-folder", folderId)
        assertTrue(fileId == null || fileId == progressFile?.id)
        updatedProgress = progress
    }

    override suspend fun updateStatsFile(folderId: String, fileId: String?, stats: List<ReadingStatistics>) {
        assertEquals("book-folder", folderId)
        assertTrue(fileId == null || fileId == statisticsFile?.id)
        updatedStatistics = stats
    }

    override suspend fun updateAudioBookFile(folderId: String, fileId: String?, audioBook: TtuAudioBook) {
        assertEquals("book-folder", folderId)
        assertTrue(fileId == null || fileId == audioBookFile?.id)
        updatedAudioBook = audioBook
    }

    override fun clearCache() {
        clearCacheCalls += 1
    }
}
