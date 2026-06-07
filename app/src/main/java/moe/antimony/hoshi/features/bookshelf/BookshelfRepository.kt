package moe.antimony.hoshi.features.bookshelf

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.di.CacheDir
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.dictionary.DictionaryRepository
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.BookShelf
import moe.antimony.hoshi.epub.BookSortOption
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubBookParser
import moe.antimony.hoshi.epub.isUuidString
import moe.antimony.hoshi.features.sync.StatisticsSyncMode
import moe.antimony.hoshi.features.sync.DriveAuthStatus
import moe.antimony.hoshi.features.sync.DriveAuthorizer
import moe.antimony.hoshi.features.sync.DriveSyncDataSource
import moe.antimony.hoshi.features.sync.GoogleDriveApiException
import moe.antimony.hoshi.features.sync.SyncDirection
import moe.antimony.hoshi.features.sync.SyncManager
import moe.antimony.hoshi.features.sync.SyncResult
import moe.antimony.hoshi.features.sync.SyncSettingsRepository
import moe.antimony.hoshi.features.sync.TtuBookDataConverter
import moe.antimony.hoshi.features.sync.TtuProgress
import moe.antimony.hoshi.features.sync.TtuSyncRules
import moe.antimony.hoshi.features.sync.TtuAudioBook
import moe.antimony.hoshi.features.sync.googleDriveCoverCacheDirectory
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.features.sync.resolveTtuCharacterPosition
import java.io.File
import java.text.Collator
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.first

internal interface BookshelfRepository {
    suspend fun loadBooks(sortOption: BookSortOption): BookshelfLoadResult
    suspend fun loadRemoteBooks(localEntries: List<BookEntry>): RemoteBookshelfLoadResult
    suspend fun openBook(entry: BookEntry): String
    suspend fun importBook(uri: Uri): String
    suspend fun exportBook(entry: BookEntry, uri: Uri)
    suspend fun importRemoteBook(
        entry: RemoteBookEntry,
        syncStats: Boolean,
        syncAudioBook: Boolean,
        onProgress: (Double) -> Unit,
    ): String
    suspend fun deleteRemoteBook(entry: RemoteBookEntry)
    suspend fun deleteBook(entry: BookEntry)
    suspend fun deleteBooks(entries: Collection<BookEntry>)
    suspend fun moveBooks(bookIds: Set<String>, shelfName: String?)
    suspend fun createShelf(name: String)
    suspend fun deleteShelf(name: String)
    suspend fun moveShelf(fromIndex: Int, toIndex: Int)
    suspend fun markRead(entry: BookEntry)
    suspend fun renameBook(entry: BookEntry, title: String?)
    suspend fun changeSort(sortOption: BookSortOption)
    suspend fun changeShowReading(showReading: Boolean)
    suspend fun rebuildLookupQuery()
    suspend fun syncBook(
        entry: BookEntry,
        direction: SyncDirection?,
        syncStats: Boolean,
        statsSyncMode: StatisticsSyncMode,
        syncAudioBook: Boolean,
    ): SyncResult
}

