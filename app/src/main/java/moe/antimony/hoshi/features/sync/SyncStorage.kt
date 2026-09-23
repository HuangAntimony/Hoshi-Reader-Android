package moe.antimony.hoshi.features.sync

import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import moe.antimony.hoshi.di.FilesDir
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.ReadingSessions
import moe.antimony.hoshi.epub.SasayakiPlaybackData
import moe.antimony.hoshi.epub.writeBookJson

@Singleton
class SyncStorage @Inject constructor(
    @param:FilesDir private val filesDir: File,
    private val books: BookRepository,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    var state = SyncState()
        private set
    private var loaded = false
    private val sidecars get() = books.sidecarDataSource
    private val statistics get() = books.statisticsStore
    private val booksDirectory get() = filesDir.resolve("Books")
    private val revision = MutableStateFlow(0)
    val booksChanged = revision.asStateFlow()
    var onChange: (() -> Unit)? = null
    var applyReaderState: (suspend (String, SyncBook, Boolean) -> Unit)? = null

    init {
        statistics.onSave = { folder, sessions -> handleSessionsChange(folder.syncKey(), sessions) }
        books.onBookChange = { key, fileType ->
            transaction {
                handleBookChange(key)
                if (fileType != null) {
                    markFileChanged(key, fileType)
                    saveChanges(false)
                }
            }
        }
        books.onShelvesChange = ::handleShelvesChange
        books.onBookImport = ::handleBookImport
    }

    suspend fun <T> transaction(action: suspend () -> T): T = withContext(ioDispatcher) {
        books.storageLock.withLock {
            if (!loaded) reload()
            action()
        }
    }

    suspend fun reload() = withContext(ioDispatcher) {
        val file = booksDirectory.resolve(".sync.json")
        state = if (file.isFile) runCatching { SyncFormat.json.decodeFromString<SyncState>(file.readText()) }.getOrDefault(SyncState()) else SyncState()
        loaded = true
    }

    suspend fun save() = withContext(ioDispatcher) {
        writeBookJson(booksDirectory.resolve(".sync.json"), SyncFormat.json.encodeToString(state))
    }

    suspend fun saveChanges(booksChanged: Boolean = true) {
        save()
        if (booksChanged) notifyBooksChanged()
        onChange?.invoke()
    }

    fun notifyBooksChanged() {
        revision.value += 1
    }

    suspend fun updateRecord(key: String, transform: (SyncRecord) -> SyncRecord) = transaction {
        state = state.copy(books = state.books + (key to transform(state.books.getValue(key))))
    }

    suspend fun setShelvesPending(pending: Boolean) = transaction {
        state = state.copy(shelvesPending = pending)
    }

    suspend fun markPending(key: String) = transaction {
        if (!state.books.getValue(key).pending) {
            updateRecord(key) { it.copy(pending = true) }
            save()
        }
        onChange?.invoke()
    }

    suspend fun resetSyncState() = transaction {
        for ((key, record) in state.books) {
            val archived = books.loadMetadata(bookDirectory(key)) == null
            updateRecord(key) { record.copy(generation = if (archived) 0 else 1, deleted = archived, files = emptyMap(), attached = false, pending = true) }
        }
        setShelvesPending(true)
        saveChanges()
    }

    suspend fun prepareLibrary() = transaction {
        for (root in bookDirectories()) {
            statistics.loadSessions(root)
            prepareBook(root)
        }
        books.loadShelfList()
        save()
    }

    suspend fun prepareBook(root: File) = transaction {
        val key = root.name.syncKey()
        if (state.books[key] != null) return@transaction
        val archived = root.parentFile!!.name == "statistics_archive"
        var record = SyncRecord(generation = if (archived) 0 else 1, deleted = archived)
        for (type in SyncFileType.entries) {
            if (sourceURL(key, type) != null) record = record.copy(sources = record.sources + (type to System.currentTimeMillis()))
        }
        state = state.copy(books = state.books + (key to record))
    }

    suspend fun loadBook(key: String): SyncBook? = transaction {
        val record = state.books[key] ?: return@transaction null
        val root = resolveBookDirectory(key)
        val metadata = books.loadMetadata(root) ?: return@transaction null
        var book = SyncBook(
            generation = record.generation,
            deleted = record.deleted,
            metadata = Timestamped(metadata.modified ?: 0, SyncMetadata(metadata.displayTitle, metadata.author)),
            characterCount = maxOf(metadata.characterCount ?: 0, books.loadBookInfo(root)?.characterCount ?: 0),
            files = record.files,
            sessions = statistics.loadSessions(root),
        )
        if (!record.deleted) {
            val bookmark = books.loadBookmark(root)
            val playback = books.loadSasayakiPlayback(root)
            book = book.copy(
                bookmark = bookmark?.let { Timestamped(it.lastModified?.appleDateMilliseconds() ?: DistantPast, SyncBookmark(it.characterCount)) },
                audiobook = playback?.let { Timestamped(it.modified ?: 0, SyncPlayback(it.lastPosition, it.delay, it.rate.toDouble())) },
                highlights = books.loadHighlightRecords(root).mapValues { (_, change) -> change.replacing(change.value?.let(::SyncHighlight)) },
                shelves = metadata.shelves.orEmpty(),
            )
        }
        book
    }

    suspend fun applyBook(key: String, book: SyncBook) = transaction {
        val oldRecord = state.books[key]
        val bookURL = bookDirectory(key)
        val existing = books.loadMetadata(bookURL)
        val folder = existing?.folder ?: bookURL.name
        var changed = existing != null && (book.deleted || oldRecord?.generation != book.generation)
        if (book.deleted && existing != null) {
            statistics.archiveBook(bookURL)
            bookURL.deleteRecursively()
        }
        val root = bookDirectory(folder, book.deleted)
        val oldMetadata = books.loadMetadata(root)
        val title = oldMetadata?.title ?: book.metadata.value.title
        val metadata = BookMetadata(
            id = oldMetadata?.id ?: UUID.randomUUID().toString().uppercase(),
            title = title,
            author = book.metadata.value.author,
            epub = oldMetadata?.epub,
            cover = oldMetadata?.cover,
            folder = oldMetadata?.folder ?: folder,
            lastAccess = if (!book.deleted && book.bookmark != null) maxOf(oldMetadata?.lastAccess ?: DistantPast.appleDateSeconds(), book.bookmark.modified.appleDateSeconds()) else oldMetadata?.lastAccess ?: DistantPast.appleDateSeconds(),
            renamedTitle = book.metadata.value.title.takeIf { it != title },
            modified = book.metadata.modified,
            characterCount = book.characterCount,
            shelves = book.shelves.takeUnless { book.deleted },
            profileId = oldMetadata?.profileId,
            bookLanguage = oldMetadata?.bookLanguage,
        )
        if (metadata != oldMetadata) {
            sidecars.saveMetadata(root, metadata)
            changed = true
        }
        var record = (state.books[key] ?: SyncRecord(book.generation, book.deleted)).copy(
            generation = book.generation, deleted = book.deleted, files = book.files, attached = true,
        )
        if (statistics.loadSessions(root) != book.sessions) {
            statistics.applySessions(root, book.sessions)
            changed = true
        }
        if (!book.deleted) {
            statistics.restore(folder)
            var bookmark = books.loadBookmark(root)
            val bookmarkChanged = book.bookmark?.let { bookmark?.characterCount != it.value.characterCount } ?: false
            book.bookmark?.let { change ->
                val modified = bookmark?.lastModified?.appleDateMilliseconds() ?: DistantPast
                if (bookmarkChanged) {
                    changed = true
                    val position = books.loadBookInfo(root)?.resolveTtuCharacterPosition(change.value.characterCount)
                    bookmark = Bookmark(position?.spineIndex ?: 0, position?.progress ?: 0.0, change.value.characterCount)
                }
                if (bookmarkChanged || modified != change.modified) sidecars.saveBookmark(root, bookmark!!.copy(lastModified = change.modified.appleDateSeconds()))
            }
            if (books.loadHighlightRecords(root).mapValues { (_, change) -> change.replacing(change.value?.let(::SyncHighlight)) } != book.highlights) {
                sidecars.saveHighlightRecords(root, book.highlights.mapValues { (id, change) -> change.replacing(change.value?.highlight(id)) })
            }
            book.audiobook?.let { change ->
                val playback = books.loadSasayakiPlayback(root) ?: SasayakiPlaybackData(0.0)
                val value = change.value
                if (playback.modified != change.modified || playback.lastPosition != value.lastPosition || playback.delay != value.delay || playback.rate.toDouble() != value.rate) {
                    sidecars.saveSasayakiPlayback(root, playback.copy(lastPosition = value.lastPosition, delay = value.delay, rate = value.rate.toFloat(), modified = change.modified))
                }
            }
            applyReaderState?.invoke(key, book, bookmarkChanged)
        } else {
            record = record.copy(sources = record.sources - SyncFileType.epub - SyncFileType.sasayaki)
        }
        state = state.copy(books = state.books + (key to record))
        clearUnusedCover(key, book.sessions)
        if (state.books[key] != oldRecord) save()
        if (changed) notifyBooksChanged()
    }

    suspend fun handleBookImport(book: BookMetadata, root: File) = transaction {
        val key = book.folder!!.syncKey()
        val archive = bookDirectory(key, true)
        if (books.loadMetadata(archive) != null) prepareBook(archive)
        state.books[key]?.takeIf { it.deleted }?.let { old ->
            updateRecord(key) {
                SyncRecord(maxOf(1, old.generation + 1), false, attached = old.attached || old.generation > 0, cleanup = old.cleanup + old.generation)
            }
        }
        sidecars.saveMetadata(root, book.copy(modified = System.currentTimeMillis()))
        statistics.restore(book.folder)
        prepareBook(root)
        for (type in SyncFileType.entries) if (sourceURL(key, type) != null) markFileChanged(key, type)
        updateRecord(key) { it.copy(pending = true) }
        saveChanges()
    }

    suspend fun deleteLocalBook(key: String) = transaction {
        val root = bookDirectory(key)
        val metadata = books.loadMetadata(root)!!
        root.resolve(metadata.epub!!).delete()
        sidecars.saveMetadata(root, metadata.copy(epub = null))
        updateRecord(key) { it.copy(sources = it.sources - SyncFileType.epub) }
        save()
        notifyBooksChanged()
    }

    suspend fun deleteBook(key: String) = transaction {
        val root = bookDirectory(key)
        statistics.archiveBook(root)
        updateRecord(key) { record ->
            record.copy(deleted = true, pending = true, cleanup = record.cleanup + record.generation,
                files = record.files - SyncFileType.epub - SyncFileType.sasayaki,
                sources = record.sources - SyncFileType.epub - SyncFileType.sasayaki)
        }
        saveChanges()
        root.deleteRecursively()
        clearUnusedCover(key)
        saveChanges()
    }

    suspend fun handleBookChange(folder: String) = transaction {
        if (state.books[folder] == null) {
            prepareBook(resolveBookDirectory(folder))
            save()
        }
        markPending(folder)
    }

    suspend fun markFileChanged(key: String, fileType: SyncFileType) = transaction {
        updateRecord(key) { it.copy(sources = it.sources + (fileType to System.currentTimeMillis()), pending = true) }
    }

    suspend fun handleShelvesChange() = transaction {
        setShelvesPending(true)
        saveChanges()
    }

    suspend fun applyShelves(shelves: Map<String, Timestamped<Int?>>) = transaction {
        if (shelves != books.loadShelfList()) {
            sidecars.saveShelfList(booksDirectory, shelves)
            notifyBooksChanged()
        }
    }

    suspend fun sourceURL(key: String, fileType: SyncFileType): File? = transaction {
        val root = resolveBookDirectory(key)
        val metadata = books.loadMetadata(root)
        when (fileType) {
            SyncFileType.epub -> metadata?.epub?.let { root.resolve(it) }
            SyncFileType.cover -> metadata?.let { books.coverFile(BookEntry(root, it)) }
            SyncFileType.sasayaki -> root.resolve("sasayaki_match.json").takeIf { it.isFile }
        }
    }

    suspend fun clearUnusedCover(key: String, sessions: ReadingSessions? = null) = transaction {
        val record = state.books.getValue(key)
        if (!record.deleted) return@transaction
        val root = resolveBookDirectory(key)
        if ((sessions ?: statistics.loadSessions(root)).values.all { it.value == null }) {
            val metadata = books.loadMetadata(root)
            if (metadata != null) {
                books.coverFile(BookEntry(root, metadata))?.let { cover ->
                    cover.delete()
                    sidecars.saveMetadata(root, metadata.copy(cover = null))
                }
            }
            val published = record.files[SyncFileType.cover]
            if (published?.value == null && (record.sources[SyncFileType.cover] ?: Long.MIN_VALUE) <= (published?.modified ?: Long.MIN_VALUE)) return@transaction
            val modified = System.currentTimeMillis()
            updateRecord(key) { it.copy(files = it.files + (SyncFileType.cover to Timestamped(modified, null)),
                sources = it.sources + (SyncFileType.cover to modified), cleanup = it.cleanup + it.generation, pending = true) }
        }
    }

    suspend fun removeBookFiles(key: String) = transaction {
        val root = resolveBookDirectory(key)
        books.loadMetadata(root)?.let { metadata ->
            metadata.epub?.let { root.resolve(it).delete() }
            books.coverFile(BookEntry(root, metadata))?.delete()
            listOf("bookinfo.json", "sasayaki_match.json", "sasayaki_transcript.json", "bookmark.json", "highlights.json").forEach { root.resolve(it).delete() }
            books.loadSasayakiPlayback(root)?.let { sidecars.saveSasayakiPlayback(root, it.copy(lastPosition = 0.0, delay = 0.0, rate = 1f, modified = null)) }
            sidecars.saveMetadata(root, metadata.copy(epub = null, cover = null))
        }
        updateRecord(key) { it.copy(sources = emptyMap()) }
    }

    suspend fun bookDirectories(): List<File> = transaction {
        listOf(booksDirectory, booksDirectory.resolve("statistics_archive")).flatMap { it.listFiles().orEmpty().toList() }
            .filter { books.loadMetadata(it) != null }
    }

    suspend fun bookDirectory(folder: String, archived: Boolean = false): File = withContext(ioDispatcher) {
        val directory = if (archived) booksDirectory.resolve("statistics_archive") else booksDirectory
        directory.listFiles().orEmpty().firstOrNull { it.name.syncKey() == folder.syncKey() } ?: directory.resolve(folder)
    }

    suspend fun resolveBookDirectory(folder: String): File = transaction {
        val live = bookDirectory(folder)
        if (books.loadMetadata(live) != null) live else bookDirectory(folder, true)
    }

    private suspend fun handleSessionsChange(folder: String, sessions: ReadingSessions) = transaction {
        if (state.books[folder] == null) {
            prepareBook(resolveBookDirectory(folder))
            save()
        }
        if (state.books.getValue(folder).deleted) {
            clearUnusedCover(folder, sessions)
            updateRecord(folder) { it.copy(pending = true) }
            saveChanges(false)
        } else markPending(folder)
    }

    companion object {
        const val DistantPast = -62_135_769_600_000L
    }
}
