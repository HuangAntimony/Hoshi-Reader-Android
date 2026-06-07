package moe.antimony.hoshi.features.bookshelf

import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.features.sync.DriveAuthStatus
import moe.antimony.hoshi.features.sync.DriveFile
import moe.antimony.hoshi.features.sync.DriveSyncDataSource
import moe.antimony.hoshi.features.sync.DriveSyncFiles
import moe.antimony.hoshi.features.sync.SyncSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteBookshelfLoaderTest {
    @Test
    fun automaticRemoteBooksLoadOnlyWhenSyncIsEnabledAndDriveIsAuthenticated() {
        assertTrue(shouldLoadRemoteBooks(SyncSettings(enabled = true), DriveAuthStatus.Connected))
        assertFalse(shouldLoadRemoteBooks(SyncSettings(enabled = false), DriveAuthStatus.Connected))
        assertFalse(shouldLoadRemoteBooks(SyncSettings(enabled = true), DriveAuthStatus.NotConnected))
        assertFalse(shouldLoadRemoteBooks(SyncSettings(enabled = true), DriveAuthStatus.MissingConfiguration))
        assertFalse(shouldLoadRemoteBooks(SyncSettings(enabled = true), DriveAuthStatus.Failed("failed")))
    }

    @Test
    fun remoteBooksFilterLocalDuplicatesBeforeListingSyncFilesLikeIos() = runBlocking {
        val drive = FakeRemoteBooksDrive(
            folders = listOf(
                DriveFile("local-id", "Local Book"),
                DriveFile("remote-id", "Remote Book"),
            ),
        )

        val entries = loadRemoteBooksOnce(
            drive = drive,
            localDriveNames = setOf("Local Book"),
        )

        assertEquals(listOf(listOf("remote-id")), drive.listSyncFileRequests)
        assertEquals(listOf("Remote Book"), entries.map { it.title })
    }

    @Test
    fun remoteBooksUseNaturalTitleOrderingLikeIosLocalizedStandardCompare() = runBlocking {
        val drive = FakeRemoteBooksDrive(
            folders = listOf(
                DriveFile("book-10", "Book 10"),
                DriveFile("book-2", "Book 2"),
                DriveFile("book-1", "book 1"),
            ),
        )

        val entries = loadRemoteBooksOnce(
            drive = drive,
            localDriveNames = emptySet(),
        )

        assertEquals(listOf("book 1", "Book 2", "Book 10"), entries.map { it.title })
    }

    private class FakeRemoteBooksDrive(
        private val folders: List<DriveFile>,
    ) : DriveSyncDataSource {
        val listSyncFileRequests = mutableListOf<List<String>>()

        override suspend fun findRootFolder(): String = "root"

        override suspend fun listBooks(rootFolderId: String): List<DriveFile> = folders

        override suspend fun ensureBookFolder(
            bookTitle: String,
            rootFolderId: String,
            coverImageDataProvider: (suspend () -> ByteArray?)?,
        ): String = error("not used")

        override suspend fun listSyncFiles(folderId: String): DriveSyncFiles = error("batched listing expected")

        override suspend fun listSyncFiles(folderIds: List<String>): Map<String, DriveSyncFiles> {
            listSyncFileRequests += folderIds
            return folderIds.associateWith { folderId ->
                DriveSyncFiles(
                    bookData = DriveFile("bookdata-$folderId", "bookdata_1_6_10_1000_1000.zip"),
                    cover = null,
                    progress = null,
                    statistics = null,
                    audioBook = null,
                )
            }
        }

        override suspend fun getProgressFile(fileId: String) = error("not used")

        override suspend fun getStatsFile(fileId: String) = error("not used")

        override suspend fun getAudioBookFile(fileId: String) = error("not used")

        override suspend fun updateProgressFile(
            folderId: String,
            fileId: String?,
            progress: moe.antimony.hoshi.features.sync.TtuProgress,
        ) = error("not used")

        override suspend fun updateStatsFile(
            folderId: String,
            fileId: String?,
            stats: List<moe.antimony.hoshi.epub.ReadingStatistics>,
        ) = error("not used")

        override suspend fun updateAudioBookFile(
            folderId: String,
            fileId: String?,
            audioBook: moe.antimony.hoshi.features.sync.TtuAudioBook,
        ) = error("not used")

        override fun clearCache() = Unit
    }
}