@Singleton
internal class AndroidBookshelfRepository @Inject constructor(
    private val contentResolver: ContentResolver,
    private val bookRepository: BookRepository,
    private val dictionaryRepository: DictionaryRepository,
    private val settingsRepository: BookshelfSettingsRepository,
    private val syncSettingsRepository: SyncSettingsRepository,
    private val syncManager: SyncManager,
    private val drive: DriveSyncDataSource,
    private val driveAuthorizer: DriveAuthorizer,
    private val ttuBookDataConverter: TtuBookDataConverter,
    private val bookParser: EpubBookParser,
    @param:CacheDir private val cacheDir: File,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BookshelfRepository {
    override suspend fun loadBooks(sortOption: BookSortOption): BookshelfLoadResult = withContext(ioDispatcher) {
        val entries = bookRepository.loadBookEntries(sortOption)
        val shelves = bookRepository.loadShelves()
        BookshelfLoadResult(
            entries = entries,
            progressById = loadBookProgressById(entries, bookRepository),
            coverSourcesById = loadBookCoverSourcesById(entries, bookRepository),
            shelves = shelves,
            settings = settingsRepository.settings.first(),
        )
    }

    override suspend fun loadRemoteBooks(localEntries: List<BookEntry>): RemoteBookshelfLoadResult = withContext(ioDispatcher) {
        val remoteEntries = loadRemoteBookEntries(localEntries)
        RemoteBookshelfLoadResult(
            remoteEntries = remoteEntries,
            remoteProgressById = remoteEntries.mapNotNull { remote ->
                TtuSyncRules.parseProgressValue(remote.syncFiles.progress)?.let { remote.id to it }
            }.toMap(),
            remoteCoverSourcesById = loadRemoteCoverSources(remoteEntries, cacheDir) { cover, destination, progress ->
                val thumbnailLink = cover.thumbnailLink
                if (thumbnailLink != null) {
                    drive.downloadThumbnailTo(thumbnailLink, destination, progress)
                } else {
                    drive.downloadFileTo(cover.id, destination, progress)
                }
            },
        )
    }

    override suspend fun openBook(entry: BookEntry): String = withContext(ioDispatcher) {
        val metadata = bookRepository.loadMetadata(entry.root) ?: entry.metadata
        bookRepository.saveMetadata(
            entry.root,
            metadata.copy(lastAccess = bookRepository.currentAppleReferenceDateSeconds()),
        )
        readerBookId(entry.root)
    }

    override suspend fun importBook(uri: Uri): String = withContext(ioDispatcher) {
        val root = bookRepository.importBook(contentResolver, uri)
        val parsedBook = bookParser.parse(root)
        saveMetadata(root, parsedBook, bookRepository.loadMetadata(root))
        saveBookInfo(root, parsedBook)
        readerBookId(root)
    }

    override suspend fun exportBook(entry: BookEntry, uri: Uri): Unit = withContext(ioDispatcher) {
        contentResolver.openOutputStream(uri)?.use { output ->
            bookRepository.exportEpub(entry, output)
        } ?: error("Unable to open EPUB export destination.")
    }

    override suspend fun importRemoteBook(
        entry: RemoteBookEntry,
        syncStats: Boolean,
        syncAudioBook: Boolean,
        onProgress: (Double) -> Unit,
    ): String = withContext(ioDispatcher) {
        val bookDataFile = entry.syncFiles.bookData ?: error("Remote bookdata is missing.")
        val tempRoot = File.createTempFile("remote-bookdata-", ".zip", cacheDir)
        try {
            drive.downloadFileTo(bookDataFile.id, tempRoot) { downloadedBytes, totalBytes ->
                val total = totalBytes?.takeIf { it > 0L }
                if (total != null) {
                    onProgress((downloadedBytes.toDouble() / total.toDouble()).coerceIn(0.0, 1.0))
                }
            }
            val imported = ttuBookDataConverter.importBookData(tempRoot)
            importRemoteSidecars(imported, entry, syncStats, syncAudioBook)
            readerBookId(imported.root)
        } finally {
            tempRoot.delete()
        }
    }

    override suspend fun deleteRemoteBook(entry: RemoteBookEntry) = withContext(ioDispatcher) {
        drive.trashFile(entry.folderId)
    }

    override suspend fun deleteBook(entry: BookEntry) = withContext(ioDispatcher) {
        bookRepository.deleteBook(entry.root, ::releasePersistedSasayakiAudioUri)
    }

    override suspend fun deleteBooks(entries: Collection<BookEntry>) = withContext(ioDispatcher) {
        entries.forEach { bookRepository.deleteBook(it.root, ::releasePersistedSasayakiAudioUri) }
    }

    override suspend fun moveBooks(bookIds: Set<String>, shelfName: String?) = withContext(ioDispatcher) {
        val shelves = bookRepository.loadShelves()
            .map { shelf -> shelf.copy(bookIds = shelf.bookIds.filterNot { it in bookIds }) }
            .map { shelf ->
                if (shelf.name == shelfName) {
                    shelf.copy(bookIds = (shelf.bookIds + bookIds).distinct())
                } else {
                    shelf
                }
            }
        bookRepository.saveShelves(shelves)
    }

    override suspend fun createShelf(name: String) = withContext(ioDispatcher) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withContext
        val shelves = bookRepository.loadShelves()
        if (shelves.none { it.name == trimmed }) {
            bookRepository.saveShelves(shelves + BookShelf(trimmed, emptyList()))
        }
    }

    override suspend fun deleteShelf(name: String) = withContext(ioDispatcher) {
        bookRepository.saveShelves(bookRepository.loadShelves().filterNot { it.name == name })
    }

    override suspend fun moveShelf(fromIndex: Int, toIndex: Int) = withContext(ioDispatcher) {
        val shelves = bookRepository.loadShelves().toMutableList()
        if (fromIndex !in shelves.indices || toIndex !in shelves.indices || fromIndex == toIndex) {
            return@withContext
        }
        val shelf = shelves.removeAt(fromIndex)
        shelves.add(toIndex, shelf)
        bookRepository.saveShelves(shelves)
    }

    override suspend fun markRead(entry: BookEntry) = withContext(ioDispatcher) {
        val bookInfo = bookRepository.loadBookInfo(entry.root) ?: return@withContext
        val lastChapter = bookInfo.chapterInfo.values
            .mapNotNull { it.spineIndex }
            .maxOrNull()
            ?: 0
        bookRepository.saveBookmark(
            entry.root,
            Bookmark(
                chapterIndex = lastChapter,
                progress = 1.0,
                characterCount = bookInfo.characterCount,
                lastModified = bookRepository.currentAppleReferenceDateSeconds(),
            ),
        )
    }

    override suspend fun renameBook(entry: BookEntry, title: String?) = withContext(ioDispatcher) {
        val metadata = bookRepository.loadMetadata(entry.root) ?: entry.metadata
        bookRepository.saveMetadata(entry.root, metadata.copy(renamedTitle = title))
    }

    override suspend fun changeSort(sortOption: BookSortOption) {
        settingsRepository.update { it.copy(sortOption = sortOption) }
    }

    override suspend fun changeShowReading(showReading: Boolean) {
        settingsRepository.update { it.copy(showReading = showReading) }
    }

    override suspend fun rebuildLookupQuery() {
        dictionaryRepository.rebuildLookupQuery()
    }

    override suspend fun syncBook(
        entry: BookEntry,
        direction: SyncDirection?,
        syncStats: Boolean,
        statsSyncMode: StatisticsSyncMode,
        syncAudioBook: Boolean,
    ): SyncResult = withContext(ioDispatcher) {
        val syncSettings = syncSettingsRepository.settings.first()
        syncManager.syncBook(
            entry = entry,
            direction = direction,
            syncStats = syncStats,
            statsSyncMode = statsSyncMode,
            syncAudioBook = syncAudioBook,
            syncBookData = syncSettings.uploadBooks,
        )
    }

    private suspend fun saveMetadata(root: File, parsedBook: EpubBook, previous: BookMetadata? = null) {
        val metadata = BookMetadata(
            id = previous?.id?.takeIf { it.isUuidString() } ?: UUID.randomUUID().toString(),
            title = parsedBook.title,
            cover = bookRepository.metadataCoverPath(root, parsedBook) ?: previous?.cover,
            folder = root.name,
            lastAccess = bookRepository.currentAppleReferenceDateSeconds(),
            renamedTitle = previous?.renamedTitle,
            epub = bookRepository.epubFile(root, previous)?.name ?: previous?.epub,
        )
        bookRepository.saveMetadata(root, metadata)
    }

    private suspend fun saveBookInfo(root: File, parsedBook: EpubBook) {
        bookRepository.saveBookInfo(root, parsedBook.bookInfo)
    }

    private suspend fun loadRemoteBookEntries(localEntries: List<BookEntry>): List<RemoteBookEntry> {
        val syncSettings = syncSettingsRepository.settings.first()
        val authStatus = driveAuthorizer.status()
        if (!shouldLoadRemoteBooks(syncSettings, authStatus)) return emptyList()
        val localDriveNames = localEntries.mapTo(mutableSetOf()) { entry ->
            TtuSyncRules.sanitizeTtuFilename(entry.metadata.title ?: entry.displayTitle)
        }
        return runCatching { loadRemoteBooksOnce(drive, localDriveNames) }
            .recoverCatching { error ->
                if (error !is GoogleDriveApiException || !error.isStaleCacheError) throw error
                drive.clearCache()
                loadRemoteBooksOnce(drive, localDriveNames)
            }
            .getOrThrow()
    }

    private suspend fun importRemoteSidecars(
        entry: BookEntry,
        remote: RemoteBookEntry,
        syncStats: Boolean,
        syncAudioBook: Boolean,
    ) {
        remote.syncFiles.progress?.let { file ->
            val progress = remoteJson.decodeFromString(TtuProgress.serializer(), drive.downloadFile(file.id).decodeToString())
            val bookInfo = bookRepository.loadBookInfo(entry.root) ?: return@let
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
        if (syncStats) {
            remote.syncFiles.statistics?.let { file ->
                val stats = remoteJson.decodeFromString(ListSerializer(moe.antimony.hoshi.epub.ReadingStatistics.serializer()), drive.downloadFile(file.id).decodeToString())
                bookRepository.saveStatistics(entry.root, stats)
            }
        }
        if (syncAudioBook) {
            remote.syncFiles.audioBook?.let { file ->
                val audioBook = remoteJson.decodeFromString(TtuAudioBook.serializer(), drive.downloadFile(file.id).decodeToString())
                val existing = bookRepository.loadSasayakiPlayback(entry.root) ?: moe.antimony.hoshi.epub.SasayakiPlaybackData(lastPosition = 0.0)
                bookRepository.saveSasayakiPlayback(entry.root, existing.copy(lastPosition = audioBook.playbackPosition))
            }
        }
    }

    private suspend fun readerBookId(root: File): String =
        bookRepository.loadMetadata(root)?.id ?: root.name

    private fun releasePersistedSasayakiAudioUri(uriString: String) {
        contentResolver.releasePersistableUriPermission(
            Uri.parse(uriString),
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }
}

private val remoteJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal fun shouldLoadRemoteBooks(syncSettings: moe.antimony.hoshi.features.sync.SyncSettings, authStatus: DriveAuthStatus): Boolean =
    syncSettings.enabled && authStatus is DriveAuthStatus.Connected

internal suspend fun loadRemoteBooksOnce(
    drive: DriveSyncDataSource,
    localDriveNames: Set<String>,
): List<RemoteBookEntry> {
    val rootFolderId = drive.findRootFolder()
    val folders = drive.listBooks(rootFolderId)
        .filterNot { folder -> folder.name in localDriveNames }
    val syncFilesByFolder = drive.listSyncFiles(folders.map { it.id })
    return folders.mapNotNull { folder ->
        val syncFiles = syncFilesByFolder[folder.id] ?: return@mapNotNull null
        if (syncFiles.bookData == null) return@mapNotNull null
        RemoteBookEntry(
            id = folder.id,
            folderId = folder.id,
            folderName = folder.name,
            title = TtuSyncRules.desanitizeTtuFilename(folder.name),
            syncFiles = syncFiles,
        )
    }.sortedWith(compareByIosLikeTitle { it.title })
}

private fun <T> compareByIosLikeTitle(selector: (T) -> String): Comparator<T> {
    val collator = Collator.getInstance(Locale.getDefault()).apply {
        strength = Collator.PRIMARY
        decomposition = Collator.CANONICAL_DECOMPOSITION
    }
    return Comparator { left, right ->
        val leftTitle = selector(left)
        val rightTitle = selector(right)
        compareIosLikeTitle(leftTitle, rightTitle, collator).takeIf { it != 0 }
            ?: String.CASE_INSENSITIVE_ORDER.compare(leftTitle, rightTitle)
    }
}

private fun compareIosLikeTitle(left: String, right: String, collator: Collator): Int {
    var leftIndex = 0
    var rightIndex = 0
    while (leftIndex < left.length && rightIndex < right.length) {
        val leftDigit = left[leftIndex].isDigit()
        val rightDigit = right[rightIndex].isDigit()
        if (leftDigit && rightDigit) {
            val leftEnd = left.nextDigitRunEnd(leftIndex)
            val rightEnd = right.nextDigitRunEnd(rightIndex)
            val leftNumber = left.substring(leftIndex, leftEnd).trimStart('0').ifEmpty { "0" }
            val rightNumber = right.substring(rightIndex, rightEnd).trimStart('0').ifEmpty { "0" }
            leftNumber.length.compareTo(rightNumber.length).takeIf { it != 0 }?.let { return it }
            leftNumber.compareTo(rightNumber).takeIf { it != 0 }?.let { return it }
            leftIndex = leftEnd
            rightIndex = rightEnd
        } else {
            val leftEnd = if (leftDigit) left.nextDigitRunEnd(leftIndex) else left.nextTextRunEnd(leftIndex)
            val rightEnd = if (rightDigit) right.nextDigitRunEnd(rightIndex) else right.nextTextRunEnd(rightIndex)
            collator.compare(left.substring(leftIndex, leftEnd), right.substring(rightIndex, rightEnd))
                .takeIf { it != 0 }
                ?.let { return it }
            leftIndex = leftEnd
            rightIndex = rightEnd
        }
    }
    return (left.length - leftIndex).compareTo(right.length - rightIndex)
}

private fun String.nextDigitRunEnd(start: Int): Int {
    var index = start
    while (index < length && this[index].isDigit()) index += 1
    return index
}

private fun String.nextTextRunEnd(start: Int): Int {
    var index = start
    while (index < length && !this[index].isDigit()) index += 1
    return index
}

internal suspend fun loadRemoteCoverSources(
    remoteEntries: List<RemoteBookEntry>,
    cacheDir: File,
    downloadCoverTo: suspend (
        cover: moe.antimony.hoshi.features.sync.DriveFile,
        destination: File,
        progress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) -> Unit,
): Map<String, BookCoverSource> = coroutineScope {
    val coverCache = googleDriveCoverCacheDirectory(cacheDir).also { it.mkdirs() }
    remoteEntries.map { entry ->
        async {
            val cover = entry.syncFiles.cover ?: return@async null
            val extension = cover.name.substringAfterLast('.', "jpg").ifBlank { "jpg" }
            val cached = coverCache.resolve("${cover.id}.$extension")
            runCatching {
                if (!cached.isFile) {
                    val temp = coverCache.resolve(".${cover.id}-${UUID.randomUUID()}.tmp")
                    try {
                        downloadCoverTo(cover.withIosThumbnailSize(), temp) { _, _ -> }
                        if (!temp.renameTo(cached)) {
                            temp.copyTo(cached, overwrite = true)
                            temp.delete()
                        }
                    } catch (error: Throwable) {
                        temp.delete()
                        cached.delete()
                        throw error
                    }
                }
                entry.id to cached.toBookCoverSource()
            }.getOrNull()
        }
    }.awaitAll().filterNotNull().toMap()
}

private fun moe.antimony.hoshi.features.sync.DriveFile.withIosThumbnailSize(): moe.antimony.hoshi.features.sync.DriveFile =
    copy(thumbnailLink = thumbnailLink?.replace(Regex("""=s\d+$"""), "=s768"))

internal suspend fun loadBookCoverSourcesById(
    entries: List<BookEntry>,
    bookRepository: BookRepository,
): Map<String, BookCoverSource> =
    entries.mapNotNull { entry ->
        bookRepository.coverFile(entry)?.toBookCoverSource()?.let { source ->
            entry.metadata.id to source
        }
    }.toMap()

internal fun File.toBookCoverSource(): BookCoverSource =
    BookCoverSource(
        path = absolutePath,
        cacheKey = "$absolutePath:${lastModified()}:${length()}",
    )
