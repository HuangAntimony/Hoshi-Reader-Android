package moe.antimony.hoshi.features.sync

import moe.antimony.hoshi.epub.BookInfo
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.Bookmark
import java.io.File

interface SyncAccessTokenProvider {
    fun accessTokenOrNull(): String?
}

interface SyncBookStorage {
    val rootDir: File

    fun loadBookmark(folder: String): Bookmark?
    fun saveBookmark(folder: String, bookmark: Bookmark)
    fun loadBookInfo(folder: String): BookInfo?
}

interface DriveSyncGateway {
    fun rootFolderId(accessToken: String): String
    fun bookFolderId(accessToken: String, rootFolderId: String, title: String): String
    fun listSyncFiles(accessToken: String, folderId: String): DriveSyncFiles
    fun downloadProgress(accessToken: String, fileId: String): TtuProgress
    fun uploadProgress(accessToken: String, folderId: String, fileId: String?, progress: TtuProgress)
}

class GoogleDriveSyncGateway(private val client: GoogleDriveClient) : DriveSyncGateway {
    override fun rootFolderId(accessToken: String): String =
        client.findOrCreateRootFolder(accessToken)

    override fun bookFolderId(accessToken: String, rootFolderId: String, title: String): String =
        client.ensureBookFolder(accessToken, rootFolderId, title)

    override fun listSyncFiles(accessToken: String, folderId: String): DriveSyncFiles =
        client.listSyncFiles(accessToken, folderId)

    override fun downloadProgress(accessToken: String, fileId: String): TtuProgress =
        client.downloadProgress(accessToken, fileId)

    override fun uploadProgress(accessToken: String, folderId: String, fileId: String?, progress: TtuProgress) {
        client.uploadProgress(accessToken, folderId, fileId, progress)
    }
}

class DriveSyncManager(
    private val tokenProvider: SyncAccessTokenProvider,
    private val drive: DriveSyncGateway,
    private val storage: SyncBookStorage,
) {
    fun syncAll(books: List<BookMetadata>): List<SyncResult> =
        books.map { book ->
            runCatching { syncBook(book) }.getOrElse {
                SyncResult.Failed(book.title, it.message ?: "Sync failed.")
            }
        }

    fun syncBook(book: BookMetadata): SyncResult {
        val title = book.title?.takeIf { it.isNotBlank() }
            ?: return SyncResult.Skipped(book.title, "Book title is missing.")
        val folder = book.folder?.takeIf { it.isNotBlank() }
            ?: return SyncResult.Skipped(title, "Book folder is missing.")
        val accessToken = tokenProvider.accessTokenOrNull()
            ?: return SyncResult.Skipped(title, "Not signed in.")

        val rootFolderId = drive.rootFolderId(accessToken)
        val bookFolderId = drive.bookFolderId(accessToken, rootFolderId, title)
        val syncFiles = drive.listSyncFiles(accessToken, bookFolderId)
        val localBookmark = storage.loadBookmark(folder)
        val remoteTimestamp = syncFiles.progress?.let(::parseProgressTimestampMillis)
        val direction = determineDirection(localBookmark, remoteTimestamp)

        return when (direction) {
            SyncDirection.Synced -> SyncResult.Synced(title)
            SyncDirection.ExportToTtu -> exportProgress(title, folder, bookFolderId, syncFiles.progress?.id, localBookmark)
            SyncDirection.ImportFromTtu -> importProgress(title, folder, requireNotNull(syncFiles.progress))
        }
    }

    private fun determineDirection(local: Bookmark?, remoteTimestampMillis: Long?): SyncDirection {
        val localTimestampMillis = local?.lastModified?.let(::appleReferenceSecondsToUnixMillis)
        return when {
            localTimestampMillis == null && remoteTimestampMillis == null -> SyncDirection.Synced
            localTimestampMillis == null -> SyncDirection.ImportFromTtu
            remoteTimestampMillis == null -> SyncDirection.ExportToTtu
            localTimestampMillis > remoteTimestampMillis -> SyncDirection.ExportToTtu
            remoteTimestampMillis > localTimestampMillis -> SyncDirection.ImportFromTtu
            else -> SyncDirection.Synced
        }
    }

    private fun exportProgress(
        title: String,
        folder: String,
        driveFolderId: String,
        progressFileId: String?,
        localBookmark: Bookmark?,
    ): SyncResult {
        val bookmark = localBookmark ?: return SyncResult.Skipped(title, "Bookmark is missing.")
        val bookInfo = storage.loadBookInfo(folder) ?: return SyncResult.Skipped(title, "Book info is missing.")
        val lastModifiedMillis = bookmark.lastModified?.let(::appleReferenceSecondsToUnixMillis)
            ?: return SyncResult.Skipped(title, "Bookmark timestamp is missing.")
        val progress = TtuProgress(
            dataId = 0,
            exploredCharCount = bookmark.characterCount,
            progress = bookmark.characterCount.toDouble().div(bookInfo.characterCount.toDouble()).coerceIn(0.0, 1.0),
            lastBookmarkModified = lastModifiedMillis,
        )
        val accessToken = requireNotNull(tokenProvider.accessTokenOrNull())
        drive.uploadProgress(accessToken, driveFolderId, progressFileId, progress)
        storage.saveBookmark(
            folder,
            bookmark.copy(lastModified = unixMillisToAppleReferenceSeconds(lastModifiedMillis)),
        )
        return SyncResult.Exported(title, bookmark.characterCount)
    }

    private fun importProgress(title: String, folder: String, remoteProgressFile: DriveFile): SyncResult {
        val bookInfo = storage.loadBookInfo(folder) ?: return SyncResult.Skipped(title, "Book info is missing.")
        val accessToken = requireNotNull(tokenProvider.accessTokenOrNull())
        val progress = drive.downloadProgress(accessToken, remoteProgressFile.id)
        val resolved = bookInfo.resolveCharacterPosition(progress.exploredCharCount)
            ?: return SyncResult.Skipped(title, "Book info is missing.")
        storage.saveBookmark(
            folder,
            Bookmark(
                chapterIndex = resolved.chapterIndex,
                progress = resolved.progress,
                characterCount = progress.exploredCharCount,
                lastModified = unixMillisToAppleReferenceSeconds(progress.lastBookmarkModified),
            ),
        )
        return SyncResult.Imported(title, progress.exploredCharCount)
    }
}
