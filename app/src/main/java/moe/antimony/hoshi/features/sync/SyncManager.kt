package moe.antimony.hoshi.features.sync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.SasayakiPlaybackData
import java.io.File

@Singleton
class SyncManager private constructor(
    private val bookRepository: BookRepository,
    private val drive: DriveSyncDataSource,
    private val ioDispatcher: CoroutineDispatcher,
    private val nowUnixMillis: () -> Long,
    private val bookDataExporter: suspend (BookEntry) -> File,
) {
    @Inject
    constructor(
        bookRepository: BookRepository,
        drive: DriveSyncDataSource,
        bookDataConverter: TtuBookDataConverter,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(
        bookRepository = bookRepository,
        drive = drive,
        ioDispatcher = ioDispatcher,
        nowUnixMillis = { System.currentTimeMillis() },
        bookDataExporter = bookDataConverter::exportBookData,
    )

    constructor(
        bookRepository: BookRepository,
        drive: DriveSyncDataSource,
    ) : this(
        bookRepository = bookRepository,
        drive = drive,
        ioDispatcher = Dispatchers.IO,
        nowUnixMillis = { System.currentTimeMillis() },
        bookDataExporter = { error("TTU bookdata export is not configured.") },
    )

    constructor(
        bookRepository: BookRepository,
        drive: DriveSyncDataSource,
        nowUnixMillis: () -> Long,
        bookDataExporter: suspend (BookEntry) -> File = { error("TTU bookdata export is not configured.") },
    ) : this(
        bookRepository = bookRepository,
        drive = drive,
        ioDispatcher = Dispatchers.IO,
        nowUnixMillis = nowUnixMillis,
        bookDataExporter = bookDataExporter,
    )

    constructor(
        bookRepository: BookRepository,
        drive: DriveSyncDataSource,
        bookDataExporter: suspend (BookEntry) -> File,
    ) : this(
        bookRepository = bookRepository,
        drive = drive,
        ioDispatcher = Dispatchers.IO,
        nowUnixMillis = { System.currentTimeMillis() },
        bookDataExporter = bookDataExporter,
    )

    suspend fun syncBook(
        entry: BookEntry,
        direction: SyncDirection?,
        syncStats: Boolean,
        statsSyncMode: StatisticsSyncMode,
        syncAudioBook: Boolean,
        importOnly: Boolean = false,
        syncBookData: Boolean = false,
    ): SyncResult =
        try {
            syncBookOnce(entry, direction, syncStats, statsSyncMode, syncAudioBook, importOnly, syncBookData)
        } catch (error: GoogleDriveApiException) {
            if (!error.isStaleCacheError) throw error
            drive.clearCache()
            syncBookOnce(entry, direction, syncStats, statsSyncMode, syncAudioBook, importOnly, syncBookData)
        }

    private suspend fun syncBookOnce(
        entry: BookEntry,
        direction: SyncDirection?,
        syncStats: Boolean,
        statsSyncMode: StatisticsSyncMode,
        syncAudioBook: Boolean,
        importOnly: Boolean,
        syncBookData: Boolean,
    ): SyncResult {
        val title = entry.metadata.title ?: return SyncResult.Skipped
        entry.metadata.folder ?: return SyncResult.Skipped
        val displayTitle = entry.displayTitle

        val rootFolderId = drive.findRootFolder()
        val driveFolderId = drive.ensureBookFolder(
            bookTitle = title,
            rootFolderId = rootFolderId,
            coverImageDataProvider = {
                withContext(ioDispatcher) {
                    bookRepository.coverFile(entry)?.takeIf { it.isFile }?.readBytes()
                }
            },
        )
        val localBookmark = bookRepository.loadBookmark(entry.root)
        val syncFiles = drive.listSyncFiles(driveFolderId)
        val syncDirection = direction ?: TtuSyncRules.determineDirection(localBookmark, syncFiles.progress)

        if (syncBookData && !importOnly && syncDirection != SyncDirection.ImportFromTtu && syncFiles.bookData == null) {
            drive.uploadBookData(driveFolderId, bookDataExporter(entry))
        }

        if (syncDirection == SyncDirection.Synced) {
            return SyncResult.Synced(displayTitle)
        }
        if (importOnly && syncDirection != SyncDirection.ImportFromTtu) {
            return SyncResult.Skipped
        }

        val progressFileId = syncFiles.progress?.id
        val statsFileId = if (syncStats) syncFiles.statistics?.id else null
        val audioBookFileId = if (syncAudioBook) syncFiles.audioBook?.id else null

        return when (syncDirection) {
            SyncDirection.ImportFromTtu -> importFromTtu(
                title = displayTitle,
                entry = entry,
                progressFileId = progressFileId,
                statsFileId = statsFileId,
                audioBookFileId = audioBookFileId,
                syncStats = syncStats,
                statsSyncMode = statsSyncMode,
                syncAudioBook = syncAudioBook,
            )
            SyncDirection.ExportToTtu -> exportToTtu(
                title = displayTitle,
                entry = entry,
                driveFolderId = driveFolderId,
                progressFileId = progressFileId,
                statsFileId = statsFileId,
                audioBookFileId = audioBookFileId,
                localBookmark = localBookmark,
                syncStats = syncStats,
                statsSyncMode = statsSyncMode,
                syncAudioBook = syncAudioBook,
            )
            SyncDirection.Synced -> SyncResult.Synced(displayTitle)
        }
    }

    private suspend fun importFromTtu(
        title: String,
        entry: BookEntry,
        progressFileId: String?,
        statsFileId: String?,
        audioBookFileId: String?,
        syncStats: Boolean,
        statsSyncMode: StatisticsSyncMode,
        syncAudioBook: Boolean,
    ): SyncResult {
        val progress = progressFileId?.let { drive.getProgressFile(it) } ?: return SyncResult.Skipped
        importProgress(entry, progress)
        if (syncStats) {
            val localStats = bookRepository.loadStatistics(entry.root)
            val remoteStats = statsFileId?.let { drive.getStatsFile(it) }.orEmpty()
            val merged = TtuSyncRules.mergeStatistics(localStats, remoteStats, statsSyncMode)
            if (merged.isNotEmpty()) {
                bookRepository.saveStatistics(entry.root, merged)
            }
        }
        if (syncAudioBook) {
            audioBookFileId?.let { fileId ->
                importAudioBook(entry, drive.getAudioBookFile(fileId))
            }
        }
        return SyncResult.Imported(title, progress.exploredCharCount)
    }

    private suspend fun exportToTtu(
        title: String,
        entry: BookEntry,
        driveFolderId: String,
        progressFileId: String?,
        statsFileId: String?,
        audioBookFileId: String?,
        localBookmark: Bookmark?,
        syncStats: Boolean,
        statsSyncMode: StatisticsSyncMode,
        syncAudioBook: Boolean,
    ): SyncResult {
        val bookmark = localBookmark ?: return SyncResult.Skipped
        val remoteProgress = progressFileId?.let { drive.getProgressFile(it) }
        exportProgress(entry, driveFolderId, progressFileId, bookmark, remoteProgress)

        if (syncStats) {
            val remoteStats = statsFileId?.let { drive.getStatsFile(it) }.orEmpty()
            val localStats = bookRepository.loadStatistics(entry.root)
            val statsToExport = TtuSyncRules.mergeStatistics(remoteStats, localStats, statsSyncMode)
            if (statsToExport.isNotEmpty()) {
                drive.updateStatsFile(driveFolderId, statsFileId, statsToExport)
            }
        }

        if (syncAudioBook) {
            val playback = bookRepository.loadSasayakiPlayback(entry.root)
            if (playback != null) {
                drive.updateAudioBookFile(
                    folderId = driveFolderId,
                    fileId = audioBookFileId,
                    audioBook = TtuAudioBook(
                        title = title,
                        playbackPosition = playback.lastPosition,
                        lastAudioBookModified = nowUnixMillis(),
                    ),
                )
            }
        }
        return SyncResult.Exported(title, bookmark.characterCount)
    }

    private suspend fun importProgress(entry: BookEntry, progress: TtuProgress) {
        val bookInfo = bookRepository.loadBookInfo(entry.root) ?: return
        val resolved = bookInfo.resolveTtuCharacterPosition(progress.exploredCharCount)
        bookRepository.saveBookmark(
            entry.root,
            Bookmark(
                chapterIndex = resolved?.spineIndex ?: 0,
                progress = resolved?.progress ?: 0.0,
                characterCount = progress.exploredCharCount,
                lastModified = TtuSyncRules.unixMillisToAppleReferenceSeconds(progress.lastBookmarkModified),
            ),
        )
    }

    private suspend fun exportProgress(
        entry: BookEntry,
        driveFolderId: String,
        progressFileId: String?,
        localBookmark: Bookmark,
        remoteProgress: TtuProgress?,
    ) {
        val bookInfo = bookRepository.loadBookInfo(entry.root) ?: return
        val lastModified = localBookmark.lastModified ?: return
        val unixTimestamp = TtuSyncRules.appleReferenceSecondsToUnixMillis(lastModified)
        val progress = TtuProgress(
            dataId = remoteProgress?.dataId ?: 0,
            exploredCharCount = localBookmark.characterCount,
            progress = if (bookInfo.characterCount > 0) {
                localBookmark.characterCount.toDouble() / bookInfo.characterCount.toDouble()
            } else {
                0.0
            },
            lastBookmarkModified = unixTimestamp,
        )
        drive.updateProgressFile(driveFolderId, progressFileId, progress)
        bookRepository.saveBookmark(
            entry.root,
            localBookmark.copy(lastModified = TtuSyncRules.unixMillisToAppleReferenceSeconds(unixTimestamp)),
        )
    }

    private suspend fun importAudioBook(entry: BookEntry, audioBook: TtuAudioBook) {
        val playback = bookRepository.loadSasayakiPlayback(entry.root)
            ?: SasayakiPlaybackData(lastPosition = 0.0)
        bookRepository.saveSasayakiPlayback(
            entry.root,
            playback.copy(lastPosition = audioBook.playbackPosition),
        )
    }
}
