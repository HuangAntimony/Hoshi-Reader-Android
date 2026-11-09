package moe.antimony.hoshi.features.sync

import android.os.SystemClock
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
import moe.antimony.hoshi.features.bookshelf.BookCoverSource
import moe.antimony.hoshi.features.bookshelf.toBookCoverSource
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.diagnosticMessage

@Serializable
data class GoogleDriveSyncCache(
    val cursor: String? = null,
    @Required val root: String = "",
    @Required val stateFolder: String = "",
    @Required val bookFolder: String = "",
    @Required val bookVersions: Map<String, Map<String, String>> = emptyMap(),
    val bookFolders: Map<String, String> = emptyMap(),
    val attachPending: Boolean = false,
    val replacedRoot: Boolean = false,
    val verifyConnection: Boolean = false,
)

data class GoogleDriveSyncState(
    val isSyncing: Boolean = false,
    val errorMessage: UiText? = null,
    val lastSync: Long? = null,
    val queue: List<SyncQueueItem> = emptyList(),
    val progress: SyncProgress? = null,
    val attachPending: Boolean = false,
    val attachDismissed: Boolean = false,
    val missingBooks: Int = 0,
    val activeTransfers: Map<String, SyncFileTransfer> = emptyMap(),
    val transferSummaries: Map<SyncTransferDirection, SyncTransferSummary> = emptyMap(),
    val syncingBookKeys: Set<String> = emptySet(),
    val isCheckingForChanges: Boolean = false,
)

enum class AttachChoice { Merge, Device, Cloud }

data class SyncQueueItem(
    val key: String,
    val title: String,
    val direction: SyncTransferDirection?,
    val error: UiText?,
    val coverSource: BookCoverSource? = null,
    val pendingBytes: Long? = null,
    val interruptedTransfer: SyncFileTransfer? = null,
)

enum class SyncTransferDirection { Upload, Download, Both }

data class SyncProgress(val done: Int, val total: Int)

private enum class SyncPhase { State, File }

private data class TransferJob(val key: String, val epub: Boolean)

private data class QueuedBook(val record: SyncRecord, val item: SyncQueueItem, val payloads: Map<SyncPayload, Long?>)

private data class BookError(val title: String, val message: UiText, val cause: Throwable)

private class RemoteChanges(val listed: Map<String, List<GoogleDriveFile>>?, val changed: Map<String, List<GoogleDriveFile>>, val cursor: String) {
    operator fun contains(key: String) = key in changed || listed?.containsKey(key) == true

    fun files(key: String, cached: Map<String, String>?): List<GoogleDriveFile>? {
        val changed = changed[key]
        if (changed == null && listed != null) return listed[key].orEmpty()
        val files = cachedFiles(key, cached)?.toMutableMap() ?: return null
        for (file in changed.orEmpty()) if (file.trashed == true) files -= file.id else files[file.id] = file
        return files.values.sortedBy { it.id }
    }
}

private fun cachedFiles(key: String, versions: Map<String, String>?): Map<String, GoogleDriveFile>? =
    versions?.mapValues { (id, checksum) -> GoogleDriveFile(id, "$key.json", "", checksum, createdTime = "") }

private fun fileVersions(files: List<GoogleDriveFile>): Map<String, String> = files.associate { it.id to it.md5Checksum.orEmpty() }

private class Listing {
    var listed = false
    val books = mutableMapOf<String, String>()
    val folders = mutableMapOf<Pair<String, Int>, String>()
    val files = mutableMapOf<Pair<String, String>, GoogleDriveFile>()
    val created = mutableSetOf<String>()
    var published = false
    val networkFailures = mutableSetOf<String>()
}

/** [text] contains either a user-facing precondition or already-recorded failure details. */
class GoogleDriveSyncException(val text: UiText, cause: Throwable? = null) : Exception(cause)

private class AttachPending : Exception()

