package moe.antimony.hoshi.features.sync

import moe.antimony.hoshi.epub.BookInfo
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.Bookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DriveSyncManagerTest {
    @Test
    fun localOnlyBookmarkExportsProgress() {
        val book = syncBook(title = "Book")
        val storage = FakeSyncBookStorage(
            bookmarks = mutableMapOf(book.folder!! to Bookmark(0, 0.5, 50, unixMillisToAppleReferenceSeconds(1_000))),
            bookInfo = mutableMapOf(book.folder to sampleBookInfo()),
        )
        val drive = FakeDriveGateway()
        val manager = DriveSyncManager(FakeTokenProvider("token"), drive, storage)

        val result = manager.syncBook(book)

        assertEquals(SyncResult.Exported("Book", 50), result)
        assertEquals(50, drive.uploadedProgress.single().exploredCharCount)
        assertEquals(1_000L, drive.uploadedProgress.single().lastBookmarkModified)
    }

    @Test
    fun remoteOnlyProgressImportsBookmark() {
        val book = syncBook(title = "Book")
        val storage = FakeSyncBookStorage(bookInfo = mutableMapOf(book.folder!! to sampleBookInfo()))
        val drive = FakeDriveGateway(
            syncFiles = DriveSyncFiles(progress = DriveFile("remote-progress", "progress_1_6_2000_0.75.json"), statistics = null, audioBook = null),
            progress = TtuProgress(dataId = 0, exploredCharCount = 150, progress = 0.75, lastBookmarkModified = 2_000),
        )
        val manager = DriveSyncManager(FakeTokenProvider("token"), drive, storage)

        val result = manager.syncBook(book)

        assertEquals(SyncResult.Imported("Book", 150), result)
        assertEquals(Bookmark(1, 0.25, 150, unixMillisToAppleReferenceSeconds(2_000)), storage.bookmarks[book.folder])
    }

    @Test
    fun equalTimestampsAreSyncedNoOp() {
        val book = syncBook(title = "Book")
        val storage = FakeSyncBookStorage(
            bookmarks = mutableMapOf(book.folder!! to Bookmark(0, 0.5, 50, unixMillisToAppleReferenceSeconds(2_000))),
            bookInfo = mutableMapOf(book.folder to sampleBookInfo()),
        )
        val drive = FakeDriveGateway(
            syncFiles = DriveSyncFiles(progress = DriveFile("remote-progress", "progress_1_6_2000_0.5.json"), statistics = null, audioBook = null),
        )
        val manager = DriveSyncManager(FakeTokenProvider("token"), drive, storage)

        assertEquals(SyncResult.Synced("Book"), manager.syncBook(book))
        assertTrue(drive.uploadedProgress.isEmpty())
    }

    @Test
    fun missingBookInfoSkipsRemoteImport() {
        val book = syncBook(title = "Book")
        val drive = FakeDriveGateway(
            syncFiles = DriveSyncFiles(progress = DriveFile("remote-progress", "progress_1_6_2000_0.75.json"), statistics = null, audioBook = null),
            progress = TtuProgress(dataId = 0, exploredCharCount = 150, progress = 0.75, lastBookmarkModified = 2_000),
        )
        val manager = DriveSyncManager(FakeTokenProvider("token"), drive, FakeSyncBookStorage())

        assertEquals(SyncResult.Skipped("Book", "Book info is missing."), manager.syncBook(book))
    }

    @Test
    fun syncAllIsolatesPerBookFailures() {
        val good = syncBook(title = "Good", folder = "good")
        val bad = syncBook(title = "Bad", folder = "bad")
        val storage = FakeSyncBookStorage(
            bookmarks = mutableMapOf("good" to Bookmark(0, 0.5, 50, unixMillisToAppleReferenceSeconds(1_000))),
            bookInfo = mutableMapOf("good" to sampleBookInfo()),
        )
        val drive = FakeDriveGateway(failTitles = setOf("Bad"))
        val manager = DriveSyncManager(FakeTokenProvider("token"), drive, storage)

        val results = manager.syncAll(listOf(good, bad))

        assertEquals(2, results.size)
        assertEquals(SyncResult.Exported("Good", 50), results[0])
        assertEquals(SyncResult.Failed("Bad", "boom"), results[1])
    }

    private fun syncBook(title: String, folder: String = title): BookMetadata =
        BookMetadata(id = folder, title = title, cover = null, folder = folder, lastAccess = 0.0)

    private fun sampleBookInfo(): BookInfo =
        BookInfo(
            characterCount = 300,
            chapterInfo = mapOf(
                "0" to BookInfo.ChapterInfo(spineIndex = 0, currentTotal = 0, chapterCount = 100),
                "1" to BookInfo.ChapterInfo(spineIndex = 1, currentTotal = 100, chapterCount = 200),
            ),
        )
}

private class FakeTokenProvider(private val token: String?) : SyncAccessTokenProvider {
    override fun accessTokenOrNull(): String? = token
}

private class FakeSyncBookStorage(
    override val rootDir: File = File("root"),
    val bookmarks: MutableMap<String, Bookmark> = mutableMapOf(),
    val bookInfo: MutableMap<String, BookInfo> = mutableMapOf(),
) : SyncBookStorage {
    override fun loadBookmark(folder: String): Bookmark? = bookmarks[folder]

    override fun saveBookmark(folder: String, bookmark: Bookmark) {
        bookmarks[folder] = bookmark
    }

    override fun loadBookInfo(folder: String): BookInfo? = bookInfo[folder]
}

private class FakeDriveGateway(
    private val syncFiles: DriveSyncFiles = DriveSyncFiles(progress = null, statistics = null, audioBook = null),
    private val progress: TtuProgress? = null,
    private val failTitles: Set<String> = emptySet(),
) : DriveSyncGateway {
    val uploadedProgress = mutableListOf<TtuProgress>()

    override fun rootFolderId(accessToken: String): String = "root"

    override fun bookFolderId(accessToken: String, rootFolderId: String, title: String): String {
        if (title in failTitles) throw IllegalStateException("boom")
        return "folder-$title"
    }

    override fun listSyncFiles(accessToken: String, folderId: String): DriveSyncFiles = syncFiles

    override fun downloadProgress(accessToken: String, fileId: String): TtuProgress =
        requireNotNull(progress)

    override fun uploadProgress(accessToken: String, folderId: String, fileId: String?, progress: TtuProgress) {
        uploadedProgress += progress
    }
}
