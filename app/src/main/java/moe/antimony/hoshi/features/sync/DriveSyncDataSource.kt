package moe.antimony.hoshi.features.sync

import moe.antimony.hoshi.epub.ReadingStatistics
import java.io.File

interface DriveSyncDataSource {
    suspend fun findRootFolder(): String

    suspend fun listBooks(rootFolderId: String): List<DriveFile> = emptyList()

    suspend fun ensureBookFolder(
        bookTitle: String,
        rootFolderId: String,
        coverImageDataProvider: (suspend () -> ByteArray?)? = null,
    ): String

    suspend fun listSyncFiles(folderId: String): DriveSyncFiles

    suspend fun listSyncFiles(folderIds: List<String>): Map<String, DriveSyncFiles> =
        folderIds.associateWith { listSyncFiles(it) }

    suspend fun downloadFile(
        fileId: String,
        progress: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): ByteArray = throw UnsupportedOperationException("downloadFile is not implemented.")

    suspend fun downloadFileTo(
        fileId: String,
        destination: File,
        progress: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ) {
        destination.writeBytes(downloadFile(fileId, progress))
    }

    suspend fun downloadThumbnailTo(
        thumbnailLink: String,
        destination: File,
        progress: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ) {
        throw UnsupportedOperationException("downloadThumbnailTo is not implemented.")
    }

    suspend fun getProgressFile(fileId: String): TtuProgress

    suspend fun getStatsFile(fileId: String): List<ReadingStatistics>

    suspend fun getAudioBookFile(fileId: String): TtuAudioBook

    suspend fun updateProgressFile(folderId: String, fileId: String?, progress: TtuProgress)

    suspend fun updateStatsFile(folderId: String, fileId: String?, stats: List<ReadingStatistics>)

    suspend fun updateAudioBookFile(folderId: String, fileId: String?, audioBook: TtuAudioBook)

    suspend fun uploadBookData(folderId: String, file: File) = Unit

    suspend fun trashFile(fileId: String) = Unit

    fun clearCache()
}