private class AuthorizationRecoveryCancellation(val failure: Exception) : CancellationException("Google Drive authorization recovery interrupted the download") {
    init { initCause(failure) }
}

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
    private val fileTransfer: DriveFileTransfer,
    nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val authorizationRecoveryRequired: suspend () -> Boolean = { false },
    private val completeAuthorizationRecovery: suspend () -> Unit = {},
) {
    @Inject constructor(
        store: SyncStorage,
        drive: GoogleDriveSyncHandler,
        client: GoogleDriveClient,
        auth: GoogleDriveAuth,
        fileTransfer: GoogleDriveFileTransfer,
        settings: SyncSettingsRepository,
        ttu: TtuDriveHandler,
        books: BookRepository,
        @FilesDir filesDir: File,
        @CacheDir cacheDir: File,
        @ApplicationScope scope: CoroutineScope,
        @MainDispatcher mainDispatcher: CoroutineDispatcher,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(store, drive, client, settings, books, filesDir, cacheDir, scope, mainDispatcher, ioDispatcher,
        { auth.status(SyncProvider.Gdrive).isLinked }, auth::revokeAccess, ttu::clearCache, fileTransfer,
        SystemClock::elapsedRealtime, auth::recoveryRequired, auth::completeRecovery)

    private val mutableState = MutableStateFlow(GoogleDriveSyncState())
    val state = mutableState.asStateFlow()
    var cache = GoogleDriveSyncCache()
        private set
    private var remoteBooks = mapOf<String, Pair<Map<String, String>, SyncBook>>()
    private var stateTask: Job? = null
    private var fileTransferTask: Job? = null
    private var pollTask: Job? = null
    private var debounceTask: Job? = null
    private var stopped = false
    private var authorizing = false
    private val networkRetry = DriveSyncRetry(nowMillis)
    private var unsupportedFormat = false
    private var transfers = listOf<SyncQueueItem>()
    private var progress: SyncProgress? = null
    private var bookErrors = mapOf<Pair<String, SyncPhase>, BookError>()
    private var runError: Exception? = null
    private val jobs = mutableListOf<TransferJob>()
    private var active = 0
    private val scheduler = BookTransferScheduler()
    private val transferTracker = SyncTransferTracker(nowMillis)
    private val queueAccounting = SyncQueueAccounting()
    private var queueEpoch = 0L
    private var queueListing: Listing? = null
    private var transferTicker: Job? = null
    private val readerDownloads = mutableSetOf<Job>()
    private var downloads = setOf<String>()
    private val busy = mutableMapOf<String, Long>()
    private val wakeSignal = MutableStateFlow(0)
    var flushReader: (suspend (String) -> Unit)? = null
    var stopReader: (suspend (String) -> Unit)? = null
    var reloadSyncedMatch: (suspend (String) -> Unit)? = null

    private val initialization = scope.async(start = CoroutineStart.LAZY) {
        cache = withContext(ioDispatcher) {
            runCatching { SyncFormat.decode<GoogleDriveSyncCache>(filesDir.resolve("drive-sync.json").readText()) }.getOrDefault(GoogleDriveSyncCache())
        }
        mutableState.value = state.value.copy(attachPending = cache.attachPending)
        runCatching { store.prepareLibrary() }
        Unit
    }

    init {
        scope.launch(mainDispatcher) {
            settings.settings.collect { scheduler.setLimit(it.transferConcurrency) }
        }
        store.onChange = { scope.launch { schedule() } }
        store.syncEnabled = { withContext(mainDispatcher) { enabled() } }
    }

    private suspend fun enabled(): Boolean {
        val config = settings.settings.first()
        return config.enabled && config.provider == SyncProvider.Gdrive && isAuthenticated() && !stopped && !authorizing && !cache.attachPending
    }

    suspend fun start(): Unit = withContext(mainDispatcher) {
        initialization.await()
        if (authorizing) return@withContext
        client.resume()
        stopped = false
        pollTask?.cancel()
        if (!enabled()) return@withContext
        pollTask = scope.launch {
            syncAutomatically()
            while (true) {
                val interval = if (BuildConfig.DEBUG) 5_000L else 120_000L
                val remaining = networkRetry.remainingMillis()
                delay(if (remaining > 0) minOf(interval, remaining) else interval)
                syncAutomatically()
            }
        }
    }

    suspend fun pausePolling(): Unit = withContext(mainDispatcher) {
        pollTask?.cancel()
        pollTask = null
    }

    suspend fun pause(): Unit = withContext(mainDispatcher) {
        pausePolling()
        syncAutomatically()
    }

    suspend fun syncInBackground(): Unit = withContext(mainDispatcher) {
        if (!enabled()) return@withContext
        syncAutomatically()
        // A state pass can enqueue files, whose uploads need another state pass.
        // Drain the whole chain before reporting its final outcome to WorkManager.
        while (true) {
            val tasks = listOfNotNull(stateTask, fileTransferTask)
            if (tasks.isEmpty()) break
            for (task in tasks) task.join()
        }
        fun failure(error: Throwable, message: UiText): Nothing {
            // Automatic outage diagnostics were already recorded (or deliberately suppressed).
            if (error.isSyncRetryableFailure()) throw GoogleDriveSyncException(message, error)
            throw error
        }
        runError?.let { failure(it, state.value.errorMessage ?: UiText.Resource(R.string.bookshelf_sync_failed)) }
        val errors = bookErrors.values.map { it.cause }
        (errors.firstOrNull { it.isRetryableBackgroundSyncFailure() } ?: errors.firstOrNull())?.let {
            failure(it, bookErrors.values.first { book -> book.cause === it }.message)
        }
    }

    suspend fun stop(): Unit = withContext(mainDispatcher) {
        stopped = true
        pollTask?.cancel()
        debounceTask?.cancel()
        debounceTask = null
        stateTask?.cancel()
        fileTransferTask?.cancel()
        val readers = readerDownloads.toList()
        readers.forEach { it.cancel() }
        withContext(ioDispatcher) { client.stop() }
        stateTask?.join()
        fileTransferTask?.join()
        readers.forEach { it.join() }
        stateTask = null
        fileTransferTask = null
        downloads = emptySet()
        clearTransferQueue()
        bookErrors = emptyMap()
        publish()
    }

    suspend fun signOut(): Unit = withContext(mainDispatcher) {
        stop()
        fileTransfer.reset()
        if (settings.settings.first().provider == SyncProvider.Gdrive) resetConnection()
        revokeAccess()
        withContext(ioDispatcher) { clearTtuCache() }
    }

    suspend fun changeProvider(provider: SyncProvider): Unit = withContext(mainDispatcher) {
        stop()
        fileTransfer.reset()
        withContext(ioDispatcher) { clearTtuCache() }
        settings.update { it.copy(provider = provider) }
        start()
    }

    suspend fun clearCache(): Unit = withContext(mainDispatcher) {
        initialization.await()
        stop()
        fileTransfer.reset()
        cache = GoogleDriveSyncCache(root = cache.root, attachPending = cache.attachPending,
            replacedRoot = cache.replacedRoot, verifyConnection = cache.verifyConnection)
        saveCache()
        withContext(ioDispatcher) { clearTtuCache() }
        start()
    }

    suspend fun attach(choice: AttachChoice): Unit = withContext(mainDispatcher) {
        initialization.await()
        stop()
        client.resume()
        fileTransfer.reset()
        try {
            if (choice == AttachChoice.Device) {
                cache = cache.copy(attachPending = false, replacedRoot = true)
                saveCache()
                try {
                    drive.renameRoot()
                } catch (error: Exception) {
                    cache = cache.copy(attachPending = true)
                    saveCache()
                    throw error
                }
                store.removeDeletionRecords()
            }
            val layout = drive.layout()
            if (choice == AttachChoice.Cloud) clearLibrary()
            cache = cache.copy(root = layout.root, stateFolder = layout.state, bookFolder = layout.books, attachPending = false)
            saveCache()
        } finally {
            start()
        }
    }

    private suspend fun clearLibrary() {
        for (root in books.loadAllBooks()) stopReader?.invoke(root.name.syncKey())
        store.transaction {
            filesDir.resolve("Books").deleteRecursively()
            filesDir.resolve("Books").mkdirs()
            store.reload()
        }
        store.notifyBooksChanged()
    }

    suspend fun resetConnection(restoringBackup: Boolean = false): Unit = withContext(mainDispatcher) {
        initialization.await()
        fileTransfer.reset()
        store.transaction {
            if (restoringBackup) store.reload()
            store.prepareLibrary()
            removePlaceholders()
            store.resetSyncState()
        }
        cache = GoogleDriveSyncCache(replacedRoot = cache.replacedRoot)
        clearTransferQueue()
        networkRetry.reset()
        unsupportedFormat = false
        mutableState.value = GoogleDriveSyncState(
            isSyncing = syncWorkInProgress,
            isCheckingForChanges = stateTask != null,
        )
        saveCache()
    }

    /** Keep the old association until the newly authorized account's root has been checked. */
    suspend fun authorizationChanged(): Unit = withContext(mainDispatcher) {
        initialization.await()
        fileTransfer.reset()
        cache = cache.copy(verifyConnection = true, attachPending = false)
        networkRetry.reset()
        mutableState.value = state.value.copy(attachPending = false, attachDismissed = false)
        saveCache()
    }

    suspend fun reauthorize(accept: suspend () -> Unit): Unit = withContext(mainDispatcher) {
        check(!authorizing)
        authorizing = true
        try {
            stop()
            authorizationChanged()
            accept()
        } finally {
            withContext(NonCancellable) {
                authorizing = false
                start()
            }
        }
    }

    suspend fun schedule(): Unit = withContext(mainDispatcher) {
        if (!enabled() || stateTask != null || debounceTask != null) return@withContext
        debounceTask = scope.launch {
            delay(maxOf(if (BuildConfig.DEBUG) 2_000L else 30_000L, networkRetry.remainingMillis()))
            debounceTask = null
            syncAutomatically()
        }
    }

    suspend fun sync(book: BookMetadata? = null) {
        requestSync(book, automatic = false)
    }

    suspend fun syncAutomatically(book: BookMetadata? = null) {
        requestSync(book, automatic = true)
    }

    suspend fun onNetworkAvailable(): Unit = withContext(mainDispatcher) {
        requestSync(book = null, automatic = true, force = true)
    }

    private suspend fun requestSync(book: BookMetadata?, automatic: Boolean, force: Boolean = false): Boolean = withContext(mainDispatcher) {
        // Forced requests release the deadline even when they join an existing pass.
        if (!automatic || force) networkRetry.allowNow()
        syncPass(book, automatic) { networkRetry.startAttempt(respectBackoff = automatic && !force) }
    }

    // Publication continues admitted work without opening another retry window.
    // False means no new pass completed: disabled, joined another pass, or superseded.
    private suspend fun syncPass(book: BookMetadata?, automatic: Boolean = false, admit: (() -> Boolean)? = null): Boolean = withContext(mainDispatcher) {
        initialization.await()
        preserveAuthorizationVerification()
        if (!enabled()) return@withContext false
        val previous = stateTask
        if (book == null && previous != null) {
            previous.join()
            return@withContext false
        }
        if (admit?.invoke() == false) return@withContext false
        debounceTask?.cancel()
        debounceTask = null
        if (book != null) previous?.cancel()
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                previous?.join()
                currentCoroutineContext().ensureActive()
                if (!automatic) mutableState.value = state.value.copy(errorMessage = null)
                runError = null
                try {
                    syncState(book, automatic)
                    mutableState.value = state.value.copy(errorMessage = null)
                } finally {
                    withContext(NonCancellable) { saveCache() }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: AttachPending) {
                fileTransferTask?.cancel()
            } catch (error: Exception) {
                failRun(error, automatic)
                if (error !is GoogleDriveMetadataNetworkException) fileTransferTask?.cancel()
            } finally {
                val current = currentCoroutineContext()[Job]!!
                if (stateTask === current) {
                    try {
                        // Keep ownership across the suspending handoff so retry state never sees a false idle.
                        if (!current.isCancelled) startFileSync(automatic, completingState = current)
                    } finally {
                        if (stateTask === current) stateTask = null
                        publish()
                    }
                    if (!current.isCancelled) {
                        val succeeded = state.value.errorMessage == null && bookErrors.keys.none { it.second == SyncPhase.State }
                        if (book != null || (succeeded && store.transaction { store.state.books.values.any { it.pending } || store.state.shelvesPending })) schedule()
                        settleRetryIfIdle()
                    }
                }
            }
        }
        stateTask = task
        publish()
        task.start()
        // Callers may leave the foreground while the application-owned pass finishes.
        withContext(NonCancellable) { task.join() }
        !task.isCancelled
    }

    private suspend fun syncState(book: BookMetadata?, automatic: Boolean) {
        if (cache.verifyConnection) {
            loadLayout()
            cache = cache.copy(verifyConnection = false)
            saveCache()
            completeAuthorizationRecovery()
        }
        if (book != null) {
            if (cache.stateFolder.isEmpty()) loadLayout()
            val key = book.folder!!.syncKey()
            recordBook(key, SyncPhase.State, runCatching { syncBook(key) }, automatic)
            return
        }
        val remote = changes()
        val pending = store.transaction { store.state.books.filterValues { it.pending }.keys }
        val retry = bookErrors.filter { it.key.second == SyncPhase.State && it.value.cause.isRetryableBackgroundSyncFailure() }.keys.map { it.first }
        val keys = (remote.changed.keys + remote.listed?.keys.orEmpty() + pending + retry - ".shelves").sorted()
        concurrent(keys) { key ->
            val files = remote.files(key, cache.bookVersions[key])
            recordBook(key, SyncPhase.State, runCatching { syncBook(key, files) }, automatic)
        }
        val failed = keys.any { (it to SyncPhase.State) in bookErrors }
        if (!failed && store.transaction { store.state.books.values.none { !it.attached && !it.deleted } }) {
            if (".shelves" in remote || store.state.shelvesPending) syncShelves()
            cache = cache.copy(cursor = remote.cursor)
            mutableState.value = state.value.copy(lastSync = System.currentTimeMillis())
            unsupportedFormat = false
        }
    }

    private suspend fun <T> concurrent(items: List<T>, action: suspend (T) -> Unit) = coroutineScope {
        val limit = Semaphore(8)
        for (item in items) launch { limit.withPermit { action(item) } }
    }

    private suspend fun startFileSync(automatic: Boolean = true, completingState: Job? = null) {
        initialization.await()
        preserveAuthorizationVerification()
        while (true) {
            if (!enabled() || cache.verifyConnection || unsupportedFormat || state.value.errorMessage != null ||
                (stateTask != null && stateTask !== completingState) || cache.bookFolder.isEmpty()) return
            val owner = fileTransferTask ?: break
            val epoch = queueEpoch
            enqueue(reset = false)
            // The owner can finish while the queue reads book metadata.
            if (epoch != queueEpoch || fileTransferTask === owner) return
        }
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                transferLoop@ do {
                    val result = runFileSync(automatic)
                    val published = result.published
                    progress = null
                    publish()
                    val queued = jobs.isNotEmpty()
                    jobs.clear()
                    // An older/targeted pass may not include these new references.
                    // Keep ownership until a fresh full pass completes, even if a
                    // reader-open sync supersedes it. Terminal errors still end the run.
                    if (published) {
                        while (!syncPass(book = null, automatic = automatic)) {
                            currentCoroutineContext().ensureActive()
                            if (!enabled()) break@transferLoop
                        }
                    }
                    if (result.networkFailures.isNotEmpty()) break@transferLoop
                } while ((published || queued) && enabled() && !unsupportedFormat && state.value.errorMessage == null &&
                    bookErrors.keys.none { it.second == SyncPhase.State })
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failRun(error, automatic)
            } finally {
                fileTransferTask = null
                progress = null
                if (!currentCoroutineContext()[Job]!!.isCancelled) settleRetryIfIdle()
                publish()
            }
        }
        fileTransferTask = task
        publish()
        task.start()
    }

    suspend fun downloadAll(): Unit = withContext(mainDispatcher) {
        downloads = downloads + store.transaction { store.state.books.filterValues { it.missingEpub }.keys }
        requestSync(book = null, automatic = false)
    }

    private suspend fun runFileSync(automatic: Boolean): Listing {
        val records = store.transaction { store.state.books.toMap() }
        val root = cache.root
        fileTransfer.discardObsolete { upload ->
            records[upload.book]?.let { record ->
                upload.root == root && record.attached && record.generation == upload.generation &&
                    (!record.deleted || upload.type == SyncFileType.cover) && record.sources[upload.type] == upload.source &&
                    (record.files[upload.type]?.modified ?: Long.MIN_VALUE) < upload.source
            } == true
        }
        val listing = Listing()
        queueListing = listing
        queueAccounting.beginPass()
        try {
            enqueue(reset = true)
            if (progress != null) {
                listFiles(listing)
                if (queueListing === listing) {
                    queueAccounting.applyListing { listedFile(listing, it) }
                    publish()
                }
            }
            coroutineScope { repeat(8) { launch { work(listing, automatic) } } }
        } finally {
            if (queueListing === listing) {
                queueListing = null
                queueAccounting.endPass()
            }
            withContext(NonCancellable) { saveCache() }
        }
        if (cache.replacedRoot) {
            try {
                drive.removeReplaced()
                cache = cache.copy(replacedRoot = false)
                saveCache()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error.stopsRun) throw error
            }
        }
        return listing
    }

    private suspend fun work(listing: Listing, automatic: Boolean) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val version = wakeSignal.value
            val index = if (jobs.isEmpty()) -1 else 0
            if (index < 0) {
                if (jobs.isEmpty() && active == 0) return
                wakeSignal.first { it != version }
                continue
            }
            val job = jobs.removeAt(index)
            if (job.key in listing.networkFailures) continue
            active++
            publish()
            var skipped = false
            val result = runCatching {
                scheduler.withBook(job.key) {
                    lock(job.key)
                    try {
                        if (job.key in listing.networkFailures) skipped = true
                        else if (job.epub) downloadFile(job.key, SyncFileType.epub, listing) else syncFiles(job.key, listing, automatic)
                    } catch (error: Exception) {
                        // Publish the failure before releasing this book to a waiting duplicate.
                        if (error.isSyncNetworkFailure()) listing.networkFailures += job.key
                        throw error
                    } finally {
                        unlock(job.key)
                    }
                }
            }
            if (skipped) {
                active--
                wake()
                continue
            }
            val failure = runCatching { recordBook(job.key, SyncPhase.File, result, automatic) }.exceptionOrNull()
            val succeeded = failure == null && (job.key to SyncPhase.File) !in bookErrors
            val deferred = !job.epub && succeeded && wantsEpub(job.key)
            active--
            if (job.epub) {
                if (succeeded) downloads = downloads - job.key
            }
            if (deferred) jobs += TransferJob(job.key, true)
            if (failure != null) jobs.clear()
            finishTransfer(job.key, !deferred)
            wake()
            failure?.let { throw it }
        }
    }

    private suspend fun lock(key: String) {
        while (key in busy) {
            val version = wakeSignal.value
            wakeSignal.first { it != version }
        }
        busy[key] = queueEpoch
        publish()
    }

    private fun unlock(key: String) {
        busy -= key
        wake()
        publish()
    }

    private fun wake() {
        wakeSignal.value++
    }

    private fun wantsEpub(key: String): Boolean =
        key in downloads && store.state.books[key]?.missingEpub == true

    private suspend fun enqueue(reset: Boolean) {
        val epoch = queueEpoch
        val owner = fileTransferTask
        if (reset) {
            jobs.clear()
            active = 0
            transfers = emptyList()
            progress = null
        }
        val requestedDownloads = downloads
        val records = store.transaction { store.state.books }
        val keys = records.keys.sorted()
        val queuedBooks = keys.mapNotNull { key ->
            if (epoch != queueEpoch || fileTransferTask !== owner) return
            val epub = key in requestedDownloads
            if (records.getValue(key).transferDirection(epub) == null) return@mapNotNull null
            store.transaction {
                val record = store.state.books[key] ?: return@transaction null
                val direction = record.transferDirection(epub) ?: return@transaction null
                QueuedBook(record, queueItem(key, record.deleted, direction), pendingPayloads(key, record, epub))
            }
        }
        if (epoch != queueEpoch || fileTransferTask !== owner) return
        val listing = queueListing
        for (key in keys) {
            val job = TransferJob(key, false)
            if (job !in jobs) jobs += job
        }
        val rows = transfers.associateByTo(linkedMapOf()) { it.key }
        for ((plannedRecord, item, payloads) in queuedBooks) {
            val current = store.state.books[item.key] ?: continue
            if (current.generation != plannedRecord.generation || current.deleted != plannedRecord.deleted) continue
            val direction = current.transferDirection(item.key in downloads) ?: continue
            if (rows.put(item.key, item.copy(direction = direction)) == null) {
                progress = (progress ?: SyncProgress(0, 0)).let { it.copy(total = it.total + 1) }
            }
            for ((payload, size) in payloads) {
                if (payload.isPending(current)) queueAccounting.plan(payload, size, listing?.let { listedFile(it, payload) })
            }
        }
        transfers = rows.values.toList()
        wake()
        publish()
    }

    // Called inside the storage transaction, on its IO dispatcher.
    private suspend fun pendingPayloads(key: String, record: SyncRecord, epub: Boolean): Map<SyncPayload, Long?> {
        val payloads = mutableMapOf<SyncPayload, Long?>()
        for (type in SyncFileType.entries) {
            val source = record.uploadSource(type)
            if (source != null) {
                optionalQueueFileInfo { store.sourceURL(key, type) }?.let { file ->
                    val payload = SyncPayload(key, type, record.generation, record.deleted, source,
                        SyncTransferDirection.Upload, uploadName(type, source, file))
                    payloads[payload] = file.payloadSize()
                }
            }
            val reference = record.downloadReference(type)
            val name = reference?.value
            if (name != null && (type != SyncFileType.epub || epub)) {
                if (record.deleted && optionalQueueFileInfo {
                        books.statisticsStore.hasStoredSessions(store.bookDirectory(key, archived = true))
                    } != true) continue
                val payload = SyncPayload(key, type, record.generation, record.deleted, reference.modified, SyncTransferDirection.Download, name)
                payloads[payload] = null
            }
        }
        return payloads
    }

    private suspend fun syncFiles(key: String, listing: Listing, automatic: Boolean) {
        val failures = mutableListOf<Pair<UiText, Throwable>>()
        suspend fun attempt(operation: (String) -> UiText, action: suspend () -> Unit) {
            val error = runCatching { action() }.exceptionOrNull() ?: return
            if (error.stopsRun) throw error
            val title = bookTitle(key, store.state.books[key]?.deleted == true)
            failures += reportFailure(error, operation(title), automatic) to error
        }
        for (type in SyncFileType.entries) {
            currentCoroutineContext().ensureActive()
            val label = UiText.Resource(when (type) {
                SyncFileType.epub -> R.string.sync_file_epub
                SyncFileType.cover -> R.string.sync_file_cover
                SyncFileType.sasayaki -> R.string.sync_file_sasayaki
            })
            attempt({ title -> UiText.Resource(R.string.sync_file_failed_format, title, label) }) {
                uploadFile(key, type, listing)
                if (type != SyncFileType.epub) downloadFile(key, type, listing)
            }
        }
        attempt({ title -> UiText.Resource(R.string.sync_cleanup_failed_format, title) }) { cleanupFiles(key, listing) }
        if (failures.isNotEmpty()) {
            val cause = failures.firstOrNull { it.second.isRetryableBackgroundSyncFailure() }?.second ?: failures.first().second
            throw GoogleDriveSyncException(UiText.Joined(failures.map { it.first }, "\n\n"), cause).apply {
                failures.map { it.second }.filter { it !== cause }.forEach(::addSuppressed)
            }
        }
    }

    private fun finishTransfer(key: String, finished: Boolean) {
        if (!finished || transfers.none { it.key == key }) return
        progress = progress?.let { it.copy(done = it.done + 1) }
        if ((key to SyncPhase.File) !in bookErrors) transfers = transfers.filter { it.key != key }
        publish()
    }

    private suspend fun recordBook(key: String, phase: SyncPhase, result: Result<Unit>, automatic: Boolean = false) {
        val error = result.exceptionOrNull()
        if (error == null) {
            bookErrors = bookErrors - (key to phase)
            return
        }
        currentCoroutineContext().ensureActive()
        if (error.stopsRun) throw error
        val title = bookTitle(key, store.state.books[key]?.deleted == true)
        bookErrors = bookErrors + ((key to phase) to BookError(title, reportFailure(error, UiText.Resource(R.string.sync_book_failed_format, title), automatic), error))
    }

    private suspend fun bookTitle(key: String, deleted: Boolean): String =
        store.transaction { books.loadMetadata(store.bookDirectory(key, deleted))?.displayTitle?.ifBlank { null } } ?: key

    private suspend fun queueItem(key: String, deleted: Boolean, direction: SyncTransferDirection): SyncQueueItem =
        store.transaction {
            val root = store.bookDirectory(key, deleted)
            val metadata = books.loadMetadata(root)
            val cover = metadata?.let { optionalQueueFileInfo { books.coverFile(BookEntry(root, it))?.toBookCoverSource() } }
            SyncQueueItem(key, metadata?.displayTitle?.ifBlank { null } ?: key, direction, null, coverSource = cover)
        }

    private fun reportFailure(error: Throwable, operation: UiText? = null, automatic: Boolean = false): UiText {
        if (error is GoogleDriveSyncException) return error.syncMessage(operation)
        networkRetry.recordFailure(error)
        if (!automatic || !error.isSyncRetryableFailure()) return error.syncMessage(operation)
        val summary = operation ?: UiText.Resource(
            if (error.isSyncAuthorizationRecovery()) R.string.sync_authorization_recovery_pending else R.string.bookshelf_sync_failed)
        if (networkRetry.shouldRecord(recovery = error.isSyncAuthorizationRecovery())) ErrorDiagnosticEvents.buffer.record(summary, error)
        return UiText.Joined(listOf(summary, UiText.Literal(error.diagnosticMessage())))
    }

    private suspend fun failRun(error: Exception, automatic: Boolean = false) {
        val recovery = error.isSyncAuthorizationRecovery()
        if (recovery) cache = cache.copy(verifyConnection = true)
        // Publish before persistence can suspend, so an old failure cannot overwrite a newer recovery.
        runError = error
        mutableState.value = state.value.copy(errorMessage = reportFailure(error, automatic = automatic))
        if (error is SyncFormatError) unsupportedFormat = true
        if (recovery) {
            val current = currentCoroutineContext()[Job]
            stateTask?.takeUnless { it === current }?.cancel()
            fileTransferTask?.takeUnless { it === current }?.cancel()
            readerDownloads.filter { it !== current }.forEach { it.cancel(AuthorizationRecoveryCancellation(error)) }
            withContext(NonCancellable) { saveCache() }
        }
    }

    private suspend fun preserveAuthorizationVerification() {
        if (!cache.verifyConnection && authorizationRecoveryRequired()) {
            cache = cache.copy(verifyConnection = true)
            saveCache()
        }
    }

    private fun settleRetryIfIdle() {
        if (!stopped && !authorizing && stateTask == null && fileTransferTask == null && readerDownloads.none { it.isActive } &&
            runError == null && bookErrors.values.none { it.cause.isSyncRetryableFailure() }) networkRetry.reset()
    }

    private fun queue(accounting: SyncQueueAccounting.Snapshot, syncing: Set<String>): List<SyncQueueItem> {
        val queue = transfers.associateByTo(linkedMapOf()) { it.key }
        for ((key, interrupted) in accounting.interruptedItems) {
            val item = queue[key]
            queue[key] = item?.copy(error = interrupted.error ?: item.error) ?: interrupted
        }
        for (item in accounting.activeItems) queue.putIfAbsent(item.key, item)
        for ((id, error) in bookErrors.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))) {
            val key = id.first
            val item = queue[key] ?: SyncQueueItem(key, error.title, null, null)
            queue[key] = item.copy(error = item.error?.let {
                UiText.Joined(listOf(it, error.message), "\n\n")
            } ?: error.message)
        }
        return queue.values.map { item ->
            item.copy(pendingBytes = accounting.pendingSizes[item.key],
                interruptedTransfer = accounting.interruptedItems[item.key]?.interruptedTransfer)
        }.sortedBy { if (it.key in syncing) 0 else 1 }
    }

    private suspend fun <T> trackTransfer(
        epoch: Long, payload: SyncPayload, totalBytes: Long?, sizeOf: (T) -> Long?,
        retainReaderError: Boolean = false,
        action: suspend ((DriveTransferUpdate) -> Unit) -> T,
    ): T {
        val key = payload.book
        val item = transfers.firstOrNull { it.key == key } ?: queueItem(key, payload.archived, payload.direction)
        if (epoch != queueEpoch) return action {}
        queueAccounting.begin(payload, item)
        val id = transferTracker.begin(key, payload.direction, payload.type, totalBytes)
        if (transferTicker == null) transferTicker = scope.launch(mainDispatcher) {
            while (true) { delay(500); publish() }
        }
        publish()
        var completedSize: Long? = null
        var succeeded = false
        var interruptionError: UiText? = null
        try {
            return action { transferTracker.update(key, id, it) }.also {
                completedSize = sizeOf(it)
                succeeded = true
            }
        } catch (error: Exception) {
            if (retainReaderError && error !is CancellationException && !error.isSyncAuthorizationRecovery()) {
                val operation = UiText.Resource(R.string.sync_file_failed_format, item.title, UiText.Resource(R.string.sync_file_epub))
                val details = (error as? GoogleDriveSyncException)?.text ?: UiText.Literal(error.diagnosticMessage())
                // The reader also receives this exception and owns its diagnostic.
                interruptionError = UiText.Joined(listOf(operation, details))
            }
            throw error
        } finally {
            transferTracker.finish(key, id)?.let { transfer ->
                val size = if (succeeded) completedSize ?: transfer.totalBytes ?: transfer.bytes else null
                queueAccounting.finish(key, transfer, size, store.state.books[key], interruptionError)
                if (!transferTracker.hasActive()) {
                    transferTicker?.cancel()
                    transferTicker = null
                }
                publish()
            }
        }
    }

    private fun clearTransferQueue() {
        queueEpoch++
        queueListing = null
        queueAccounting.clear()
        transferTracker.clear()
        transferTicker?.cancel()
        transferTicker = null
        transfers = emptyList()
        progress = null
    }

    fun setAttachDismissed(dismissed: Boolean) {
        mutableState.value = state.value.copy(attachDismissed = dismissed)
    }

    private val syncWorkInProgress: Boolean
        get() = stateTask != null || fileTransferTask != null || readerDownloads.any { it.isActive }

    private fun publish() {
        val active = transferTracker.snapshot()
        val syncing = busy.filterValues { it == queueEpoch }.keys + active.keys
        val accounting = queueAccounting.snapshot(store.state.books, active, bookErrors.keys.map { it.first }.toSet() - syncing)
        mutableState.value = state.value.copy(
            isSyncing = syncWorkInProgress,
            isCheckingForChanges = stateTask != null,
            queue = queue(accounting, syncing),
            progress = progress,
            activeTransfers = active,
            transferSummaries = accounting.summaries,
            syncingBookKeys = syncing,
            attachPending = cache.attachPending,
            missingBooks = store.state.books.values.count { it.missingEpub },
        )
    }

    suspend fun syncOrThrow(): Unit = withContext(mainDispatcher) {
        sync()
        runError?.let { throw it }
    }

    suspend fun downloadBook(book: BookMetadata, onProgress: (Double) -> Unit): BookMetadata = withContext(mainDispatcher) {
        val task = scope.async(start = CoroutineStart.LAZY) {
            val key = book.folder!!.syncKey()
            try {
                if (!requestSync(book, automatic = false)) throw CancellationException("Book state check was superseded")
                if (unsupportedFormat) throw SyncFormatError()
                runError?.let { throw it }
                bookErrors[key to SyncPhase.State]?.cause?.let { throw it }
                currentCoroutineContext().ensureActive()
                if (cache.verifyConnection) throw CancellationException("Drive connection verification is pending")
                if (store.state.books[key]?.deleted == true) throw GoogleDriveSyncException(UiText.Resource(R.string.sync_book_deleted))
                if (enabled() && store.state.books[key]?.files?.get(SyncFileType.epub)?.value != null) {
                    scheduler.withBook(key, priority = true) {
                        lock(key)
                        try {
                            downloadFile(key, SyncFileType.epub, Listing(), onProgress, retainReaderError = true)
                        } finally {
                            unlock(key)
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                val metadata = books.loadMetadata(store.bookDirectory(book.folder)) ?: book
                if (metadata.epub == null) throw GoogleDriveSyncException(UiText.Resource(R.string.sync_book_not_uploaded))
                metadata
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error.isSyncAuthorizationRecovery()) {
                    failRun(error)
                }
                throw error
            } finally {
                withContext(NonCancellable) { startFileSync() }
            }
        }
        readerDownloads += task
        task.invokeOnCompletion { scope.launch(mainDispatcher) { readerDownloads -= task; settleRetryIfIdle(); publish() } }
        task.start()
        // A caller leaving the reader does not stop application-owned work.
        try {
            task.await()
        } catch (error: CancellationException) {
            currentCoroutineContext().ensureActive()
            // Stopping app-owned I/O must still report its operational failure to an active waiter.
            if (error is AuthorizationRecoveryCancellation) throw error.failure
            throw error
        }
    }

    private suspend fun changes(): RemoteChanges {
        var listed: Map<String, List<GoogleDriveFile>>? = null
        val changed = mutableMapOf<String, List<GoogleDriveFile>>()
        var cursor = cache.cursor ?: drive.startToken().also { listed = listRemote() }
        while (true) {
            val page = drive.changes(cursor)
            currentCoroutineContext().ensureActive()
            if (page.changes.any { change -> change.file?.let { it.isFolder && (it.name == "Hoshi Reader" || it.parents?.contains(cache.root) == true) } == true }) listed = listRemote()
            for (change in page.changes) {
                val file = change.file
                if (change.removed || file?.parents?.contains(cache.stateFolder) != true) continue
                if (file.isNewerFormat && file.trashed != true) throw SyncFormatError()
                file.stateKey?.let { changed[it] = changed[it].orEmpty() + file }
            }
            cursor = page.nextPageToken ?: return RemoteChanges(listed, changed, page.newStartPageToken!!)
        }
    }

    private suspend fun loadLayout() {
        val layout = drive.layout()
        currentCoroutineContext().ensureActive()
        val replaced = cache.root.isNotEmpty() && cache.root != layout.root
        if (replaced) resetConnection()
        if (cache.root.isEmpty() && store.hasData()) {
            val files = drive.children(layout.state)
            currentCoroutineContext().ensureActive()
            if (files.any { it.isNewerFormat }) throw SyncFormatError()
            if ((replaced && !layout.created) || files.any { it.stateKey != null }) {
                cache = cache.copy(attachPending = true)
                mutableState.value = state.value.copy(attachDismissed = false)
                throw AttachPending()
            }
        }
        cache = cache.copy(root = layout.root, stateFolder = layout.state, bookFolder = layout.books)
    }

    private suspend fun listRemote(): Map<String, List<GoogleDriveFile>> {
        loadLayout()
        val files = drive.children(cache.stateFolder)
        currentCoroutineContext().ensureActive()
        if (files.any { it.isNewerFormat }) throw SyncFormatError()
        return files.filter { it.stateKey != null }.groupBy { it.stateKey!! }
    }

    private suspend fun remoteState(key: String, listed: List<GoogleDriveFile>?): Pair<List<GoogleDriveFile>, SyncBook?>? {
        if (listed == null) {
            val files = cachedFiles(key, cache.bookVersions[key])?.values?.toList() ?: drive.children(cache.stateFolder, "$key.json")
            currentCoroutineContext().ensureActive()
            cache = cache.copy(bookVersions = cache.bookVersions - key)
            return try {
                files to readState(files, SyncBook::merge)
            } catch (error: GoogleDriveApiException) {
                val found = drive.children(cache.stateFolder, "$key.json")
                found to readState(found, SyncBook::merge)
            }
        }
        currentCoroutineContext().ensureActive()
        val versions = fileVersions(listed)
        if (listed.size == 1 && store.state.books[key]?.pending == false && cache.bookVersions[key] == versions) return null
        cache = cache.copy(bookVersions = cache.bookVersions - key)
        remoteBooks[key]?.takeIf { it.first == versions }?.let { return listed to it.second }
        return listed to readState(listed, SyncBook::merge)
    }

    private suspend fun syncBook(key: String, listed: List<GoogleDriveFile>? = null) {
        val (files, state) = remoteState(key, listed) ?: return
        var remote = state
        var versions = fileVersions(files)
        mergeBook(key, remote)
        val book = store.loadBook(key, remote)
        if (book == null) {
            store.transaction {
                if (store.state.books[key] != null) {
                    store.updateRecord(key) { it.copy(pending = false, cleanup = emptySet()) }
                    store.save()
                }
            }
            return
        }
        if (book.needsUpload(remote) || files.size > 1) {
            val written = writeState(book, "$key.json", files)
            versions = mapOf(written.id to written.md5Checksum.orEmpty())
            remote = book
        }
        store.transaction {
            if (store.state.books.getValue(key).pending && store.loadBook(key, remote) == book) {
                store.updateRecord(key) { it.copy(pending = false) }
                store.save()
            }
        }
        remoteBooks = remoteBooks + (key to (versions to remote!!))
        cache = cache.copy(bookVersions = cache.bookVersions + (key to versions))
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
        val resetTime = books.statisticsStore.resetMinutes()
        suspend fun applyBook() = store.transaction {
            var local = store.loadBook(key, remote)!!
            if (replaced) {
                store.removeBookFiles(key)
                store.updateRecord(key) { it.copy(cleanup = it.cleanup + record.generation) }
            }
            if (!record.attached && record.generation == 0) local = local.copy(metadata = remote.metadata)
            if (!record.attached && !record.deleted && !remote.deleted) local = local.copy(generation = remote.generation)
            val merged = SyncBook.merge(local, remote)
            store.applyBook(key, merged.copy(sessions = TtuStatistics.reconciled(merged.sessions, key, resetTime)))
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

    private suspend fun uploadFile(key: String, fileType: SyncFileType, listing: Listing) {
        val epoch = queueEpoch
        val (record, source, url) = store.transaction {
            val record = store.state.books.getValue(key)
            val source = record.uploadSource(fileType) ?: return@transaction null
            val url = store.sourceURL(key, fileType)
            if (url == null) {
                store.updateRecord(key) { it.copy(files = it.files + (fileType to Timestamped(source, null)), pending = true) }
                listing.published = true
                store.saveChanges(false)
                return@transaction null
            }
            Triple(record, source, url)
        } ?: return
        val name = uploadName(fileType, source, url)
        val payload = SyncPayload(key, fileType, record.generation, record.deleted, source, SyncTransferDirection.Upload, name)
        val request = upload(listing, epoch, payload, url)
        currentCoroutineContext().ensureActive()
        store.transaction {
            if (!canPublish(key, fileType, source, record.generation)) return@transaction
            store.updateRecord(key) {
                val old = it.files[SyncFileType.sasayaki]?.value
                it.copy(files = it.files + (fileType to Timestamped(source, name)), pending = true,
                    cleanup = if (fileType == SyncFileType.sasayaki && old != null && old != name) it.cleanup + record.generation else it.cleanup)
            }
            listing.published = true
            store.saveChanges(false)
        }
        fileTransfer.acknowledge(request)
    }

    private suspend fun listFiles(listing: Listing) {
        val listed = drive.list("'me' in owners")
        val keys = listed.filter { it.parents?.contains(cache.bookFolder) == true }.associate { it.id to it.name }
        listing.listed = true
        val folders = mutableMapOf<String, String>()
        for (file in listed) {
            if (file.id in keys) listing.books.getOrPut(file.name) { file.id }
            val parent = file.parents?.firstOrNull() ?: continue
            val key = keys[parent]
            val generation = file.name.toIntOrNull()
            if (file.isFolder && key != null && generation != null) {
                if ((key to generation) !in listing.folders) {
                    listing.folders[key to generation] = file.id
                    folders["$key/$generation"] = file.id
                }
            } else {
                listing.files.getOrPut(parent to file.name) { file }
            }
        }
        cache = cache.copy(bookFolders = folders)
    }

    private fun listedFile(listing: Listing, payload: SyncPayload): GoogleDriveFile? {
        val folder = listing.folders[payload.book to payload.generation] ?: return null
        return listing.files[folder to payload.name]
    }

    private suspend fun folder(listing: Listing, key: String, generation: Int): String? = resolveFolder(listing, key, generation).second

    private fun forgetFolder(listing: Listing, key: String, generation: Int) {
        listing.folders -= key to generation
        cache = cache.copy(bookFolders = cache.bookFolders - "$key/$generation")
    }

    private suspend fun upload(
        listing: Listing, epoch: Long, payload: SyncPayload, file: File,
    ): DriveUploadRequest {
        val (key, type, generation, _, source, _, name) = payload
        var (book, folder) = resolveFolder(listing, key, generation)
        if (folder == null) {
            if (book == null) {
                book = drive.createFolder(cache.bookFolder, key)
                listing.books[key] = book
            }
            val created = drive.createFolder(book, generation.toString())
            listing.created += created
            rememberFolder(listing, key, generation, created)
            folder = created
        }
        val request = DriveUploadRequest(cache.root, key, generation, type, source, folder, name)
        if ((folder to name) !in listing.files &&
            (listing.listed || folder in listing.created || drive.children(folder, name).isEmpty())) {
            val size = withContext(ioDispatcher) { file.payloadSize() }
            val uploaded = trackTransfer(epoch, payload, size, { it.payloadSize ?: size }) {
                fileTransfer.upload(file, request, it)
            }
            listing.files[folder to name] = uploaded
        } else if (epoch == queueEpoch) {
            queueAccounting.skip(payload)
            publish()
        }
        return request
    }

    private suspend fun findFile(listing: Listing, key: String, generation: Int, name: String): GoogleDriveFile? {
        (listing.folders[key to generation] ?: cache.bookFolders["$key/$generation"])?.let { folder ->
            listing.files[folder to name]?.let { return it }
            drive.children(folder, name).firstOrNull()?.let { return it }
        }
        for (book in drive.children(cache.bookFolder, key)) {
            for (folder in drive.children(book.id, generation.toString())) {
                drive.children(folder.id, name).firstOrNull()?.let {
                    rememberFolder(listing, key, generation, folder.id)
                    return it
                }
            }
        }
        return null
    }

    private suspend fun resolveFolder(listing: Listing, key: String, generation: Int): Pair<String?, String?> {
        var folder = listing.folders[key to generation]
        var book = listing.books[key]
        if (folder != null || listing.listed) return book to folder
        if (book == null) book = drive.folder(cache.bookFolder, key, false)
        if (book != null) {
            folder = drive.folder(book, generation.toString(), false)
            listing.books[key] = book
        }
        if (folder != null) rememberFolder(listing, key, generation, folder)
        return book to folder
    }

    private fun rememberFolder(listing: Listing, key: String, generation: Int, folder: String) {
        listing.folders[key to generation] = folder
        cache = cache.copy(bookFolders = cache.bookFolders + ("$key/$generation" to folder))
    }

    private suspend fun canPublish(key: String, fileType: SyncFileType, source: Long, generation: Int): Boolean = store.transaction {
        val record = store.state.books.getValue(key)
        record.generation == generation && record.sources[fileType] == source && (!record.deleted || fileType == SyncFileType.cover) && (record.files[fileType]?.modified ?: Long.MIN_VALUE) <= source
    }

    private suspend fun downloadFile(
        key: String, fileType: SyncFileType, listing: Listing,
        onProgress: (Double) -> Unit = {}, retainReaderError: Boolean = false,
    ) {
        val epoch = queueEpoch
        val (record, reference, root) = store.transaction {
            val record = store.state.books.getValue(key)
            val reference = record.downloadReference(fileType) ?: return@transaction null
            if (reference.value == null) {
                applyDownloadedFile(key, fileType, null, reference)
                return@transaction null
            }
            val root = store.bookDirectory(key, record.deleted)
            if (record.deleted && books.loadSessions(root).values.all { it.value == null }) return@transaction null
            Triple(record, reference, root)
        } ?: return
        val name = reference.value!!
        val payload = SyncPayload(key, fileType, record.generation, record.deleted, reference.modified, SyncTransferDirection.Download, name)
        val file = findFile(listing, key, record.generation, name) ?: throw GoogleDriveApiException("$name is missing from Google Drive.", 404)
        val temporary = cacheDir.resolve(UUID.randomUUID().toString())
        try {
            trackTransfer(epoch, payload, file.payloadSize, { it }, retainReaderError) { update ->
                fileTransfer.download(driveUrl("files/${file.id.urlPathSegment()}", mapOf("alt" to "media")), temporary, file.payloadSize) { value ->
                    update(value)
                    value.totalBytes?.takeIf { it > 0 }?.let { onProgress(value.bytes.toDouble() / it) }
                }
                withContext(ioDispatcher) { temporary.length() }
            }
            currentCoroutineContext().ensureActive()
            val cover = store.transaction {
                val current = store.state.books.getValue(key)
                if (current.generation != record.generation || current.deleted != record.deleted || current.files[fileType] != reference) return@transaction null
                if ((current.sources[fileType] ?: Long.MIN_VALUE) >= reference.modified) return@transaction null
                root.mkdirs()
                val fileName = if (fileType == SyncFileType.sasayaki) "sasayaki_match.json" else name
                val destination = root.resolve(fileName)
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                val relative = "Books/" + (if (record.deleted) "statistics_archive/" else "") + root.name + "/" + fileName
                applyDownloadedFile(key, fileType, relative, reference)
                destination.takeIf { fileType == SyncFileType.cover }?.toBookCoverSource()
            }
            if (cover != null && epoch == queueEpoch) {
                transfers = transfers.map { if (it.key == key) it.copy(coverSource = cover) else it }
                publish()
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

    private suspend fun cleanupFiles(key: String, listing: Listing) {
        for (generation in store.transaction { store.state.books.getValue(key).cleanup }) {
            if (store.state.books.getValue(key).pending) return
            val files = drive.children(cache.stateFolder, "$key.json")
            val remote = readState(files, SyncBook::merge)
            mergeBook(key, remote)
            val book = store.loadBook(key, remote) ?: return
            if (book.needsUpload(remote)) {
                store.transaction {
                    store.updateRecord(key) { it.copy(pending = true) }
                    store.saveChanges(false)
                }
                return
            }
            val folder = folder(listing, key, generation)
            var recent = false
            if (folder != null && generation < book.generation) {
                client.trashFile(folder)
                forgetFolder(listing, key, generation)
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

private val Throwable.stopsRun: Boolean
    get() = this !is Exception || this is CancellationException || this is SyncFormatError || this is DriveAuthException ||
        (this is GoogleDriveUnavailableException && this !is GoogleDriveMetadataNetworkException && cause !is DrivePayloadNetworkIOException)

private fun File.payloadSize(): Long? = takeIf { it.isFile }?.length()

private inline fun <T> optionalQueueFileInfo(read: () -> T): T? =
    try {
        read()
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        // Payload I/O still reports failures through its existing book error path.
        null
    }

private fun uploadName(type: SyncFileType, source: Long, file: File): String =
    if (type == SyncFileType.sasayaki) "$source-${file.name.syncKey()}" else file.name.syncKey()

private val SyncRecord.missingEpub: Boolean
    get() = !deleted && files[SyncFileType.epub]?.let { published -> published.value != null && (sources[SyncFileType.epub]?.let { it < published.modified } ?: true) } == true

private fun SyncRecord.transferDirection(epub: Boolean): SyncTransferDirection? {
    val types = SyncFileType.entries.filter { !deleted || it == SyncFileType.cover }
    val upload = attached && types.any { type -> sources[type]?.let { source -> files[type]?.let { it.modified < source } ?: true } == true }
    val download = types.any { type -> (type != SyncFileType.epub || epub) && files[type]?.let { published -> sources[type]?.let { it < published.modified } ?: true } == true }
    return when {
        upload && download -> SyncTransferDirection.Both
        upload -> SyncTransferDirection.Upload
        download -> SyncTransferDirection.Download
        else -> null
    }
}
