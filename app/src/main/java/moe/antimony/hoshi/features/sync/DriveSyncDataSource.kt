package moe.antimony.hoshi.features.sync

import moe.antimony.hoshi.epub.ReadingStatistics

interface DriveSyncDataSource {
    suspend fun findRootFolder(): String

    suspend fun ensureBookFolder(
        bookTitle: String,
        rootFolderId: String,
        coverImageDataProvider: (suspend () -> ByteArray?)? = null,
    ): String

    suspend fun listSyncFiles(folderId: String): DriveSyncFiles

    suspend fun getProgressFile(fileId: String): TtuProgress

    suspend fun getStatsFile(fileId: String): List<ReadingStatistics>

    suspend fun getAudioBookFile(fileId: String): TtuAudioBook

    suspend fun updateProgressFile(folderId: String, fileId: String?, progress: TtuProgress)

    suspend fun updateStatsFile(folderId: String, fileId: String?, stats: List<ReadingStatistics>)

    suspend fun updateAudioBookFile(folderId: String, fileId: String?, audioBook: TtuAudioBook)

    fun clearCache()
}
