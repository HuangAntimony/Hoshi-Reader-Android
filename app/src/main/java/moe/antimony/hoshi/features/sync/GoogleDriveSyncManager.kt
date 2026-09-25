package moe.antimony.hoshi.features.sync

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import moe.antimony.hoshi.BuildConfig
import moe.antimony.hoshi.R
import moe.antimony.hoshi.di.ApplicationScope
import moe.antimony.hoshi.di.CacheDir
import moe.antimony.hoshi.di.FilesDir
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.di.MainDispatcher
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.writeBookJson
import moe.antimony.hoshi.ui.UiText

@Serializable
data class GoogleDriveSyncCache(
    val cursor: String? = null,
    @Required val root: String = "",
    @Required val stateFolder: String = "",
    @Required val bookFolder: String = "",
    @Required val bookVersions: Map<String, Map<String, String>> = emptyMap(),
)

data class GoogleDriveSyncState(
    val isSyncing: Boolean = false,
    val errorMessage: UiText? = null,
    val lastSync: Long? = null,
)

class GoogleDriveSyncException(val text: UiText) : Exception()

@Singleton
class GoogleDriveSyncManager internal constructor(
    val store: SyncStorage,
    private val drive: GoogleDriveSyncHandler,
    private val client: GoogleDriveClient,
    private val settings: SyncSettingsRepository,
    private val books: BookRepository,
    private val filesDir: File,
    private val cacheDir: File,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher,
    private val isAuthenticated: suspend () -> Boolean,
    private val revokeAccess: suspend () -> Unit,
    private val clearTtuCache: () -> Unit,
) {
    @Inject constructor(
        store: SyncStorage,
        drive: GoogleDriveSyncHandler,
        client: GoogleDriveClient,
        auth: GoogleDriveAuth,
        settings: SyncSettingsRepository,
        ttu: TtuDriveHandler,
        books: BookRepository,
        @FilesDir filesDir: File,
        @CacheDir cacheDir: File,
        @ApplicationScope scope: CoroutineScope,
        @MainDispatcher mainDispatcher: CoroutineDispatcher,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(store, drive, client, settings, books, filesDir, cacheDir, scope, mainDispatcher, ioDispatcher,
        { auth.status(SyncProvider.Gdrive) == DriveAuthStatus.Connected }, auth::revokeAccess, ttu::clearCache)

    private val mutableState = MutableStateFlow(GoogleDriveSyncState())
    val state = mutableState.asStateFlow()
    var cache = GoogleDriveSyncCache()
        private set
    private var remoteBooks = mapOf<String, Pair<Map<String, String>, SyncBook>>()
    private var stateTask: Job? = null
    private var fileTransferTask: Job? = null
    private var pollTask: Job? = null
    private var debounceTask: Job? = null
    private var downloadTask: Job? = null
    private var stopped = false
    private var unsupportedFormat = false
    var flushReader: (suspend (String) -> Unit)? = null
    var stopReader: (suspend (String) -> Unit)? = null
    var reloadSyncedMatch: (suspend (String) -> Unit)? = null

    private val initialization = scope.async(start = CoroutineStart.LAZY) {
        cache = withContext(ioDispatcher) {
            runCatching { SyncFormat.decode<GoogleDriveSyncCache>(filesDir.resolve("drive-sync.json").readText()) }.getOrDefault(GoogleDriveSyncCache())
        }
        runCatching { store.prepareLibrary() }
        Unit
    }

    init {
        store.onChange = { scope.launch { schedule() } }
    }

    private suspend fun enabled(): Boolean {
        val config = settings.settings.first()
        return config.enabled && config.provider == SyncProvider.Gdrive && isAuthenticated() && !stopped
    }

    suspend fun start(): Unit = withContext(mainDispatcher) {
        initialization.await()
        client.resume()
        stopped = false
        pollTask?.cancel()
        if (!enabled()) return@withContext
        pollTask = scope.launch {
            sync()
            while (true) {
                delay(if (BuildConfig.DEBUG) 5_000 else 120_000)
                sync()
            }
        }
    }

    suspend fun pausePolling(): Unit = withContext(mainDispatcher) {
        pollTask?.cancel()
        pollTask = null
    }

    suspend fun pause(): Unit = withContext(mainDispatcher) {
        pausePolling()
        sync()
    }

    suspend fun syncInBackground(): Unit = withContext(mainDispatcher) {
        sync()
        fileTransferTask?.join()
    }

    suspend fun stop(): Unit = withContext(mainDispatcher) {
        stopped = true
        pollTask?.cancel()
        debounceTask?.cancel()
        debounceTask = null
        stateTask?.cancel()
        fileTransferTask?.cancel()
        downloadTask?.cancel()
        withContext(ioDispatcher) { client.stop() }
        stateTask?.join()
        fileTransferTask?.join()
        downloadTask?.join()
        stateTask = null
        fileTransferTask = null
        downloadTask = null
        mutableState.value = state.value.copy(isSyncing = false)
    }

    suspend fun signOut(): Unit = withContext(mainDispatcher) {
        stop()
        if (settings.settings.first().provider == SyncProvider.Gdrive) resetConnection()
        revokeAccess()
        withContext(ioDispatcher) { clearTtuCache() }
    }

    suspend fun changeProvider(provider: SyncProvider): Unit = withContext(mainDispatcher) {
        stop()
        withContext(ioDispatcher) { clearTtuCache() }
        settings.update { it.copy(provider = provider) }
        start()
    }

    suspend fun clearCache(): Unit = withContext(mainDispatcher) {
        stop()
        cache = GoogleDriveSyncCache()
        saveCache()
        withContext(ioDispatcher) { clearTtuCache() }
        start()
    }

    suspend fun resetConnection(restoringBackup: Boolean = false): Unit = withContext(mainDispatcher) {
        initialization.await()
        store.transaction {
            if (restoringBackup) store.reload()
            store.prepareLibrary()
            removePlaceholders()
            store.resetSyncState()
        }
        cache = GoogleDriveSyncCache()
        unsupportedFormat = false
        mutableState.value = GoogleDriveSyncState()
        saveCache()
    }

    suspend fun schedule(): Unit = withContext(mainDispatcher) {
        if (!enabled() || stateTask != null || debounceTask != null) return@withContext
        debounceTask = scope.launch {
            delay(if (BuildConfig.DEBUG) 2_000 else 30_000)
            debounceTask = null
            sync()
        }
    }

    suspend fun sync(book: BookMetadata? = null): Unit = withContext(mainDispatcher) {
        initialization.await()
        if (!enabled()) return@withContext
        val previous = stateTask
        if (book == null && previous != null) {
            previous.join()
            return@withContext
        }
        debounceTask?.cancel()
        debounceTask = null
        val previousFiles = if (book == null) null else fileTransferTask
        if (book != null) {
            previous?.cancel()
            previousFiles?.cancel()
        }
        val task = scope.launch(start = CoroutineStart.LAZY) {
            previous?.join()
            previousFiles?.join()
            try {
                currentCoroutineContext().ensureActive()
                mutableState.value = state.value.copy(errorMessage = null)
                if (book != null) {
                    if (cache.stateFolder.isEmpty()) loadLayout()
                    syncBook(book.folder!!.syncKey())
                    return@launch
                }
                val (changed, cursor) = changes()
                val pending = store.transaction { store.state.books.filterValues { it.pending }.keys }
                val keys = changed + pending
                for (key in (keys - ".shelves").sorted()) syncBook(key)
                if (store.transaction { store.state.books.values.none { !it.attached && !it.deleted } }) {
                    if (".shelves" in keys || store.state.shelvesPending) syncShelves()
                    cache = cache.copy(cursor = cursor)
                    saveCache()
                    mutableState.value = state.value.copy(lastSync = System.currentTimeMillis())
                    unsupportedFormat = false
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = state.value.copy(errorMessage = error.syncMessage())
                if (error is SyncFormatError) unsupportedFormat = true
                fileTransferTask?.cancel()
            }
        }
        stateTask = task
        mutableState.value = state.value.copy(isSyncing = true)
        task.start()
        withContext(NonCancellable) {
            task.join()
            if (stateTask === task && !task.isCancelled) {
                stateTask = null
                mutableState.value = state.value.copy(isSyncing = false)
                if (book != null || (state.value.errorMessage == null && store.transaction { store.state.books.values.any { it.pending } || store.state.shelvesPending })) schedule()
                if (book == null) startFileSync()
            }
        }
    }

    private suspend fun startFileSync() {
        if (!enabled() || unsupportedFormat || state.value.errorMessage != null || stateTask != null || fileTransferTask != null || downloadTask != null || cache.bookFolder.isEmpty()) return
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val keys = store.transaction { store.state.books.keys.sorted() }
                for (key in keys) {
                    currentCoroutineContext().ensureActive()
                    for (type in SyncFileType.entries) {
                        try {
                            uploadFile(key, type)
                            if (type != SyncFileType.epub) downloadFile(key, type)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            if (stopsFileSync(error)) return@launch
                        }
                    }
                    try {
                        cleanupFiles(key)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        if (stopsFileSync(error)) return@launch
                    }
                }
            } finally {
                fileTransferTask = null
            }
        }
        fileTransferTask = task
        task.start()
    }

    private fun stopsFileSync(error: Exception): Boolean {
        mutableState.value = state.value.copy(errorMessage = error.syncMessage())
        if (error is SyncFormatError) {
            unsupportedFormat = true
            return true
        }
        return false
    }

    suspend fun cancelDownload(): Unit = withContext(mainDispatcher) {
        downloadTask?.cancel()
        downloadTask = null
    }

    suspend fun downloadBook(book: BookMetadata, onProgress: (Double) -> Unit): BookMetadata = withContext(mainDispatcher) {
        downloadTask?.cancel()
        val task = scope.async(start = CoroutineStart.LAZY) {
            val key = book.folder!!.syncKey()
            sync(book)
            if (unsupportedFormat) throw SyncFormatError()
            state.value.errorMessage?.let { throw GoogleDriveSyncException(it) }
            currentCoroutineContext().ensureActive()
            if (store.state.books[key]?.deleted == true) throw GoogleDriveSyncException(UiText.Resource(R.string.sync_book_deleted))
            if (enabled() && store.state.books[key]?.files?.get(SyncFileType.epub)?.value != null) downloadFile(key, SyncFileType.epub, onProgress)
            currentCoroutineContext().ensureActive()
            val metadata = books.loadMetadata(store.bookDirectory(book.folder)) ?: book
            if (metadata.epub == null) throw GoogleDriveSyncException(UiText.Resource(R.string.sync_book_not_uploaded))
            metadata
        }
        downloadTask = task
        try {
            task.await()
        } finally {
            task.cancel()
            if (downloadTask === task) downloadTask = null
            if (currentCoroutineContext().isActive) startFileSync()
        }
    }

    private suspend fun changes(): Pair<Set<String>, String> {
        val keys = mutableSetOf<String>()
        var cursor = cache.cursor ?: drive.startToken().also { keys += listRemote() }
        while (true) {
            val page = drive.changes(cursor)
            currentCoroutineContext().ensureActive()
            if (page.changes.any { change -> change.file?.let { it.isFolder && (it.name == "Hoshi Reader" || it.parents?.contains(cache.root) == true) } == true }) keys += listRemote()
            for (change in page.changes) {
                val file = change.file
                if (!change.removed && file?.trashed != true && file?.parents?.contains(cache.stateFolder) == true) file.stateKey?.let { keys += it }
            }
            cursor = page.nextPageToken ?: return keys to page.newStartPageToken!!
        }
    }

    private suspend fun loadLayout() {
        val layout = drive.layout()
        currentCoroutineContext().ensureActive()
        cache = cache.copy(root = layout.root, stateFolder = layout.state, bookFolder = layout.books)
    }

    private suspend fun listRemote(): Set<String> {
        loadLayout()
        val files = drive.children(cache.stateFolder)
        currentCoroutineContext().ensureActive()
        return files.mapNotNull { it.stateKey }.toSet()
    }

    private suspend fun syncBook(key: String) {
        val files = drive.children(cache.stateFolder, "$key.json")
        currentCoroutineContext().ensureActive()
        var versions = files.associate { it.id to it.version }
        if (files.size == 1 && store.state.books[key]?.pending == false && cache.bookVersions[key] == versions) return
        if (key in cache.bookVersions) {
            cache = cache.copy(bookVersions = cache.bookVersions - key)
            saveCache()
        }
        var remote = remoteBooks[key]?.takeIf { it.first == versions }?.second ?: readState(files, SyncBook::merge)
        mergeBook(key, remote)
        val book = store.loadBook(key) ?: return
        if (book.needsUpload(remote) || files.size > 1) {
            val written = writeState(book, "$key.json", files)
            versions = mapOf(written.id to written.version)
            remote = book
        }
        store.transaction {
            if (store.state.books.getValue(key).pending && store.loadBook(key) == book) {
                store.updateRecord(key) { it.copy(pending = false) }
                store.save()
            }
        }
        remoteBooks = remoteBooks + (key to (versions to remote!!))
        cache = cache.copy(bookVersions = cache.bookVersions + (key to versions))
        saveCache()
    }

    private suspend inline fun <reified T> readState(files: List<GoogleDriveFile>, merge: (T, T) -> T): T? {
        var state: T? = null
        for (file in files) {
            val data = drive.read(file)
            currentCoroutineContext().ensureActive()
            val incoming = SyncFormat.decode<T>(data.decodeToString())
            state = state?.let { merge(it, incoming) } ?: incoming
        }
        return state
    }

    private suspend inline fun <reified T> writeState(state: T, name: String, files: List<GoogleDriveFile>): GoogleDriveFile {
        val written = client.write(SyncFormat.encode(state).toByteArray(), name, cache.stateFolder, files.firstOrNull()?.id)
        for (duplicate in files.drop(1)) {
            currentCoroutineContext().ensureActive()
            drive.trash(duplicate)
        }
        return written
    }

    private suspend fun mergeBook(key: String, remote: SyncBook?) {
        flushReader?.invoke(key)
        val record = store.transaction {
            val root = store.resolveBookDirectory(key)
            if (books.loadMetadata(root) != null) store.prepareBook(root)
            store.state.books[key]
        }
        if (remote == null || record == null) {
            store.transaction { (remote ?: store.loadBook(key))?.let { store.applyBook(key, it) } }
            return
        }
        val replaced = remote.generation > record.generation && (record.attached || record.deleted)
        suspend fun applyBook() = store.transaction {
            var local = store.loadBook(key)!!
            if (replaced) {
                store.removeBookFiles(key)
                store.updateRecord(key) { it.copy(cleanup = it.cleanup + record.generation) }
            }
            if (!record.attached && record.generation == 0) local = local.copy(metadata = remote.metadata)
            if (!record.attached && !record.deleted && !remote.deleted) local = local.copy(generation = remote.generation)
            store.applyBook(key, SyncBook.merge(local, remote))
        }
        if (replaced || (remote.deleted && remote.generation >= record.generation)) {
            books.workRegistry.delete(store.bookDirectory(key)) {
                stopReader?.invoke(key)
                applyBook()
            }
        } else {
            applyBook()
        }
    }

    private suspend fun syncShelves() {
        val files = drive.children(cache.stateFolder, ".shelves.json")
        val remote = readState(files, SyncShelves::merge)
        val merged = store.transaction {
            val local = SyncShelves(books.loadShelfList())
            (remote?.let { SyncShelves.merge(it, local) } ?: local).also { store.applyShelves(it.shelves) }
        }
        if ((merged != remote && merged.shelves.isNotEmpty()) || files.size > 1) writeState(merged, ".shelves.json", files)
        store.transaction {
            store.setShelvesPending(books.loadShelfList() != merged.shelves)
            store.save()
        }
    }

    private suspend fun uploadFile(key: String, fileType: SyncFileType) {
        val (record, source, url) = store.transaction {
            val record = store.state.books.getValue(key)
            if (!record.attached || (record.deleted && fileType != SyncFileType.cover)) return@transaction null
            val source = record.sources[fileType] ?: return@transaction null
            if ((record.files[fileType]?.modified ?: Long.MIN_VALUE) >= source) return@transaction null
            val url = store.sourceURL(key, fileType)
            if (url == null) {
                store.updateRecord(key) { it.copy(files = it.files + (fileType to Timestamped(source, null)), pending = true) }
                store.saveChanges(false)
                return@transaction null
            }
            Triple(record, source, url)
        } ?: return
        val name = if (fileType == SyncFileType.sasayaki) "$source-${url.name.syncKey()}" else url.name.syncKey()
        val data = withContext(ioDispatcher) { url.readBytes() }
        currentCoroutineContext().ensureActive()
        if (!canPublish(key, fileType, source, record.generation)) return
        val folder = drive.fileFolder(cache.bookFolder, key, record.generation, true)!!
        drive.upload(data, name, folder)
        currentCoroutineContext().ensureActive()
        store.transaction {
            if (!canPublish(key, fileType, source, record.generation)) return@transaction
            store.updateRecord(key) {
                val old = it.files[SyncFileType.sasayaki]?.value
                it.copy(files = it.files + (fileType to Timestamped(source, name)), pending = true,
                    cleanup = if (fileType == SyncFileType.sasayaki && old != null && old != name) it.cleanup + record.generation else it.cleanup)
            }
            store.saveChanges(false)
        }
    }

    private suspend fun canPublish(key: String, fileType: SyncFileType, source: Long, generation: Int): Boolean = store.transaction {
        val record = store.state.books.getValue(key)
        record.generation == generation && record.sources[fileType] == source && (!record.deleted || fileType == SyncFileType.cover) && (record.files[fileType]?.modified ?: Long.MIN_VALUE) <= source
    }

    private suspend fun downloadFile(key: String, fileType: SyncFileType, onProgress: (Double) -> Unit = {}) {
        val (record, reference, root) = store.transaction {
            val record = store.state.books.getValue(key)
            if (record.deleted && fileType != SyncFileType.cover) return@transaction null
            val reference = record.files[fileType] ?: return@transaction null
            if ((record.sources[fileType] ?: Long.MIN_VALUE) >= reference.modified) return@transaction null
            if (reference.value == null) {
                applyDownloadedFile(key, fileType, null, reference)
                return@transaction null
            }
            val root = store.bookDirectory(key, record.deleted)
            if (record.deleted && books.loadSessions(root).values.all { it.value == null }) return@transaction null
            Triple(record, reference, root)
        } ?: return
        val name = reference.value!!
        val folder = drive.fileFolder(cache.bookFolder, key, record.generation, false)
        val temporary = cacheDir.resolve(UUID.randomUUID().toString())
        try {
            drive.download(name, folder, temporary, onProgress)
            currentCoroutineContext().ensureActive()
            store.transaction {
                val current = store.state.books.getValue(key)
                if (current.generation != record.generation || current.deleted != record.deleted || current.files[fileType] != reference) return@transaction
                if ((current.sources[fileType] ?: Long.MIN_VALUE) >= reference.modified) return@transaction
                root.mkdirs()
                val fileName = if (fileType == SyncFileType.sasayaki) "sasayaki_match.json" else name
                val destination = root.resolve(fileName)
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                val relative = "Books/" + (if (record.deleted) "statistics_archive/" else "") + root.name + "/" + fileName
                applyDownloadedFile(key, fileType, relative, reference)
            }
        } finally {
            withContext(NonCancellable + ioDispatcher) { temporary.delete() }
        }
    }

    private suspend fun applyDownloadedFile(key: String, fileType: SyncFileType, path: String?, reference: Timestamped<String?>) = store.transaction {
        val record = store.state.books.getValue(key)
        val root = store.bookDirectory(key, record.deleted)
        val existing = books.loadMetadata(root)
        if (existing != null && fileType != SyncFileType.sasayaki) {
            val oldPath = if (fileType == SyncFileType.epub) existing.epub?.let { root.resolve(it) } else books.coverFile(BookEntry(root, existing))
            val newPath = path?.let { filesDir.resolve(it) }
            if (oldPath != null && oldPath.canonicalFile != newPath?.canonicalFile) oldPath.delete()
            val metadata = if (fileType == SyncFileType.epub) existing.copy(epub = path?.let { File(it).name }) else existing.copy(cover = path)
            books.sidecarDataSource.saveMetadata(root, metadata)
        }
        if (fileType == SyncFileType.sasayaki && path == null) root.resolve("sasayaki_match.json").delete()
        store.updateRecord(key) { it.copy(sources = it.sources + (fileType to reference.modified)) }
        store.save()
        if (fileType != SyncFileType.sasayaki) store.notifyBooksChanged()
        if (fileType == SyncFileType.sasayaki) reloadSyncedMatch?.invoke(key)
    }

    private suspend fun cleanupFiles(key: String) {
        for (generation in store.transaction { store.state.books.getValue(key).cleanup }) {
            if (store.state.books.getValue(key).pending) return
            val files = drive.children(cache.stateFolder, "$key.json")
            val remote = readState(files, SyncBook::merge)
            mergeBook(key, remote)
            val book = store.loadBook(key)!!
            if (book.needsUpload(remote)) {
                store.transaction {
                    store.updateRecord(key) { it.copy(pending = true) }
                    store.saveChanges(false)
                }
                return
            }
            val folder = drive.fileFolder(cache.bookFolder, key, generation, false)
            var recent = false
            if (folder != null && generation < book.generation) {
                client.trashFile(folder)
                currentCoroutineContext().ensureActive()
            } else if (folder != null) {
                for (file in drive.children(folder).filter { !it.isFolder && it.name != book.files[SyncFileType.cover]?.value }) {
                    val current = store.state.books.getValue(key)
                    val stale = file.name.endsWith("sasayaki_match.json") && file.name != current.files[SyncFileType.sasayaki]?.value
                    if (!current.deleted && !stale) continue
                    if (file.isRecent) {
                        recent = true
                        continue
                    }
                    drive.trash(file)
                    currentCoroutineContext().ensureActive()
                }
            }
            if (recent) continue
            store.transaction {
                store.updateRecord(key) { it.copy(cleanup = it.cleanup - generation) }
                store.save()
            }
        }
    }

    private suspend fun removePlaceholders() {
        for (root in books.loadAllBooks()) {
            val book = books.loadMetadata(root) ?: continue
            if (book.epub != null) continue
            if (books.loadSessions(root).values.any { it.value != null }) books.statisticsStore.archiveBook(root)
            else store.removeRecord(book.folder!!.syncKey())
            root.deleteRecursively()
        }
        store.notifyBooksChanged()
    }

    private suspend fun saveCache() = withContext(ioDispatcher) {
        writeBookJson(filesDir.resolve("drive-sync.json"), SyncFormat.encode(cache))
    }
}

fun Throwable.syncMessage(): UiText = when (this) {
    is SyncFormatError -> UiText.Resource(R.string.sync_unsupported_format)
    is GoogleDriveSyncException -> text
    is DriveAuthorizationRequiredException -> UiText.Resource(R.string.sync_connect_google_drive)
    else -> UiText.Resource(R.string.bookshelf_sync_failed)
}
