package moe.antimony.hoshi.features.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncSettingsRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun emitsIosUploadBooksDefaultWhenNoSettingsWereSaved() = runBlocking {
        repository().use { repository ->
            val settings = repository.settings.first()

            assertFalse(settings.enabled)
            assertTrue(settings.uploadBooks)
        }
    }

    @Test
    fun persistsUploadBooksDisabledOverride() = runBlocking {
        repository().use { repository ->
            repository.update { it.copy(uploadBooks = false) }

            assertFalse(repository.settings.first().uploadBooks)
        }
    }

    @Test
    fun clearsGoogleDriveCacheThroughRepositoryBoundary() = runBlocking {
        val drive = FakeDriveSyncDataSource()
        repository(drive).use { repository ->
            repository.clearGoogleDriveCache()
        }

        assertEquals(1, drive.clearCacheCalls)
    }

    private fun repository(drive: DriveSyncDataSource = FakeDriveSyncDataSource()): RepositoryHandle {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tempFolder.newFile("sync-settings.preferences_pb") },
        )
        return RepositoryHandle(SyncSettingsRepository(dataStore, drive), scope)
    }

    private class RepositoryHandle(
        private val repository: SyncSettingsRepository,
        private val scope: CoroutineScope,
    ) : AutoCloseable {
        val settings = repository.settings

        suspend fun update(transform: (SyncSettings) -> SyncSettings) {
            repository.update(transform)
        }

        suspend fun clearGoogleDriveCache() {
            repository.clearGoogleDriveCache()
        }

        override fun close() {
            scope.cancel()
        }
    }

    private class FakeDriveSyncDataSource : DriveSyncDataSource {
        var clearCacheCalls = 0

        override suspend fun findRootFolder(): String = "root"

        override suspend fun ensureBookFolder(
            bookTitle: String,
            rootFolderId: String,
            coverImageDataProvider: (suspend () -> ByteArray?)?,
        ): String = "folder"

        override suspend fun listSyncFiles(folderId: String): DriveSyncFiles =
            DriveSyncFiles(bookData = null, progress = null, statistics = null, audioBook = null)

        override suspend fun getProgressFile(fileId: String): TtuProgress =
            TtuProgress(dataId = 0, exploredCharCount = 0, progress = 0.0, lastBookmarkModified = 0L)

        override suspend fun getStatsFile(fileId: String): List<moe.antimony.hoshi.epub.ReadingStatistics> =
            emptyList()

        override suspend fun getAudioBookFile(fileId: String): TtuAudioBook =
            TtuAudioBook(title = "", playbackPosition = 0.0, lastAudioBookModified = 0L)

        override suspend fun updateProgressFile(folderId: String, fileId: String?, progress: TtuProgress) = Unit

        override suspend fun updateStatsFile(
            folderId: String,
            fileId: String?,
            stats: List<moe.antimony.hoshi.epub.ReadingStatistics>,
        ) = Unit

        override suspend fun updateAudioBookFile(folderId: String, fileId: String?, audioBook: TtuAudioBook) = Unit

        override fun clearCache() {
            clearCacheCalls += 1
        }
    }
}
