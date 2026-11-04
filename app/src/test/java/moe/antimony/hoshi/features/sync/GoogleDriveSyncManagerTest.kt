package moe.antimony.hoshi.features.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.work.ListenableWorker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.resolve
import org.junit.Before
import org.junit.After
import moe.antimony.hoshi.R
import moe.antimony.hoshi.BuildConfig
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.SasayakiPlaybackData
import moe.antimony.hoshi.epub.EpubBookParser
import moe.antimony.hoshi.epub.writeMinimalExtractedEpub
import moe.antimony.hoshi.features.sasayaki.AndroidSasayakiTranscriptionRepository
import moe.antimony.hoshi.features.sasayaki.SasayakiToken
import moe.antimony.hoshi.features.sasayaki.SasayakiTranscriptStore
import moe.antimony.hoshi.features.sasayaki.SasayakiTranscriptionClock
import moe.antimony.hoshi.features.sasayaki.SasayakiTranscriptionCoordinator
import moe.antimony.hoshi.features.sasayaki.transcription.SasayakiTranscriptionBackend
import moe.antimony.hoshi.features.sasayaki.transcription.SasayakiTranscriptionBatch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class GoogleDriveSyncManagerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val remoteBook get() = SyncBook(1, false, Timestamped(1000, SyncMetadata("Remote")), characterCount = 20)

    @Before fun clearDiagnostics() { ErrorDiagnosticEvents.buffer.drain() }
    @After fun drainDiagnostics() { ErrorDiagnosticEvents.buffer.drain() }

    private fun UiText?.details(): String = this?.resolve(
        { id, args -> when (id) {
            R.string.sync_file_cover -> "cover image"
            R.string.sync_file_sasayaki -> "Sasayaki matches"
            else -> args.joinToString()
        } },
        { _, _, _ -> "" },
    ).orEmpty()

    @Test fun recoveredTargetedStateFailureRetriesBeforeUploadingNewBooks() =
        targetedStateFailureRecovery(IOException("transient targeted read failure"))

    @Test fun rateLimitedTargetedStateIsRetriedWithoutLocalOrCloudChanges() =
        targetedStateFailureRecovery(GoogleDriveApiException("Rate limited", 429))

    @Test fun serverFailedTargetedStateIsRetriedWithoutLocalOrCloudChanges() =
        targetedStateFailureRecovery(GoogleDriveApiException("Server unavailable", 500))

    private fun targetedStateFailureRecovery(failure: Exception) = runTest {
        val f = fixture()
        f.remote.addState("a", "book-a.json", SyncFormat.encode(remoteBook))
        f.manager.sync()
        runCurrent()
        val metadata = f.books.loadMetadata(f.store.bookDirectory("book-a"))!!
        f.remote.reportChanges = false
        f.remote.failRead = { if (it == "a") failure else null }
        f.manager.syncAutomatically(metadata)
        runCurrent()
        assertFalse(f.store.state.books.getValue("book-a").pending)
        val failedReads = f.remote.reads.getValue("a")
        assertTrue(f.manager.state.value.queue.any { it.key == "book-a" && it.error != null })
        f.remote.failRead = { null }
        val root = f.books.createBookDirectory("healthy-b")
        val local = BookMetadata("b", "B", folder = "healthy-b", epub = "b.epub", lastAccess = 0.0)
        f.books.saveMetadata(root, local)
        root.resolve("b.epub").writeText("healthy payload")
        f.store.handleBookImport(local, root)
        advanceTimeBy(30_000)
        f.manager.syncAutomatically()
        runCurrent()
        assertTrue("The failed book must be retried even without local or cloud changes", f.remote.reads.getValue("a") > failedReads)
        assertTrue(f.manager.state.value.queue.none { it.key == "book-a" && it.error != null })
        assertEquals("b.epub", f.store.state.books.getValue("healthy-b").files[SyncFileType.epub]?.value)
        val published = SyncFormat.decode<SyncBook>(f.remote.entries.values.single { it.file.name == "healthy-b.json" }.data)
        assertEquals("b.epub", published.files[SyncFileType.epub]?.value)
    }

    @Test fun recoveredNetworkMustNotKeepGlobalErrorBecauseOneBookHasAStateError() = runTest {
        var offline = false
        val f = fixture(beforeRequest = {
            if (offline) throw GoogleDriveApiException(GoogleDriveApiException.NoInternetConnectionMessage)
        })
        f.remote.addState("a", "book-a.json", SyncFormat.encode(remoteBook))
        f.manager.sync()
        runCurrent()
        f.remote.reportChanges = false
        f.remote.failRead = { if (it == "a") GoogleDriveApiException("Book access denied", 403) else null }
        f.manager.syncAutomatically(f.books.loadMetadata(f.store.bookDirectory("book-a"))!!)
        runCurrent()
        assertTrue(f.manager.state.value.queue.any { it.key == "book-a" && it.error != null })
        offline = true
        f.manager.syncAutomatically()
        runCurrent()
        assertNotNull(f.manager.state.value.errorMessage)
        offline = false
        val root = f.books.createBookDirectory("healthy-b")
        val book = BookMetadata("B", "Healthy", folder = "healthy-b", epub = "b.epub", lastAccess = 0.0)
        f.books.saveMetadata(root, book)
        root.resolve("b.epub").writeText("healthy payload")
        f.store.handleBookImport(book, root)
        f.manager.onNetworkAvailable()
        runCurrent()
        assertNull("A permanent per-book error must not retain the expired run-level network failure", f.manager.state.value.errorMessage)
        assertTrue("The unresolved error must remain attached to its book", f.manager.state.value.queue.any { it.key == "book-a" && it.error != null })
        assertEquals("b.epub", f.store.state.books.getValue("healthy-b").files[SyncFileType.epub]?.value)
        val published = SyncFormat.decode<SyncBook>(f.remote.entries.values.single { it.file.name == "healthy-b.json" }.data)
        assertEquals("The healthy upload must be published to the cloud", "b.epub", published.files[SyncFileType.epub]?.value)
    }

    @Test fun successfulTargetedCheckDoesNotExtendAnotherBooksRetryDeadline() = runTest {
        val f = fixture()
        for (key in listOf("a", "b")) f.remote.addState(key, "book-$key.json", SyncFormat.encode(remoteBook))
        f.manager.sync()
        runCurrent()
        f.remote.reportChanges = false
        f.remote.failRead = { if (it == "a") IOException("transient targeted read failure") else null }
        f.manager.syncAutomatically(f.books.loadMetadata(f.store.bookDirectory("book-a"))!!)
        val failedReads = f.remote.reads.getValue("a")
        f.remote.failRead = { null }
        advanceTimeBy(30_000)
        f.manager.syncAutomatically(f.books.loadMetadata(f.store.bookDirectory("book-b"))!!)
        assertTrue("An unrelated successful check must retain the unresolved book error", f.manager.state.value.queue.any { it.key == "book-a" && it.error != null })
        f.manager.syncAutomatically()
        assertTrue("An old error must not postpone the full retry after a successful targeted check", f.remote.reads.getValue("a") > failedReads)
        assertTrue(f.manager.state.value.queue.none { it.error != null })
    }

    @Test fun automaticOfflineSyncBacksOffExponentiallyAndRecordsOneFailurePerOutage() = runTest {
        var attempts = 0
        val f = fixture(beforeRequest = {
            attempts++
            throw GoogleDriveApiException(GoogleDriveApiException.NoInternetConnectionMessage)
        })
        f.manager.syncAutomatically()
        val firstMessage = f.manager.state.value.errorMessage
        assertEquals(1, attempts)
        assertEquals(1, ErrorDiagnosticEvents.buffer.drain().size)
        for (wait in listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 960_000L, 1_920_000L, 3_600_000L, 3_600_000L)) {
            val previous = attempts
            advanceTimeBy(wait - 1)
            f.manager.syncAutomatically()
            assertEquals("Automatic requests must wait for the retry deadline", previous, attempts)
            advanceTimeBy(1)
            f.manager.syncAutomatically()
            assertEquals(previous + 1, attempts)
            assertEquals(firstMessage, f.manager.state.value.errorMessage)
            assertTrue(ErrorDiagnosticEvents.buffer.drain().isEmpty())
        }
        assertNull(f.manager.cache.cursor)
    }

    @Test fun authorizationResolutionBacksOffWithoutRepeatedErrorsAndVerifiesRootBeforeRecovery() = runTest {
        var needsResolution = false
        var attempts = 0
        val f = fixture(beforeRequest = {
            attempts++
            if (needsResolution) nativeDriveToken(null, true, emptyList(), NativeDriveAuthorizationContext(true, 0, true, true, true))
        })
        f.remote.addState("a", "book-a.json", SyncFormat.encode(remoteBook))
        f.manager.sync()
        runCurrent()
        val oldCache = f.manager.cache
        val oldRecord = f.store.state.books.getValue("book-a")
        ErrorDiagnosticEvents.buffer.drain()
        needsResolution = true
        f.manager.syncAutomatically()
        val first = attempts
        assertEquals(1, ErrorDiagnosticEvents.buffer.drain().size)
        f.manager.syncAutomatically()
        assertEquals("An authorization recovery request must wait for the retry deadline", first, attempts)
        advanceTimeBy(30_000)
        f.manager.syncAutomatically()
        assertEquals(first + 1, attempts)
        assertTrue(ErrorDiagnosticEvents.buffer.drain().isEmpty())
        assertEquals(oldCache.root, f.manager.cache.root)
        assertEquals(oldCache.cursor, f.manager.cache.cursor)
        assertEquals(oldRecord, f.store.state.books.getValue("book-a"))
        assertTrue(SyncFormat.decode<GoogleDriveSyncCache>(temporary.root.resolve("drive-sync.json").readText()).verifyConnection)
        assertEquals(ListenableWorker.Result.retry(), backgroundSyncResult(f.manager::syncInBackground))
        assertTrue(ErrorDiagnosticEvents.buffer.drain().isEmpty())
        needsResolution = false
        f.remote.requests.clear()
        f.manager.onNetworkAvailable()
        runCurrent()
        assertTrue("The root must be verified before using the old cursor", f.remote.requests.indexOf("files") < f.remote.requests.indexOf("changes"))
        assertFalse(f.manager.cache.verifyConnection)
        assertFalse(f.manager.state.value.attachPending)
        assertNull(f.manager.state.value.errorMessage)
        assertEquals(oldRecord, f.store.state.books.getValue("book-a"))
    }

    @Test fun manualSyncAndNetworkRecoveryBypassBackoffAndSuccessResetsIt() = runTest {
        var offline = true
        var attempts = 0
        val f = fixture(beforeRequest = {
            attempts++
            if (offline) throw GoogleDriveApiException(GoogleDriveApiException.NoInternetConnectionMessage)
        })
        f.manager.syncAutomatically()
        f.manager.sync()
        assertEquals(2, attempts)
        assertEquals(2, ErrorDiagnosticEvents.buffer.drain().size)
        offline = false
        f.manager.onNetworkAvailable()
        runCurrent()
        assertNotNull(f.manager.state.value.lastSync)
        assertNull(f.manager.state.value.errorMessage)
        offline = true
        val before = attempts
        f.manager.syncAutomatically()
        assertEquals(before + 1, attempts)
        assertEquals(1, ErrorDiagnosticEvents.buffer.drain().size)
        advanceTimeBy(30_000)
        f.manager.syncAutomatically()
        assertEquals(before + 2, attempts)
        assertTrue(ErrorDiagnosticEvents.buffer.drain().isEmpty())
    }

    @Test fun networkRecoveryResumesAutomaticChecksWhileHealthyPayloadIsStillRunning() = runTest {
        var offline = false
        var checks = 0
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val f = fixture(beforeRequest = {
            checks++
            if (offline) throw GoogleDriveMetadataNetworkException(IOException("metadata temporarily unavailable"))
        }, transferFactory = { client -> object : TestDriveFileTransfer(client) {
            override suspend fun upload(file: java.io.File, request: DriveUploadRequest, progress: (DriveTransferUpdate) -> Unit): GoogleDriveFile {
                started.complete(Unit)
                release.await()
                return super.upload(file, request, progress)
            }
        } })
        val root = f.books.createBookDirectory("book-a")
        val book = BookMetadata("a", "A", folder = "book-a", epub = "a.epub", lastAccess = 0.0)
        f.books.saveMetadata(root, book)
        root.resolve("a.epub").writeText("healthy payload")
        f.store.handleBookImport(book, root)
        f.manager.syncAutomatically()
        started.await()
        try {
            offline = true
            f.manager.syncAutomatically()
            assertFalse(release.isCompleted)
            offline = false
            f.manager.onNetworkAvailable()
            val recoveredChecks = checks
            f.manager.syncAutomatically()
            assertTrue("Network recovery must release the old deadline while the healthy transfer continues", checks > recoveredChecks)
        } finally {
            release.complete(Unit)
            runCurrent()
        }
    }

    @Test fun networkFailureDoesNotHideFirstAuthorizationRecoveryDiagnostic() = runTest {
        var recovery = false
        val f = fixture(beforeRequest = {
            if (recovery) nativeDriveToken(null, true, emptyList(), NativeDriveAuthorizationContext(true, 0, true, true, true))
            else throw GoogleDriveApiException(GoogleDriveApiException.NoInternetConnectionMessage)
        })
        f.manager.syncAutomatically()
        assertEquals(1, ErrorDiagnosticEvents.buffer.drain().size)
        recovery = true
        advanceTimeBy(30_000)
        f.manager.syncAutomatically()
        val events = ErrorDiagnosticEvents.buffer.drain()
        assertEquals(1, events.size)
        assertTrue(events.single().error!!.isSyncAuthorizationRecovery())
        advanceTimeBy(60_000)
        f.manager.syncAutomatically()
        assertTrue(ErrorDiagnosticEvents.buffer.drain().isEmpty())
    }

    @Test fun persistedAuthRecoveryRequiresRootValidationEvenIfManagerNeverRecordedFailure() = runTest {
        var pendingRecovery = false
        val f = fixture(beforeRequest = {
            if (pendingRecovery) assertTrue(SyncFormat.decode<GoogleDriveSyncCache>(temporary.root.resolve("drive-sync.json").readText()).verifyConnection)
        })
        f.manager.sync()
        f.manager.stop()
        assertFalse(f.manager.cache.verifyConnection)
        pendingRecovery = true
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val restarted = GoogleDriveSyncManager(f.store, GoogleDriveSyncHandler(f.client), f.client, f.settings, f.books,
            temporary.root, temporary.root.resolve("cache"), backgroundScope, dispatcher, dispatcher,
            { true }, {}, {}, TestDriveFileTransfer(f.client), { testScheduler.currentTime },
            authorizationRecoveryRequired = { pendingRecovery }, completeAuthorizationRecovery = {
                assertFalse(SyncFormat.decode<GoogleDriveSyncCache>(temporary.root.resolve("drive-sync.json").readText()).verifyConnection)
                pendingRecovery = false
            })
        f.remote.requests.clear()
        restarted.start()
        runCurrent()
        assertFalse(pendingRecovery)
        assertTrue(f.remote.requests.indexOf("files") < f.remote.requests.indexOf("changes"))
        assertFalse(restarted.state.value.attachPending)
    }

    @Test fun priorityPayloadAuthorizationRecoveryPreservesVerificationAndBackoff() = runTest {
        var attempts = 0
        val f = fixture(beforeRequest = { attempts++ }, transferFactory = { client -> object : TestDriveFileTransfer(client) {
            override suspend fun download(url: String, destination: java.io.File, size: Long?, progress: (DriveTransferUpdate) -> Unit) {
                nativeDriveToken(null, true, emptyList(), NativeDriveAuthorizationContext(true, 0, true, true, true))
            }
        } })
        val root = f.books.createBookDirectory("book-a")
        f.books.saveMetadata(root, BookMetadata("ID", "Local", folder = "book-a", epub = "book-a.epub", lastAccess = 0.0))
        root.resolve("book-a.epub").writeText("epub-content")
        f.store.handleBookImport(f.books.loadMetadata(root)!!, root)
        f.manager.sync()
        runCurrent()
        f.store.deleteLocalBook("book-a")
        f.manager.sync()
        val error = runCatching { f.manager.downloadBook(f.books.loadMetadata(root)!!) {} }.exceptionOrNull()!!
        assertTrue(error.isSyncAuthorizationRecovery())
        assertTrue(SyncFormat.decode<GoogleDriveSyncCache>(temporary.root.resolve("drive-sync.json").readText()).verifyConnection)
        val previous = attempts
        f.manager.syncAutomatically()
        assertEquals(previous, attempts)
        assertFalse(root.resolve("book-a.epub").exists())
    }

    @Test fun priorityStateAuthorizationRecoveryPreservesCauseAndRegistersOnlyOneBackoff() = runTest {
        var attempts = 0
        val f = fixture(beforeRequest = {
            attempts++
            nativeDriveToken(null, true, emptyList(), NativeDriveAuthorizationContext(true, 0, true, true, true))
        })
        val error = runCatching { f.manager.downloadBook(BookMetadata("ID", folder = "book-a", lastAccess = 0.0)) {} }.exceptionOrNull()!!
        assertTrue("An active reader waiter must receive the recovery failure", error.isSyncAuthorizationRecovery())
        assertFalse(error is kotlinx.coroutines.CancellationException)
        assertTrue(error.syncMessage().details().contains("requiresResolution=true"))
        assertNotNull(f.manager.state.value.errorMessage)
        assertEquals(1, attempts)
        advanceTimeBy(29_999)
        f.manager.syncAutomatically()
        assertEquals(1, attempts)
        advanceTimeBy(1)
        f.manager.syncAutomatically()
        assertEquals("The state failure must not register a second backoff in the reader waiter", 2, attempts)
    }

    @Test fun authorizationRecoveryStopsInFlightPriorityPayloadBeforeItCanBeApplied() = runTest {
        var needsResolution = false
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        val f = fixture(beforeRequest = {
            if (needsResolution) nativeDriveToken(null, true, emptyList(), NativeDriveAuthorizationContext(true, 0, true, true, true))
        }, transferFactory = { client -> object : TestDriveFileTransfer(client) {
            override suspend fun download(url: String, destination: java.io.File, size: Long?, progress: (DriveTransferUpdate) -> Unit) {
                started.complete(Unit)
                try { kotlinx.coroutines.awaitCancellation() } finally { cancelled = true }
            }
        } })
        val root = f.books.createBookDirectory("book-a")
        f.books.saveMetadata(root, BookMetadata("ID", "Local", folder = "book-a", epub = "book-a.epub", lastAccess = 0.0))
        root.resolve("book-a.epub").writeText("epub-content")
        f.store.handleBookImport(f.books.loadMetadata(root)!!, root)
        f.manager.sync()
        runCurrent()
        f.store.deleteLocalBook("book-a")
        f.manager.sync()
        val reader = async { runCatching { f.manager.downloadBook(f.books.loadMetadata(root)!!) {} } }
        started.await()
        needsResolution = true
        f.manager.syncAutomatically()
        runCurrent()
        assertTrue(cancelled)
        assertTrue("Cancelled payload I/O must still report its recovery failure to the active waiter", reader.await().exceptionOrNull()!!.isSyncAuthorizationRecovery())
        assertTrue(f.manager.cache.verifyConnection)
        assertFalse(root.resolve("book-a.epub").exists())
    }

    private class RecoveryIoGate(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        var shouldHold: () -> Boolean = { false }
        var held: Pair<CoroutineContext, Runnable>? = null
        private var captured = false
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (!captured && shouldHold()) {
                captured = true
                held = context to block
            } else delegate.dispatch(context, block)
        }
        fun release() {
            val (context, block) = checkNotNull(held)
            held = null
            delegate.dispatch(context, block)
        }
    }

    @Test fun staleReaderRecoveryMustNotOverwriteANewerSuccessfulSync() = runTest {
        val normal = StandardTestDispatcher(testScheduler)
        val io = RecoveryIoGate(normal)
        var checks = 0
        val f = fixture(normal, managerIo = io, beforeRequest = { checks++ }, transferFactory = { client ->
            object : TestDriveFileTransfer(client) {
                override suspend fun download(url: String, destination: java.io.File, size: Long?, progress: (DriveTransferUpdate) -> Unit) {
                    nativeDriveToken(null, true, emptyList(), NativeDriveAuthorizationContext(true, 0, true, true, true))
                }
            }
        })
        val root = f.books.createBookDirectory("book-a")
        val book = BookMetadata("ID", "Local", folder = "book-a", epub = "book-a.epub", lastAccess = 0.0)
        f.books.saveMetadata(root, book)
        root.resolve("book-a.epub").writeText("epub-content")
        f.store.handleBookImport(book, root)
        f.manager.sync()
        runCurrent()
        f.store.deleteLocalBook("book-a")
        f.manager.sync()
        runCurrent()
        io.shouldHold = { f.manager.cache.verifyConnection }
        val reader = async { runCatching { f.manager.downloadBook(f.books.loadMetadata(root)!!) {} } }
        runCurrent()
        assertNotNull("The old reader must pause during recovery persistence", io.held)
        assertTrue(f.manager.cache.verifyConnection)
        try {
            f.manager.onNetworkAvailable()
            runCurrent()
            assertFalse("The newer state check has verified the same root", f.manager.cache.verifyConnection)
            assertNull("The newer successful check cleared the visible error", f.manager.state.value.errorMessage)
        } finally {
            io.release()
        }
        runCurrent()
        assertTrue(reader.await().exceptionOrNull()!!.isSyncAuthorizationRecovery())
        assertNull("The old failure must not overwrite the newer successful recovery", f.manager.state.value.errorMessage)
        val before = checks
        f.manager.syncAutomatically()
        runCurrent()
        assertTrue("The old failure must not re-create backoff after recovery", checks > before)
    }

    @Test fun restartingForegroundPollingDoesNotResetOfflineBackoff() = runTest {
        var attempts = 0
        val f = fixture(beforeRequest = {
            attempts++
            throw GoogleDriveApiException(GoogleDriveApiException.NoInternetConnectionMessage)
        })
        f.manager.start()
        runCurrent()
        f.manager.pausePolling()
        f.manager.start()
        runCurrent()
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(1, attempts)
        advanceTimeBy(1)
        f.manager.syncAutomatically()
        assertEquals(2, attempts)
        assertEquals(1, ErrorDiagnosticEvents.buffer.drain().size)
    }

    @Test fun reauthorizationOfSameLibraryPreservesAssociationAndCloudPlaceholders() = runTest {
        val f = fixture()
        f.remote.addState("a", "book-a.json", SyncFormat.encode(remoteBook))
        f.manager.sync()
        runCurrent()
        val beforeCache = f.manager.cache
        val beforeRecord = f.store.state.books.getValue("book-a")
        val placeholder = f.store.bookDirectory("book-a")
        assertTrue(placeholder.isDirectory)
        f.manager.stop()
        f.manager.authorizationChanged()
        assertEquals(beforeCache.root, f.manager.cache.root)
        assertEquals(beforeCache.cursor, f.manager.cache.cursor)
        assertEquals(beforeRecord, f.store.state.books.getValue("book-a"))
        assertTrue(placeholder.isDirectory)
        assertTrue(f.manager.cache.verifyConnection)
        f.manager.start()
        runCurrent()
        assertFalse(f.manager.state.value.attachPending)
        assertFalse(f.manager.cache.verifyConnection)
        assertEquals(beforeCache.bookVersions, f.manager.cache.bookVersions)
        assertEquals(beforeRecord.generation, f.store.state.books.getValue("book-a").generation)
        assertTrue(placeholder.isDirectory)
    }

    @Test fun successfulManualSyncRestoresNormalPollingDuringLongBackoff() = runTest {
        var offline = true
        val f = fixture(beforeRequest = {
            if (offline) throw GoogleDriveApiException(GoogleDriveApiException.NoInternetConnectionMessage)
        })
        f.manager.start()
        runCurrent()
        advanceTimeBy(90_000)
        runCurrent()
        offline = false
        f.manager.sync()
        val before = f.remote.requests.size
        advanceTimeBy(if (BuildConfig.DEBUG) 5_000 else 120_000)
        runCurrent()
        assertTrue("Success must restore normal polling instead of sleeping through the old backoff", f.remote.requests.size > before)
    }

    @Test fun clearingCacheWhileReauthorizationIsOfflineCannotSkipRootValidation() = runTest {
        var offline = false
        val f = fixture(beforeRequest = {
            if (offline) throw GoogleDriveApiException(GoogleDriveApiException.NoInternetConnectionMessage)
        })
        f.remote.addState("a", "book-a.json", SyncFormat.encode(remoteBook))
        f.manager.sync()
        f.manager.stop()
        f.manager.authorizationChanged()
        offline = true
        f.manager.clearCache()
        runCurrent()
        assertEquals("r", f.manager.cache.root)
        assertTrue(f.manager.cache.verifyConnection)
    }

    @Test fun offlineReauthorizationSurvivesRestartAndValidatesBeforeUsingOldCursor() = runTest {
        var offline = false
        val f = fixture(beforeRequest = {
            if (offline) throw GoogleDriveApiException(GoogleDriveApiException.NoInternetConnectionMessage)
        })
        f.remote.addState("a", "book-a.json", SyncFormat.encode(remoteBook))
        f.manager.sync()
        runCurrent()
        val oldCache = f.manager.cache
        f.manager.stop()
        f.manager.authorizationChanged()
        val saved = SyncFormat.decode<GoogleDriveSyncCache>(temporary.root.resolve("drive-sync.json").readText())
        assertTrue(saved.verifyConnection)
        offline = true
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val restarted = GoogleDriveSyncManager(f.store, GoogleDriveSyncHandler(f.client), f.client, f.settings, f.books,
            temporary.root, temporary.root.resolve("cache"), backgroundScope, dispatcher, dispatcher,
            { true }, {}, {}, TestDriveFileTransfer(f.client), { testScheduler.currentTime })
        restarted.start()
        runCurrent()
        assertEquals(oldCache.root, restarted.cache.root)
        assertEquals(oldCache.cursor, restarted.cache.cursor)
        assertTrue(restarted.cache.verifyConnection)
        f.remote.requests.clear()
        offline = false
        restarted.onNetworkAvailable()
        runCurrent()
        assertFalse(restarted.cache.verifyConnection)
        assertTrue("Must validate the root before consuming the cached change cursor", f.remote.requests.indexOf("files") < f.remote.requests.indexOf("changes"))
        assertFalse(restarted.state.value.attachPending)
    }

    @Test fun reauthorizationToDifferentLibraryStillRequiresAttachWithoutPublishingLocalState() = runTest {
        val f = fixture()
        val folder = f.books.createBookDirectory("local-book")
        val local = BookMetadata("local", "Local", folder = "local-book", epub = "local.epub", lastAccess = 0.0)
        f.books.saveMetadata(folder, local)
        folder.resolve("local.epub").writeText("epub")
        f.store.handleBookImport(local, folder)
        f.manager.sync()
        runCurrent()
        assertEquals("r", f.manager.cache.root)
        assertTrue(f.store.state.books.getValue("local-book").attached)
        f.manager.stop()
        f.manager.authorizationChanged()
        f.remote.entries.clear()
        f.remote.folder("new-root", "Hoshi Reader", "root")
        f.remote.folder("new-state", "state", "new-root")
        f.remote.folder("new-books", "books", "new-root")
        f.remote.entries["remote"] = DriveService.Entry(
            GoogleDriveFile("remote", "other-book.json", "application/json", "1", parents = listOf("new-state"), createdTime = ""),
            SyncFormat.encode(remoteBook),
        )
        f.remote.writes.clear()
        f.manager.start()
        runCurrent()
        assertTrue(f.manager.state.value.attachPending)
        assertTrue(folder.resolve("local.epub").isFile)
        assertTrue(f.remote.writes.isEmpty())
    }

    @Test fun canceledReauthorizationResumesSyncAndPreservesCancellation() = runTest {
        var checks = 0
        val f = fixture(beforeRequest = { checks++ })
        f.manager.sync()
        runCurrent()
        val accepting = CompletableDeferred<Unit>()
        val auth = launch {
            f.manager.reauthorize {
                accepting.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        accepting.await()
        auth.cancel()
        auth.join()
        assertTrue(auth.isCancelled)
        val before = checks
        f.manager.onNetworkAvailable()
        runCurrent()
        assertTrue("Canceling authorization must not leave synchronization stopped", checks > before)
        assertFalse(f.manager.cache.verifyConnection)
    }

    @Test fun reauthorizationPersistsVerificationBeforeCredentialMutationAndBlocksLifecycleStart() = runTest {
        val f = fixture()
        f.manager.sync()
        val before = f.remote.requests.size
        val accepting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reconnect = async {
            f.manager.reauthorize {
                val saved = SyncFormat.decode<GoogleDriveSyncCache>(temporary.root.resolve("drive-sync.json").readText())
                assertTrue(saved.verifyConnection)
                accepting.complete(Unit)
                release.await()
            }
        }
        accepting.await()
        f.manager.start()
        f.manager.syncAutomatically()
        assertEquals(before, f.remote.requests.size)
        release.complete(Unit)
        reconnect.await()
        runCurrent()
        assertFalse(f.manager.cache.verifyConnection)
    }

    @Test fun workManagerRetriesShareOutageBackoffAndDoNotDuplicateHistory() = runTest {
        var attempts = 0
        val f = fixture(beforeRequest = {
            attempts++
            throw GoogleDriveApiException(GoogleDriveApiException.NoInternetConnectionMessage)
        })
        assertEquals(ListenableWorker.Result.retry(), backgroundSyncResult(f.manager::syncInBackground))
        assertEquals(1, ErrorDiagnosticEvents.buffer.drain().size)
        assertEquals(ListenableWorker.Result.retry(), backgroundSyncResult(f.manager::syncInBackground))
        assertEquals(1, attempts)
        assertTrue(ErrorDiagnosticEvents.buffer.drain().isEmpty())
        advanceTimeBy(30_000)
        assertEquals(ListenableWorker.Result.retry(), backgroundSyncResult(f.manager::syncInBackground))
        assertEquals(2, attempts)
        assertTrue(ErrorDiagnosticEvents.buffer.drain().isEmpty())
    }

    @Test fun perBookMetadataNetworkFailuresBackOffWithoutAdvancingCursorOrFloodingHistory() = runTest {
        val f = fixture()
        f.remote.addState("a", "book-a.json", SyncFormat.encode(remoteBook))
        var reads = 0
        f.remote.failRead = {
            reads++
            GoogleDriveMetadataNetworkException(IOException("fixture metadata network failure"))
        }
        f.manager.syncAutomatically()
        assertEquals(5, reads)
        assertNull(f.manager.cache.cursor)
        assertEquals(1, ErrorDiagnosticEvents.buffer.drain().size)
        f.manager.syncAutomatically()
        assertEquals(5, reads)
        advanceTimeBy(30_000)
        f.manager.syncAutomatically()
        assertEquals(10, reads)
        assertTrue(ErrorDiagnosticEvents.buffer.drain().isEmpty())
        f.remote.failRead = { null }
        f.manager.onNetworkAvailable()
        assertEquals("next", f.manager.cache.cursor)
        assertTrue(f.manager.state.value.queue.isEmpty())
    }

    @Test fun automaticPayloadNetworkFailuresBackOffEvenWhenMetadataChecksSucceed() = automaticPayloadBackoff {
        java.net.SocketException("fixture payload connection reset")
    }

    @Test fun transportClassifiedPayloadFailureBacksOffAndRecovers() = automaticPayloadBackoff {
        GoogleDriveUnavailableException(DrivePayloadNetworkIOException("redacted payload connection reset"))
    }

    private fun automaticPayloadBackoff(error: () -> Exception) = runTest {
        var attempts = 0
        var failUpload = true
        val f = fixture(transferFactory = { client -> object : TestDriveFileTransfer(client) {
            override suspend fun upload(file: java.io.File, request: DriveUploadRequest, progress: (DriveTransferUpdate) -> Unit): GoogleDriveFile {
                attempts++
                if (failUpload) throw error()
                return super.upload(file, request, progress)
            }
        } })
        val folder = f.books.createBookDirectory("book-a")
        val book = BookMetadata("local", "Local", folder = "book-a", epub = "book.epub", lastAccess = 0.0)
        f.books.saveMetadata(folder, book)
        folder.resolve("book.epub").writeText("epub")
        f.store.handleBookImport(book, folder)
        f.manager.syncAutomatically()
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(1, ErrorDiagnosticEvents.buffer.drain().size)
        f.manager.syncAutomatically()
        runCurrent()
        assertEquals(1, attempts)
        advanceTimeBy(30_000)
        f.manager.syncAutomatically()
        runCurrent()
        assertEquals(2, attempts)
        assertTrue(ErrorDiagnosticEvents.buffer.drain().isEmpty())
        failUpload = false
        f.manager.onNetworkAvailable()
        runCurrent()
        assertEquals(3, attempts)
        assertTrue(f.manager.state.value.queue.isEmpty())
        assertNotNull(f.store.state.books.getValue("book-a").files[SyncFileType.epub]?.value)
    }

    @Test fun mixedPayloadSuccessPublishesImmediatelyAndFailuresShareOneBackoff() = runTest {
        var failedAttempts = 0
        val healthyStarted = CompletableDeferred<Unit>()
        val releaseHealthy = CompletableDeferred<Unit>()
        var healthyCancelled = false
        val f = fixture(StandardTestDispatcher(testScheduler), transferFactory = { client -> object : TestDriveFileTransfer(client) {
            override suspend fun upload(file: java.io.File, request: DriveUploadRequest, progress: (DriveTransferUpdate) -> Unit): GoogleDriveFile {
                if (request.book != "book-a") {
                    failedAttempts++
                    throw GoogleDriveUnavailableException(DrivePayloadNetworkIOException("mixed payload reset"))
                }
                healthyStarted.complete(Unit)
                try { releaseHealthy.await() } catch (error: kotlinx.coroutines.CancellationException) {
                    healthyCancelled = true
                    throw error
                }
                return super.upload(file, request, progress)
            }
        } })
        for (name in listOf("book-a", "book-b", "book-c")) {
            val root = f.books.createBookDirectory(name)
            val book = BookMetadata(name, name, folder = name, epub = "$name.epub", lastAccess = 0.0)
            f.books.saveMetadata(root, book)
            root.resolve("$name.epub").writeText("epub")
            f.store.handleBookImport(book, root)
        }
        val worker = async { backgroundSyncResult(f.manager::syncInBackground) }
        try {
            runCurrent()
            assertTrue(healthyStarted.isCompleted)
            assertFalse("A book's exhausted network retries must not cancel healthy uploads", healthyCancelled)
            assertFalse(worker.isCompleted)
        } finally {
            releaseHealthy.complete(Unit)
        }
        assertEquals(ListenableWorker.Result.retry(), worker.await())
        assertEquals(2, failedAttempts)
        assertEquals("book-a.epub", f.publishedEpub())
        assertFalse(f.store.state.books.getValue("book-a").pending)
        assertEquals(1, ErrorDiagnosticEvents.buffer.drain().size)
        advanceTimeBy(29_999)
        f.manager.syncAutomatically()
        runCurrent()
        assertEquals(2, failedAttempts)
        advanceTimeBy(1)
        f.manager.syncAutomatically()
        runCurrent()
        assertEquals(4, failedAttempts)
        assertEquals("book-a.epub", f.publishedEpub())
        assertTrue(ErrorDiagnosticEvents.buffer.drain().isEmpty())
    }

    @Test fun networkRecoveryJoiningPublicationReleasesBackoff() = forcedRequestJoiningPublication(recoveredNetwork = true)

    @Test fun manualSyncJoiningPublicationReleasesBackoff() = forcedRequestJoiningPublication(recoveredNetwork = false)

    private fun forcedRequestJoiningPublication(recoveredNetwork: Boolean) = runTest {
        var offline = true
        var failedBookAttempts = 0
        val publicationReached = CompletableDeferred<Unit>()
        val releasePublication = CompletableDeferred<Unit>()
        lateinit var f: Fixture
        f = fixture(StandardTestDispatcher(testScheduler), transferFactory = { client -> object : TestDriveFileTransfer(client) {
            override suspend fun upload(file: java.io.File, request: DriveUploadRequest, progress: (DriveTransferUpdate) -> Unit): GoogleDriveFile {
                if (request.book == "book-b") {
                    failedBookAttempts++
                    if (offline) throw java.net.SocketException("temporarily unavailable")
                }
                return super.upload(file, request, progress)
            }
        } })
        for (name in listOf("book-a", "book-b")) {
            val root = f.books.createBookDirectory(name)
            val book = BookMetadata(name, name, folder = name, epub = "$name.epub", lastAccess = 0.0)
            f.books.saveMetadata(root, book)
            root.resolve("$name.epub").writeText("epub")
            f.store.handleBookImport(book, root)
        }
        f.manager.flushReader = { key ->
            if (key == "book-a" && f.store.state.books[key]?.files?.get(SyncFileType.epub)?.value != null) {
                publicationReached.complete(Unit)
                releasePublication.await()
            }
        }
        f.manager.syncAutomatically()
        runCurrent()
        assertTrue(publicationReached.isCompleted)
        assertEquals(1, failedBookAttempts)
        offline = false
        val forced = async {
            if (recoveredNetwork) f.manager.onNetworkAvailable() else f.manager.sync()
        }
        try {
            runCurrent()
            assertFalse("The forced request must join the active publication", forced.isCompleted)
        } finally {
            releasePublication.complete(Unit)
        }
        forced.await()
        runCurrent()
        val before = failedBookAttempts
        f.manager.syncAutomatically()
        runCurrent()
        assertTrue("The joined request must release the old outage deadline", failedBookAttempts > before)
    }

    @Test fun completedFileBatchDoesNotCountItsAlreadyReportedFailureAgain() = runTest {
        var offline = true
        var epubAttempts = 0
        val coverReached = CompletableDeferred<Unit>()
        val releaseCover = CompletableDeferred<Unit>()
        val f = fixture(StandardTestDispatcher(testScheduler), transferFactory = { client -> object : TestDriveFileTransfer(client) {
            override suspend fun upload(file: java.io.File, request: DriveUploadRequest, progress: (DriveTransferUpdate) -> Unit): GoogleDriveFile {
                if (request.type == SyncFileType.epub) {
                    epubAttempts++
                    if (offline) throw GoogleDriveMetadataNetworkException(IOException("metadata unavailable"))
                } else if (request.type == SyncFileType.cover) {
                    coverReached.complete(Unit)
                    releaseCover.await()
                }
                return super.upload(file, request, progress)
            }
        } })
        val root = f.books.createBookDirectory("book-a")
        val book = BookMetadata("a", "A", folder = "book-a", epub = "a.epub", cover = "cover.jpg", lastAccess = 0.0)
        f.books.saveMetadata(root, book)
        root.resolve("a.epub").writeText("epub")
        root.resolve("cover.jpg").writeText("cover")
        f.store.handleBookImport(book, root)
        f.manager.syncAutomatically()
        runCurrent()
        try {
            assertTrue(coverReached.isCompleted)
            assertEquals(1, epubAttempts)
            offline = false
            advanceTimeBy(30_000)
            f.manager.syncAutomatically()
        } finally {
            releaseCover.complete(Unit)
        }
        runCurrent()
        f.manager.syncAutomatically()
        runCurrent()
        assertEquals("The old batch error must not create another deadline", 2, epubAttempts)
    }

    @Test fun automaticPublicationFailuresShareOutageHistoryAndBackoff() = runTest {
        var fail = true
        var attempts = 0
        val f = uploadFixture {
            attempts++
            if (fail) throw GoogleDriveApiException(GoogleDriveApiException.NoInternetConnectionMessage)
        }
        assertEquals(ListenableWorker.Result.retry(), backgroundSyncResult(f.manager::syncInBackground))
        assertEquals(1, ErrorDiagnosticEvents.buffer.drain().size)
        val firstAttempts = attempts
        assertEquals(ListenableWorker.Result.retry(), backgroundSyncResult(f.manager::syncInBackground))
        assertEquals(firstAttempts, attempts)
        advanceTimeBy(30_000)
        assertEquals(ListenableWorker.Result.retry(), backgroundSyncResult(f.manager::syncInBackground))
        assertTrue(attempts > firstAttempts)
        assertTrue("Publication must not record the same automatic outage again", ErrorDiagnosticEvents.buffer.drain().isEmpty())
        fail = false
        f.manager.onNetworkAvailable()
        runCurrent()
        assertEquals("book-a.epub", f.publishedEpub())
    }

    @Test fun queuedDuplicateCannotRetryAfterWaitingForAFailedBook() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        val f = fixture(transferFactory = { client -> object : TestDriveFileTransfer(client) {
            override suspend fun upload(file: java.io.File, request: DriveUploadRequest, progress: (DriveTransferUpdate) -> Unit): GoogleDriveFile {
                attempts++
                if (attempts == 1) {
                    started.complete(Unit)
                    release.await()
                    throw java.net.SocketException("overlapping payload reset")
                }
                return super.upload(file, request, progress)
            }
        } })
        f.settings.update { it.copy(transferConcurrency = 1) }
        val folder = f.books.createBookDirectory("book-a")
        val book = BookMetadata("local", "Local", folder = "book-a", epub = "book-a.epub", lastAccess = 0.0)
        f.books.saveMetadata(folder, book)
        folder.resolve("book-a.epub").writeText("epub")
        f.store.handleBookImport(book, folder)
        val worker = async { backgroundSyncResult(f.manager::syncInBackground) }
        runCurrent()
        assertTrue(started.isCompleted)
        f.manager.syncAutomatically()
        runCurrent()
        release.complete(Unit)
        assertEquals(ListenableWorker.Result.retry(), worker.await())
        assertEquals("A duplicate waiting on the book permit must not retry the failed upload", 1, attempts)
        assertNull(f.publishedEpub())
        assertNull(f.store.state.books.getValue("book-a").files[SyncFileType.epub])
        assertNotNull(f.store.state.books.getValue("book-a").sources[SyncFileType.epub])
        advanceTimeBy(30_000)
        assertEquals(ListenableWorker.Result.success(), backgroundSyncResult(f.manager::syncInBackground))
        assertEquals(2, attempts)
        assertEquals("book-a.epub", f.publishedEpub())
    }

    @Test fun supersededPriorityDownloadCannotUseOldAssociationDuringVerification() = runTest {
        var blocked = false
        var blockedRequests = 0
        var payloads = 0
        val f = fixture(beforeRequest = {
            if (blocked) {
                blockedRequests++
                kotlinx.coroutines.awaitCancellation()
            }
        }, transferFactory = { client -> object : TestDriveFileTransfer(client) {
            override suspend fun download(url: String, destination: java.io.File, size: Long?, progress: (DriveTransferUpdate) -> Unit) {
                payloads++
                super.download(url, destination, size, progress)
            }
        } })
        for (name in listOf("book-a", "book-b")) {
            f.remote.addState(name, "$name.json", SyncFormat.encode(remoteBook.copy(files = mapOf(
                SyncFileType.epub to Timestamped(1000, "$name.epub"),
            ))))
        }
        f.manager.sync()
        runCurrent()
        val a = f.books.loadMetadata(f.store.bookDirectory("book-a"))!!
        val b = f.books.loadMetadata(f.store.bookDirectory("book-b"))!!
        f.manager.stop()
        f.manager.authorizationChanged()
        blocked = true
        f.manager.start()
        runCurrent()
        val first = async { runCatching { f.manager.downloadBook(a) {} } }
        runCurrent()
        val second = async { runCatching { f.manager.downloadBook(b) {} } }
        runCurrent()
        assertTrue(f.manager.cache.verifyConnection)
        assertEquals("Only the three root checks may request authorization", 3, blockedRequests)
        assertTrue("Superseded book must stop before using the old reference", first.isCompleted)
        assertTrue(first.await().exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals(0, payloads)
        f.manager.stop()
        runCurrent()
        second.await()
    }

    @Test fun foregroundRunFailurePublishesAndRecordsOriginalCause() = runTest {
        val f = fixture()
        f.remote.addState("a", "book-a.json", SyncFormat.encode(remoteBook))
        val error = GoogleDriveUnavailableException(IOException("Network unavailable", IOException("DNS lookup failed")))
        f.remote.failRead = { error }
        f.manager.sync()
        runCurrent()
        val message = f.manager.state.value.errorMessage.details()
        assertTrue(message, message.contains("Network unavailable"))
        assertTrue(message, message.contains("DNS lookup failed"))
        assertSame(error, ErrorDiagnosticEvents.buffer.drain().single().error)
    }

    @Test fun foregroundBookFailureKeepsHttpStatusCauseAndBookContext() = runTest {
        val f = fixture()
        f.remote.addState("a", "book-a.json", SyncFormat.encode(remoteBook))
        val error = GoogleDriveApiException("Drive rejected the state file", 403).apply {
            initCause(IOException("Storage quota exhausted"))
        }
        f.remote.failRead = { error }
        f.manager.sync()
        runCurrent()
        val message = f.manager.state.value.queue.single().error.details()
        for (detail in listOf("book-a", "403", "Drive rejected the state file", "Storage quota exhausted")) {
            assertTrue(message, message.contains(detail))
        }
        assertSame(error, ErrorDiagnosticEvents.buffer.drain().single().error)
    }

    @Test fun fileFailuresKeepEveryFileAndRetryALaterRateLimit() = runTest {
        val f = fixture()
        f.remote.addState("a", "book-a.json", SyncFormat.encode(remoteBook.copy(files = mapOf(
            SyncFileType.cover to Timestamped(1000, "cover.jpg"),
            SyncFileType.sasayaki to Timestamped(1000, "matches.json"),
        ))))
        f.remote.folder("book-folder", "book-a", "b")
        f.remote.folder("generation-folder", "1", "book-folder")
        for ((id, name) in listOf("cover" to "cover.jpg", "matches" to "matches.json")) {
            f.remote.entries[id] = DriveService.Entry(GoogleDriveFile(id, name, "application/octet-stream", "1", size = "4", parents = listOf("generation-folder"), createdTime = ""), "data")
        }
        val denied = GoogleDriveApiException("Cover permission denied", 403)
        val limited = GoogleDriveApiException("Matches rate limited", 403, reasons = setOf("userRateLimitExceeded"))
        f.remote.failRead = { when (it) { "cover" -> denied; "matches" -> limited; else -> null } }
        val result = backgroundSyncResult(f.manager::syncInBackground)
        assertEquals(f.manager.state.value.errorMessage.details() + f.manager.state.value.queue.map { it.error.details() }, ListenableWorker.Result.retry(), result)
        val message = f.manager.state.value.queue.single().error.details()
        for (detail in listOf("Remote", "cover image", "Sasayaki matches", "Cover permission denied", "Matches rate limited")) assertTrue(message, message.contains(detail))
        val events = ErrorDiagnosticEvents.buffer.drain()
        assertEquals(setOf(denied, limited), events.map { it.error }.toSet())
        assertEquals(2, events.size)
    }

    @Test fun manualLibrarySyncPropagatesOriginalFailure() = runTest {
        val f = fixture()
        val error = GoogleDriveUnavailableException(IOException("Library connection failed", IOException("Host unreachable")))
        f.remote.failRequest = { error }
        assertSame(error, runCatching { f.manager.syncOrThrow() }.exceptionOrNull())
        assertTrue(f.manager.state.value.errorMessage.details().contains("Host unreachable"))
        assertSame(error, ErrorDiagnosticEvents.buffer.drain().single().error)
    }

    @Test fun openingCloudBookPropagatesOriginalStateFailure() = runTest {
        val f = fixture()
        f.remote.addState("a", "book-a.json", SyncFormat.encode(remoteBook))
        f.manager.sync()
        runCurrent()
        val book = f.books.loadMetadata(f.store.bookDirectory("book-a"))!!
        val error = GoogleDriveApiException("Book metadata unavailable", 403).apply {
            initCause(IOException("Permission revoked"))
        }
        f.remote.failRead = { error }
        assertSame(error, runCatching { f.manager.downloadBook(book) {} }.exceptionOrNull())
        assertTrue(f.manager.state.value.queue.single().error.details().contains("Permission revoked"))
        assertSame(error, ErrorDiagnosticEvents.buffer.drain().single().error)
    }

    @Test fun mergeLibraryFailureReachesDialogAndHistory() = attachFailure(AttachChoice.Merge)
    @Test fun replaceFromCloudFailureReachesDialogAndHistory() = attachFailure(AttachChoice.Cloud)
    @Test fun replaceFromDeviceFailureReachesDialogAndHistory() = attachFailure(AttachChoice.Device)

    private fun attachFailure(choice: AttachChoice) = runTest {
        val f = fixture()
        val error = GoogleDriveApiException("Library request rejected", 403).apply {
            initCause(IOException("Account storage full"))
        }
        f.remote.failRequest = { error }
        val visibleError = MutableStateFlow<UiText?>(null)
        visibleError.value = syncOperationError { f.manager.attach(choice) }
        val message = visibleError.value.details()
        for (detail in listOf("Library request rejected", "403", "Account storage full")) assertTrue(message, message.contains(detail))
        assertSame(error, ErrorDiagnosticEvents.buffer.drain().single().error)
    }

    private suspend fun TestScope.fixture(
        dispatcher: TestDispatcher = UnconfinedTestDispatcher(testScheduler),
        transferFactory: (GoogleDriveClient) -> DriveFileTransfer = { TestDriveFileTransfer(it) },
        booksDispatcher: CoroutineDispatcher = dispatcher,
        managerIo: CoroutineDispatcher = dispatcher,
        beforeRequest: suspend () -> Unit = {},
    ): Fixture {
        val remote = DriveService()
        val books = BookRepository(temporary.root, booksDispatcher)
        val store = SyncStorage(temporary.root, books, dispatcher)
        val settings = SyncSettingsRepository(MemoryPreferences(), NoTtu, dispatcher)
        settings.update { it.copy(enabled = true) }
        val token = object : DriveAccessTokenProvider {
            override suspend fun accessToken(): String {
                beforeRequest()
                return "token"
            }
        }
        val client = GoogleDriveClient(token, { settings.settings.first().provider }, {}, dispatcher, remote::connection)
        val handler = GoogleDriveSyncHandler(client)
        val manager = GoogleDriveSyncManager(store, handler, client, settings, books, temporary.root,
            temporary.root.resolve("cache").apply { mkdirs() }, CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job])), dispatcher, managerIo, { true }, {}, {}, transferFactory(client), { testScheduler.currentTime })
        return Fixture(remote, books, store, manager, settings, client)
    }

    @Test fun enabledTtuKeepsSharedTransportAvailableAcrossLifecycleTransitions() = runTest {
        val f = fixture()
        f.settings.update { it.copy(provider = SyncProvider.Ttu) }
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this)
        }
        owner.lifecycle.currentState = Lifecycle.State.CREATED
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeSyncPolling(owner.lifecycle, f.settings.settings, f.manager)
        }
        for (state in listOf(Lifecycle.State.CREATED, Lifecycle.State.STARTED, Lifecycle.State.CREATED)) {
            owner.lifecycle.currentState = state
            runCurrent()
            val requests = f.remote.requests.size
            f.client.request("files", query = mapOf("q" to "'root' in parents"))
            assertTrue(f.remote.requests.size > requests)
            val afterRequest = f.remote.requests.size
            advanceTimeBy(if (BuildConfig.DEBUG) 10_000 else 240_000)
            runCurrent()
            assertEquals("TTU must not start Hoshi polling", afterRequest, f.remote.requests.size)
        }
    }

    @Test fun backgroundColdStartDoesNotPollAndForegroundTransitionsControlPolling() = runTest {
        val f = fixture()
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this)
        }
        owner.lifecycle.currentState = Lifecycle.State.CREATED
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeSyncPolling(owner.lifecycle, f.settings.settings, f.manager)
        }
        val interval = if (BuildConfig.DEBUG) 5_000L else 120_000L
        advanceTimeBy(interval * 2)
        runCurrent()
        assertTrue("A background launch must not start foreground polling", f.remote.requests.isEmpty())

        f.manager.syncInBackground()
        val backgroundRequests = f.remote.requests.size
        assertTrue(backgroundRequests > 0)
        advanceTimeBy(interval * 2)
        runCurrent()
        assertEquals(backgroundRequests, f.remote.requests.size)

        owner.lifecycle.currentState = Lifecycle.State.STARTED
        runCurrent()
        val foregroundRequests = f.remote.requests.size
        assertTrue(foregroundRequests > backgroundRequests)
        advanceTimeBy(interval)
        runCurrent()
        assertTrue(f.remote.requests.size > foregroundRequests)

        owner.lifecycle.currentState = Lifecycle.State.CREATED
        runCurrent()
        val stoppedRequests = f.remote.requests.size
        advanceTimeBy(interval * 2)
        runCurrent()
        assertEquals(stoppedRequests, f.remote.requests.size)
        f.settings.update { it.copy(enabled = false) }
        f.settings.update { it.copy(enabled = true) }
        runCurrent()
        assertEquals(stoppedRequests, f.remote.requests.size)

        owner.lifecycle.currentState = Lifecycle.State.STARTED
        runCurrent()
        assertTrue(f.remote.requests.size > stoppedRequests)
        f.settings.update { it.copy(enabled = false) }
        runCurrent()
        val disabledRequests = f.remote.requests.size
        advanceTimeBy(interval * 2)
        runCurrent()
        assertEquals(disabledRequests, f.remote.requests.size)
    }

    @Test fun cancelingForegroundWaiterLetsBackgroundPassFinishAndReleaseSyncState() = runTest {
        val f = fixture()
        f.remote.addState("a", "book-a.json", SyncFormat.encode(remoteBook))
        val gate = CompletableDeferred<Unit>()
        f.manager.flushReader = { gate.await() }
        val foreground = backgroundScope.launch { f.manager.sync() }
        runCurrent()
        assertTrue(f.manager.state.value.isSyncing)
        foreground.cancel()
        val background = backgroundScope.launch { f.manager.syncInBackground() }
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertFalse(f.manager.state.value.isSyncing)
        assertTrue(background.isCompleted)
        assertEquals("next", f.manager.cache.cursor)
        f.manager.sync()
        assertNull(f.manager.state.value.errorMessage)
    }

    @Test fun backgroundRetryIncludesTransientFailuresAfterAPermanentBookFailure() = runTest {
        val f = fixture()
        f.remote.addState("a", "book-a.json", SyncFormat.encode(remoteBook))
        f.remote.addState("b", "book-b.json", SyncFormat.encode(remoteBook))
        f.remote.failRead = { id ->
            if (id == "a") GoogleDriveApiException("Permission denied", 403)
            else GoogleDriveApiException("Rate limited", 429)
        }
        assertEquals(ListenableWorker.Result.retry(), backgroundSyncResult(f.manager::syncInBackground))
        assertEquals(2, f.manager.state.value.queue.count { it.error != null })
    }

    @Test fun runLevelFormatFailureStillStopsRetriesDespiteEarlierRateLimitedBooks() = runTest {
        val f = fixture()
        f.remote.addState("a", "book-a.json", SyncFormat.encode(remoteBook))
        f.remote.failRead = { id -> if (id == "a") GoogleDriveApiException("Rate limited", 429) else null }
        assertEquals(ListenableWorker.Result.retry(), backgroundSyncResult(f.manager::syncInBackground))
        f.remote.addState("b", "book-b.json", SyncFormat.encode(remoteBook).replace("\"formatVersion\":1", "\"formatVersion\":2"))
        assertEquals(ListenableWorker.Result.failure(), backgroundSyncResult(f.manager::syncInBackground))
    }

    @Test fun capturesCursorBeforeListingAndSkipsUnchangedStateAfterRestart() = runTest {
        val f = fixture()
        f.remote.addState("a", "book-a.json", SyncFormat.encode(remoteBook))
        f.manager.sync()
        runCurrent()
        assertNull(f.manager.state.value.errorMessage)
        assertEquals("next", f.manager.cache.cursor)
        assertEquals("changes/startPageToken", f.remote.requests.first())
        assertEquals(1, f.remote.reads["a"])
        val metadata = f.books.loadMetadata(f.store.bookDirectory("book-a"))!!
        f.manager.sync(metadata)
        assertEquals(2, f.remote.reads["a"])
        assertEquals(1, f.remote.queries.count { "book-a.json" in it })
        f.manager.stop()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SyncSettingsRepository(MemoryPreferences(), NoTtu, dispatcher)
        settings.update { it.copy(enabled = true) }
        val client = GoogleDriveClient(object : DriveAccessTokenProvider { override suspend fun accessToken() = "token" }, { SyncProvider.Gdrive }, {}, dispatcher, f.remote::connection)
        val restarted = GoogleDriveSyncManager(f.store, GoogleDriveSyncHandler(client), client, settings, f.books, temporary.root, temporary.root.resolve("cache"), CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job])), dispatcher, dispatcher, { true }, {}, {}, TestDriveFileTransfer(client))
        restarted.sync(metadata)
        assertEquals(3, f.remote.reads["a"])
        assertEquals(1, f.remote.queries.count { "book-a.json" in it })
        assertEquals(mapOf("a" to "1"), restarted.cache.bookVersions["book-a"])
    }

    @Test fun mergesDuplicateDocumentsInIdOrderBeforeTrashingDuplicates() = runTest {
        val f = fixture()
        f.remote.addState("z", "book-a.json", SyncFormat.encode(remoteBook.copy(metadata = Timestamped(1000, SyncMetadata("Second")))))
        f.remote.addState("a", "book-a.json", SyncFormat.encode(remoteBook))
        f.manager.sync()
        runCurrent()
        assertNull(f.manager.state.value.errorMessage)
        assertEquals("Remote", f.store.loadBook("book-a")!!.metadata.value.title)
        assertEquals(listOf("z"), f.remote.trashed)
        assertEquals(listOf("a"), f.remote.writes)
        assertTrue(f.remote.requests.indexOf("read/z") < f.remote.requests.indexOf("trash/z"))
        assertFalse(f.store.state.books.getValue("book-a").pending)
    }

    @Test fun rejectsUnsupportedFormatWithoutCursorAdvanceOrFileTransfers() = runTest {
        val f = fixture()
        f.remote.addState("a", "book-a.json", SyncFormat.encode(remoteBook).replace("\"formatVersion\":1", "\"formatVersion\":2"))
        f.manager.sync()
        runCurrent()
        assertNotNull(f.manager.state.value.errorMessage)
        assertNull(f.manager.cache.cursor)
        assertTrue(f.store.state.books.isEmpty())
        assertTrue(f.remote.writes.isEmpty())
        f.remote.entries.getValue("a").data = SyncFormat.encode(remoteBook)
        f.manager.sync(BookMetadata("id", folder = "book-a", lastAccess = 0.0))
        try {
            f.manager.downloadBook(f.books.loadMetadata(f.store.bookDirectory("book-a"))!!) {}
            fail("Targeted success must not clear the format latch")
        } catch (_: SyncFormatError) {
            Unit
        }
        f.manager.sync()
        assertEquals("next", f.manager.cache.cursor)
        assertNull(f.manager.state.value.errorMessage)
    }

    @Test fun persistsEditDuringUploadAsPending() = runTest {
        val f = fixture()
        f.remote.addState("a", "book-a.json", SyncFormat.encode(remoteBook))
        f.manager.sync()
        val root = f.store.bookDirectory("book-a")
        f.books.saveMetadata(root, f.books.loadMetadata(root)!!.copy(renamedTitle = "First edit", modified = 2000))
        f.remote.beforeWrite = {
            f.books.saveMetadata(root, f.books.loadMetadata(root)!!.copy(renamedTitle = "Later edit", modified = 3000))
        }
        f.manager.sync()
        assertTrue(f.store.state.books.getValue("book-a").pending)
        assertEquals("Later edit", f.store.loadBook("book-a")!!.metadata.value.title)
        assertEquals("First edit", SyncFormat.decode<SyncBook>(f.remote.entries.getValue("a").data).metadata.value.title)
    }

    @Test fun reusesOwnWrittenStateUntilAnotherDeviceWrites() = runTest {
        val f = fixture()
        f.remote.addState("a", "book-a.json", SyncFormat.encode(remoteBook))
        f.manager.sync()
        assertEquals(1, f.remote.reads["a"])
        val root = f.store.bookDirectory("book-a")
        f.books.saveMetadata(root, f.books.loadMetadata(root)!!.copy(renamedTitle = "First edit", modified = 2000))
        f.manager.sync()
        f.books.saveMetadata(root, f.books.loadMetadata(root)!!.copy(renamedTitle = "Second edit", modified = 3000))
        f.manager.sync()
        assertEquals(1, f.remote.reads["a"])
        assertEquals("Second edit", SyncFormat.decode<SyncBook>(f.remote.entries.getValue("a").data).metadata.value.title)
        f.remote.entries.getValue("a").apply {
            file = file.copy(md5Checksum = "9")
            data = SyncFormat.encode(remoteBook.copy(metadata = Timestamped(4000, SyncMetadata("Other device"))))
        }
        f.manager.sync()
        assertEquals(2, f.remote.reads["a"])
        assertEquals("Other device", f.store.loadBook("book-a")!!.metadata.value.title)
    }

    @Test fun exposesEveryActiveBookAndHonorsLiveConcurrencyChanges() = runTest {
        val gate = CompletableDeferred<Unit>()
        val f = fixture(transferFactory = { client -> object : TestDriveFileTransfer(client) {
            override suspend fun upload(file: java.io.File, request: DriveUploadRequest, progress: (DriveTransferUpdate) -> Unit): GoogleDriveFile {
                progress(DriveTransferUpdate(2, file.length(), deltaBytes = 2))
                gate.await()
                return super.upload(file, request, progress)
            }
        } })
        f.settings.update { it.copy(transferConcurrency = 2) }
        repeat(3) { index ->
            val name = "book-$index"
            val root = f.books.createBookDirectory(name)
            f.books.saveMetadata(root, BookMetadata(name, name, folder = name, epub = "$name.epub", lastAccess = 0.0))
            root.resolve("$name.epub").writeText("sample-epub")
            f.store.handleBookImport(f.books.loadMetadata(root)!!, root)
        }
        f.manager.sync()
        advanceTimeBy(501)
        runCurrent()
        assertEquals(2, f.manager.state.value.activeTransfers.size)
        assertTrue(f.manager.state.value.activeTransfers.values.all { it.bytes == 2L })
        f.settings.update { it.copy(transferConcurrency = 3) }
        runCurrent()
        assertEquals(3, f.manager.state.value.activeTransfers.size)
        gate.complete(Unit)
        runCurrent()
        assertTrue(f.manager.state.value.activeTransfers.isEmpty())
        assertTrue(f.manager.state.value.queue.isEmpty())
    }

    @Test fun exhaustedMetadataTimeoutDoesNotCancelEightHealthyUploads() = runTest {
        val gate = CompletableDeferred<Unit>()
        val f = fixture(transferFactory = { client -> object : TestDriveFileTransfer(client) {
            override suspend fun upload(file: java.io.File, request: DriveUploadRequest, progress: (DriveTransferUpdate) -> Unit): GoogleDriveFile {
                progress(DriveTransferUpdate(2, file.length(), deltaBytes = 2))
                gate.await()
                return super.upload(file, request, progress)
            }
        } })
        f.settings.update { it.copy(transferConcurrency = 8) }
        repeat(8) { index ->
            val name = "book-$index"
            val root = f.books.createBookDirectory(name)
            f.books.saveMetadata(root, BookMetadata(name, name, folder = name, epub = "$name.epub", lastAccess = 0.0))
            root.resolve("$name.epub").writeText("sample-epub")
            f.store.handleBookImport(f.books.loadMetadata(root)!!, root)
        }
        f.manager.sync()
        advanceTimeBy(501)
        runCurrent()
        assertEquals(8, f.manager.state.value.activeTransfers.size)
        val cursor = f.manager.cache.cursor
        f.remote.failRequest = { java.net.SocketTimeoutException("metadata timeout") }
        f.manager.sync()
        runCurrent()
        assertNotNull(f.manager.state.value.errorMessage)
        assertEquals(cursor, f.manager.cache.cursor)
        assertEquals("Metadata failure must not cancel payloads", 8, f.manager.state.value.activeTransfers.size)
        f.remote.failRequest = null
        gate.complete(Unit)
        runCurrent()
        assertTrue(f.manager.state.value.activeTransfers.isEmpty())
        assertNull(f.manager.state.value.errorMessage)
        assertTrue(f.manager.state.value.queue.isEmpty())
        assertEquals(8, f.store.state.books.values.count { !it.pending && it.files[SyncFileType.epub]?.value != null })
    }

    @Test fun aSingleDevicesOwnPlaybackUploadsNeverRequestASeekOnReadback() = runTest {
        val f = fixture()
        val root = f.books.createBookDirectory("book-a")
        val book = BookMetadata("ID", "Local", folder = "book-a", lastAccess = 0.0)
        f.books.saveMetadata(root, book)
        f.store.handleBookImport(book, root)
        val playback = SasayakiPlaybackData(2.0, delay = 0.25, rate = 1.2f)
        f.books.saveSasayakiPlayback(root, playback)
        var livePosition = 2.7
        val seekRequests = mutableListOf<Double>()
        var appliedSnapshots = 0
        f.store.applyReaderState = { _, state, _, audiobookChanged, _ ->
            appliedSnapshots++
            if (audiobookChanged) {
                livePosition = state.audiobook!!.value.lastPosition
                seekRequests += livePosition
            }
        }
        // The only writer is this device. Playback advances during its first upload,
        // then each following pass reads/merges state written by this same manager.
        f.remote.beforeWrite = {
            f.books.saveSasayakiPlayback(root, playback.copy(lastPosition = 3.5))
            livePosition = 4.2
        }
        f.manager.sync()
        f.manager.sync()
        for (position in listOf(4.5, 6.5, 8.5)) {
            f.books.saveSasayakiPlayback(root, playback.copy(lastPosition = position))
            livePosition = position + 0.7
            f.manager.sync()
        }
        f.manager.sync()

        assertNull(f.manager.state.value.errorMessage)
        assertTrue(appliedSnapshots >= 5)
        assertTrue(seekRequests.isEmpty())
        assertEquals(9.2, livePosition, 0.000001)
        assertEquals(8.5, f.books.loadSasayakiPlayback(root)!!.lastPosition, 0.0)
        val uploaded = f.remote.entries.values.single { it.file.name == "book-a.json" }
        assertEquals(8.5, SyncFormat.decode<SyncBook>(uploaded.data).audiobook!!.value.lastPosition, 0.0)
    }

    @Test fun publishesFilesOnlyAfterUploadAndDownloadsEpubOnlyOnDemand() = runTest {
        val f = fixture()
        val root = f.books.createBookDirectory("book-a")
        f.books.saveMetadata(root, BookMetadata("ID", "Local", folder = "book-a", epub = "book-a.epub", lastAccess = 0.0))
        root.resolve("book-a.epub").writeText("epub-content")
        f.store.handleBookImport(f.books.loadMetadata(root)!!, root)
        f.manager.sync()
        runCurrent()
        val reference = f.store.state.books.getValue("book-a").files.getValue(SyncFileType.epub)
        assertEquals("book-a.epub", reference.value)
        assertFalse(f.store.state.books.getValue("book-a").pending)
        assertFalse(f.manager.state.value.isSyncing)
        assertNull(f.manager.state.value.progress)
        assertTrue(f.manager.state.value.queue.isEmpty())
        f.manager.sync()
        f.store.deleteLocalBook("book-a")
        f.manager.sync()
        runCurrent()
        assertFalse(root.resolve("book-a.epub").exists())
        f.manager.downloadBook(f.books.loadMetadata(root)!!) {}
        assertEquals("epub-content", root.resolve("book-a.epub").readText())
        assertEquals(reference.modified, f.store.state.books.getValue("book-a").sources[SyncFileType.epub])
    }

    @Test fun liveTranscriptionUploadsOnlyTheMatchSavedOnPause() = transcriptionUploadsFinalMatch(false)

    @Test fun completedTranscriptionWhileSyncIsDisabledUploadsOnceAfterEnabling() = transcriptionUploadsFinalMatch(true)

    private fun transcriptionUploadsFinalMatch(disabled: Boolean) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val uploadedMatches = mutableListOf<String>()
        val f = fixture(dispatcher, transferFactory = { client -> object : TestDriveFileTransfer(client) {
            override suspend fun upload(file: java.io.File, request: DriveUploadRequest, progress: (DriveTransferUpdate) -> Unit): GoogleDriveFile {
                if (request.type == SyncFileType.sasayaki) uploadedMatches += file.readText()
                return super.upload(file, request, progress)
            }
        } })
        val root = f.books.createBookDirectory("book-a")
        writeMinimalExtractedEpub(root, firstChapterHtml = "<body><p>今日は、いい天気です。明日も晴れるでしょう。</p></body>")
        val metadata = BookMetadata("ID", "Local", folder = "book-a", lastAccess = 0.0)
        f.books.saveMetadata(root, metadata)
        f.store.handleBookImport(metadata, root)
        f.manager.sync(); runCurrent()
        if (disabled) { f.settings.update { it.copy(enabled = false) }; runCurrent() }
        val repository = AndroidSasayakiTranscriptionRepository(SasayakiTranscriptStore(dispatcher), f.books,
            EpubBookParser(temporary.newFolder()), dispatcher, dispatcher)
        val batches = Channel<SasayakiTranscriptionBatch>(Channel.UNLIMITED)
        val backend = object : SasayakiTranscriptionBackend {
            override suspend fun duration(source: String) = 30.0
            override suspend fun transcribe(source: String, from: Double, onDownloadRequired: suspend (Long) -> Unit,
                onDownload: suspend (Double) -> Unit, onBatch: suspend (SasayakiTranscriptionBatch) -> Unit, parallelism: Int,
            ) { for (batch in batches) onBatch(batch) }
        }
        val coordinator = SasayakiTranscriptionCoordinator(backend, repository, f.books.workRegistry, backgroundScope,
            dispatcher, SasayakiTranscriptionClock { testScheduler.currentTime * 1_000_000 })
        coordinator.start(root, "audio")
        val words = listOf(
            listOf(SasayakiToken("今日は", 1.0, 2.0), SasayakiToken("いい天気です", 2.0, 4.0)),
            listOf(SasayakiToken("明日も晴れるでしょう", 5.0, 8.0)),
        )
        for ((index, tokens) in words.withIndex()) {
            batches.send(SasayakiTranscriptionBatch(tokens, tokens.last().end + 1.0))
            runCurrent(); advanceTimeBy(15_000); runCurrent()
            f.manager.sync(); runCurrent()
            assertEquals(index + 2, coordinator.state.value.match!!.matches.size)
            assertNull(f.books.loadSasayakiMatch(root))
            assertNull(f.store.state.books.getValue("book-a").sources[SyncFileType.sasayaki])
            assertTrue(uploadedMatches.isEmpty())
        }
        if (disabled) batches.close() else coordinator.pause(root)
        runCurrent()
        assertFalse(coordinator.state.value.running)
        assertEquals(3, f.books.loadSasayakiMatch(root)!!.matches.size)
        assertNotNull(f.store.state.books.getValue("book-a").sources[SyncFileType.sasayaki])
        if (disabled) {
            f.manager.sync(); runCurrent()
            assertTrue(uploadedMatches.isEmpty())
            f.store.reload()
            f.settings.update { it.copy(enabled = true) }
        }
        f.manager.sync(); runCurrent()
        assertNull(f.manager.state.value.errorMessage)
        assertEquals(listOf(root.resolve("sasayaki_match.json").readText()), uploadedMatches)
        val reference = f.store.state.books.getValue("book-a").files[SyncFileType.sasayaki]
        batches.close()
        coordinator.start(root, "audio"); runCurrent()
        repeat(2) { f.manager.sync(); runCurrent() }
        assertEquals(1, uploadedMatches.size)
        assertEquals(reference, f.store.state.books.getValue("book-a").files[SyncFileType.sasayaki])
    }

    @Test fun downloadAllAndTappedDownloadFetchEveryMissingEpub() = runTest {
        val f = fixture()
        val folders = listOf("book-a", "book-b", "book-c")
        val roots = folders.map { folder ->
            f.books.createBookDirectory(folder).also { root ->
                f.books.saveMetadata(root, BookMetadata(folder, folder, folder = folder, epub = "$folder.epub", lastAccess = 0.0))
                root.resolve("$folder.epub").writeText("$folder-content")
                f.store.handleBookImport(f.books.loadMetadata(root)!!, root)
            }
        }
        f.manager.sync()
        runCurrent()
        for (folder in folders) f.store.deleteLocalBook(folder)
        f.manager.sync()
        runCurrent()
        assertEquals(3, f.manager.state.value.missingBooks)
        val tapped = async { f.manager.downloadBook(f.books.loadMetadata(roots[1])!!) {} }
        f.manager.downloadAll()
        val reading = async { f.manager.sync(f.books.loadMetadata(roots[0])) }
        tapped.await()
        reading.await()
        runCurrent()
        for ((root, folder) in roots.zip(folders)) assertEquals("$folder-content", root.resolve("$folder.epub").readText())
        assertEquals(0, f.manager.state.value.missingBooks)
        assertFalse(f.manager.state.value.isSyncing)
        assertTrue(f.manager.state.value.queue.isEmpty())
    }

    @Test fun debouncesFromFirstEditAtBuildInterval() = runTest {
        val interval = if (BuildConfig.DEBUG) 2_000L else 30_000L
        val f = fixture()
        f.remote.addState("a", "book-a.json", SyncFormat.encode(remoteBook))
        f.manager.sync()
        runCurrent()
        val root = f.store.bookDirectory("book-a")
        f.books.saveMetadata(root, f.books.loadMetadata(root)!!.copy(renamedTitle = "First", modified = 2000))
        runCurrent()
        advanceTimeBy(interval / 2)
        f.books.saveMetadata(root, f.books.loadMetadata(root)!!.copy(renamedTitle = "Latest", modified = 3000))
        runCurrent()
        advanceTimeBy(interval / 2 - 1)
        runCurrent()
        assertTrue(f.remote.writes.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("a"), f.remote.writes)
        assertEquals("Latest", SyncFormat.decode<SyncBook>(f.remote.entries.getValue("a").data).metadata.value.title)
    }

    @Test fun pollsWhileActiveAndRunsOneFinalPassOnPause() = runTest {
        val interval = if (BuildConfig.DEBUG) 5_000L else 120_000L
        val f = fixture()
        f.manager.start()
        runCurrent()
        val first = f.remote.requests.count { it == "changes" }
        assertEquals(1, first)
        advanceTimeBy(interval - 1)
        runCurrent()
        assertEquals(first, f.remote.requests.count { it == "changes" })
        advanceTimeBy(1)
        runCurrent()
        assertEquals(first + 1, f.remote.requests.count { it == "changes" })
        f.manager.pause()
        runCurrent()
        assertEquals(first + 2, f.remote.requests.count { it == "changes" })
        advanceTimeBy(interval)
        runCurrent()
        assertEquals(first + 2, f.remote.requests.count { it == "changes" })
    }

    @Test fun listsAllPagesAndMergesEveryDuplicate() = runTest {
        val f = fixture()
        f.remote.listPageSize = 1
        f.remote.addState("z", "book-a.json", SyncFormat.encode(remoteBook.copy(characterCount = 200)))
        f.remote.addState("b-state", "book-a.json", SyncFormat.encode(remoteBook.copy(metadata = Timestamped(3000, SyncMetadata("Newest")))))
        f.remote.addState("a", "book-a.json", SyncFormat.encode(remoteBook))
        f.manager.sync()
        runCurrent()
        assertNull(f.manager.state.value.errorMessage)
        assertEquals(200, f.store.loadBook("book-a")!!.characterCount)
        assertEquals("Newest", f.store.loadBook("book-a")!!.metadata.value.title)
        assertEquals(listOf("b-state", "z"), f.remote.trashed)
    }

    @Test fun deletionCleansOldFilesButLeavesStateDocument() = runTest {
        val f = fixture()
        val root = f.books.createBookDirectory("book-a")
        f.books.saveMetadata(root, BookMetadata("ID", "Local", folder = "book-a", epub = "book-a.epub", lastAccess = 0.0))
        root.resolve("book-a.epub").writeText("epub-content")
        f.store.handleBookImport(f.books.loadMetadata(root)!!, root)
        f.manager.sync()
        runCurrent()
        f.manager.sync()
        runCurrent()
        val epubId = f.remote.entries.values.first { it.file.name == "book-a.epub" }.file.id
        val stateId = f.remote.entries.values.first { it.file.name == "book-a.json" }.file.id
        f.store.deleteBook("book-a")
        f.manager.sync()
        runCurrent()
        assertTrue(epubId in f.remote.trashed)
        assertTrue(stateId in f.remote.entries)
        assertTrue(SyncFormat.decode<SyncBook>(f.remote.entries.getValue(stateId).data).deleted)
        assertTrue(f.store.state.books.getValue("book-a").cleanup.isEmpty())
    }

    @Test fun generationReplacementStopsReaderBeforeTakingLocalSnapshot() = runTest {
        val f = fixture()
        f.remote.addState("a", "book-a.json", SyncFormat.encode(remoteBook))
        f.manager.sync()
        runCurrent()
        var stopped = false
        var workStopped = false
        val root = f.store.bookDirectory("book-a")
        val registration = f.books.workRegistry.register(root) {
            backgroundScope.async {
                f.books.storageLock.withLock {
                    assertTrue(root.exists())
                    workStopped = true
                }
            }.await()
        }
        f.manager.stopReader = { key ->
            assertTrue(workStopped)
            stopped = true
            f.books.statisticsStore.saveTrackedSession(f.store.bookDirectory(key), "ACTIVE", moe.antimony.hoshi.epub.ReadingSession(1, 2, 10, 1.0))
        }
        val entry = f.remote.entries.getValue("a")
        entry.file = entry.file.copy(md5Checksum = "2")
        entry.data = SyncFormat.encode(remoteBook.copy(generation = 2))
        f.manager.sync()
        runCurrent()
        registration.close()
        assertTrue(stopped)
        assertEquals(2, f.store.loadBook("book-a")!!.generation)
        assertEquals(10, f.store.loadBook("book-a")!!.sessions.getValue("ACTIVE").value!!.charactersRead)
    }

    @Test fun remoteDeletionJoinsBookWorkBeforeRemovingFiles() = runTest {
        val f = fixture()
        f.remote.addState("a", "book-a.json", SyncFormat.encode(remoteBook))
        f.manager.sync()
        runCurrent()
        val root = f.store.bookDirectory("book-a")
        var stopped = false
        val registration = f.books.workRegistry.register(root) {
            backgroundScope.async {
                f.books.storageLock.withLock {
                    assertTrue(root.exists())
                    stopped = true
                }
            }.await()
        }
        val entry = f.remote.entries.getValue("a")
        entry.file = entry.file.copy(md5Checksum = "2")
        entry.data = SyncFormat.encode(remoteBook.delete())
        f.manager.sync()
        runCurrent()
        registration.close()
        assertNull(f.manager.state.value.errorMessage)
        assertTrue(stopped)
        assertFalse(root.exists())
        assertTrue(f.store.state.books.getValue("book-a").deleted)
    }

    @Test fun shelfReloadDoesNotRecreateBookDeletedAfterDirectoryEnumeration() = runTest {
        assertDeletionDuringBookLoad(bookCount = 1, pauseAt = 3)
    }

    @Test fun batchDeletionDoesNotRecreateBooksFromPartiallyReadShelfSnapshot() = runTest {
        assertDeletionDuringBookLoad(bookCount = 3, pauseAt = 4)
    }

    @Test fun bookLookupDoesNotRecreateBookDeletedAfterDirectoryEnumeration() = runTest {
        assertDeletionDuringBookLoad(bookCount = 1, pauseAt = 3, lookup = true)
    }

    @Test fun migrationWaitingForDeletionLockRechecksDirectoryBeforeWriting() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gate = BookLoadGate(dispatcher, pauseAt = 4)
        val f = fixture(dispatcher = dispatcher, booksDispatcher = gate)
        f.remote.addState("a", "book-a.json", SyncFormat.encode(remoteBook))
        f.manager.sync()
        runCurrent()
        val root = f.store.bookDirectory("book-a")
        val loading = async(CoroutineName("paused-book-load")) { f.books.loadBookEntries() }
        runCurrent()
        assertTrue(gate.isPaused)
        try {
            f.store.transaction {
                // Resume migration while deletion owns the lock. An unlocked existence
                // check would see this directory, then block only on its metadata read.
                gate.release()
                runCurrent()
                assertFalse(loading.isCompleted)
                f.store.applyBook("book-a", remoteBook.delete())
            }
        } finally {
            if (gate.isPaused) gate.release()
        }

        assertTrue("Migration must discard the snapshot after waiting for deletion", loading.await().isEmpty())
        assertFalse("Migration must not write a replacement metadata file", root.exists())
    }

    private suspend fun TestScope.assertDeletionDuringBookLoad(bookCount: Int, pauseAt: Int, lookup: Boolean = false) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gate = BookLoadGate(dispatcher, pauseAt)
        val f = fixture(dispatcher = dispatcher, booksDispatcher = gate)
        val roots = (0 until bookCount).map { index ->
            val key = "book-$index"
            val root = f.books.createBookDirectory(key)
            f.books.saveMetadata(root, BookMetadata(
                java.util.UUID.randomUUID().toString(), "Book $index", folder = key,
                epub = "$key.epub", cover = "cover.jpg", lastAccess = 0.0,
                shelves = mapOf("Shelf" to Timestamped(100, true)),
            ))
            root.resolve("$key.epub").writeText("epub-content")
            root.resolve("cover.jpg").writeText("cover-content")
            f.store.handleBookImport(f.books.loadMetadata(root)!!, root)
            root
        }
        f.manager.sync()
        runCurrent()
        f.manager.sync()
        runCurrent()
        assertNull(f.manager.state.value.errorMessage)
        val bookId = f.books.loadMetadata(roots.first())!!.id
        roots.forEachIndexed { index, root -> assertTrue(root.setLastModified(1_000_000L - index * 1000)) }

        // Dispatches 1/2 read shelves and enumerate directories. Pause before either
        // the first metadata read (3), or a later read after a snapshot was retained (4).
        val loading = async(CoroutineName("paused-book-load")) {
            if (lookup) listOfNotNull(f.books.loadBookEntry(bookId)) else f.books.loadBookEntries()
        }
        runCurrent()
        assertTrue("The loader must pause after directory enumeration", gate.isPaused)
        try {
            for (entry in f.remote.entries.values.filter { it.file.name.startsWith("book-") && it.file.name.endsWith(".json") }) {
                entry.file = entry.file.copy(md5Checksum = "900")
                entry.data = SyncFormat.encode(SyncFormat.decode<SyncBook>(entry.data).delete())
            }
            f.manager.sync()
            runCurrent()
            assertTrue(roots.none { it.exists() })
            assertTrue(f.store.state.books.values.all { it.deleted })
        } finally {
            gate.release()
        }
        assertTrue("The in-flight load must omit deleted books", loading.await().isEmpty())
        assertTrue("Loading must not recreate deleted directories", roots.none { it.exists() })
        f.manager.sync()
        runCurrent()
        assertTrue(f.books.loadBookEntries().isEmpty())
    }

    @Test fun startupReconcilesLeftoverDirectoryEvenWhenRemoteDeletionVersionIsCached() = runTest {
        val f = fixture()
        f.remote.addState("a", "book-a.json", SyncFormat.encode(remoteBook.delete()))
        f.manager.sync()
        runCurrent()
        val root = f.store.bookDirectory("book-a")
        assertFalse(root.exists())
        assertFalse(f.store.state.books.getValue("book-a").pending)
        val cachedVersion = f.manager.cache.bookVersions["book-a"]
        // Reproduce the empty sidecar that older shelf loaders could write after deletion.
        f.books.saveMetadata(root, BookMetadata(java.util.UUID.randomUUID().toString(), folder = "book-a", lastAccess = 0.0))
        f.store.prepareLibrary()
        assertEquals(cachedVersion, f.manager.cache.bookVersions["book-a"])

        f.manager.sync()
        runCurrent()

        assertFalse("A normal sync must remove the leftover without opening the book", root.exists())
        assertTrue(f.store.state.books.getValue("book-a").deleted)
        assertFalse(f.store.state.books.getValue("book-a").pending)
        assertTrue(f.books.loadBookEntries().isEmpty())
    }

    private class BookLoadGate(private val delegate: CoroutineDispatcher, private val pauseAt: Int) : CoroutineDispatcher() {
        private var held: Pair<CoroutineContext, Runnable>? = null
        private var bookLoadDispatches = 0
        val isPaused get() = held != null

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (context[CoroutineName]?.name == "paused-book-load" && ++bookLoadDispatches == pauseAt) {
                held = context to block
            } else delegate.dispatch(context, block)
        }

        fun release() {
            val (context, block) = checkNotNull(held)
            held = null
            delegate.dispatch(context, block)
        }
    }

    @Test fun bookFailureContinuesRunAndKeepsCursor() = runTest {
        val f = fixture()
        f.remote.addState("a", "book-a.json", SyncFormat.encode(remoteBook))
        f.remote.addState("b", "book-b.json", SyncFormat.encode(remoteBook.copy(metadata = Timestamped(1000, SyncMetadata("Other")))))
        f.remote.failRead = { if (it == "a") HttpStatus(500) else null }
        f.manager.sync()
        runCurrent()
        assertNull(f.manager.state.value.errorMessage)
        assertFalse(f.manager.state.value.isSyncing)
        assertEquals("Other", f.store.loadBook("book-b")!!.metadata.value.title)
        assertNull(f.manager.cache.cursor)
        val item = f.manager.state.value.queue.single()
        assertEquals("book-a", item.key)
        assertNull(item.direction)
        assertNotNull(item.error)
        f.remote.failRead = { null }
        f.manager.sync()
        runCurrent()
        assertEquals("next", f.manager.cache.cursor)
        assertTrue(f.manager.state.value.queue.isEmpty())
    }

    @Test fun metadataTimeoutIsIsolatedToBookAndKeepsCursorForRetry() = runTest {
        val f = fixture()
        f.remote.addState("a", "book-a.json", SyncFormat.encode(remoteBook))
        f.remote.addState("b", "book-b.json", SyncFormat.encode(remoteBook))
        val failure = java.net.SocketTimeoutException("state timeout")
        f.remote.failRead = { if (it == "a") failure else null }
        f.manager.sync()
        runCurrent()
        assertNull(f.manager.state.value.errorMessage)
        assertEquals("Remote", f.store.loadBook("book-b")!!.metadata.value.title)
        assertNull(f.manager.cache.cursor)
        assertTrue(f.manager.state.value.queue.single().error.details().contains("state timeout"))
        assertSame(failure, ErrorDiagnosticEvents.buffer.drain().single().error!!.cause)
        f.remote.failRead = { null }
        f.manager.sync()
        runCurrent()
        assertEquals("next", f.manager.cache.cursor)
        assertTrue(f.manager.state.value.queue.isEmpty())
    }

    @Test fun unavailableDriveStopsRun() = runTest {
        val f = fixture()
        f.remote.addState("a", "book-a.json", SyncFormat.encode(remoteBook))
        f.remote.addState("b", "book-b.json", SyncFormat.encode(remoteBook))
        f.remote.failRead = { if (it == "a") GoogleDriveUnavailableException(IOException("offline")) else null }
        f.manager.sync()
        runCurrent()
        assertNotNull(f.manager.state.value.errorMessage)
        assertNull(f.store.loadBook("book-b"))
        assertEquals(0, f.remote.reads["b"] ?: 0)
        assertTrue(f.manager.state.value.queue.isEmpty())
        assertNull(f.manager.cache.cursor)
    }

    @Test fun rejectedTransferAuthorizationStopsTheWholeRun() = runTest {
        val rejection = DriveAuthorizationRequiredException()
        var uploads = 0
        val f = fixture(transferFactory = { client -> object : TestDriveFileTransfer(client) {
            override suspend fun upload(file: java.io.File, request: DriveUploadRequest, progress: (DriveTransferUpdate) -> Unit): GoogleDriveFile {
                uploads++
                throw rejection
            }
        } })
        f.settings.update { it.copy(transferConcurrency = 1) }
        repeat(2) { index ->
            val name = "book-$index"
            val root = f.books.createBookDirectory(name)
            f.books.saveMetadata(root, BookMetadata(name, name, folder = name, epub = "$name.epub", lastAccess = 0.0))
            root.resolve("$name.epub").writeText("sample-epub")
            f.store.handleBookImport(f.books.loadMetadata(root)!!, root)
        }

        f.manager.sync()
        runCurrent()

        assertEquals(1, uploads)
        assertTrue(f.manager.state.value.errorMessage.details().contains("Connect Google Drive"))
        assertTrue(f.manager.state.value.queue.none { it.error != null })
        assertTrue(f.store.state.books.values.all { it.files[SyncFileType.epub] == null })
    }

    @Test fun backgroundSyncReportsOfflineFailureAndRecoversOnNextAttempt() = runTest {
        val f = fixture()
        f.remote.addState("a", "book-a.json", SyncFormat.encode(remoteBook))
        f.remote.failRead = { IOException("offline") }
        val error = runCatching { f.manager.syncInBackground() }.exceptionOrNull()
        assertTrue(error is GoogleDriveSyncException)
        assertTrue(error?.cause is GoogleDriveUnavailableException)
        assertNull(f.manager.cache.cursor)
        assertEquals(ListenableWorker.Result.retry(), backgroundSyncResult(f.manager::syncInBackground))
        f.remote.failRead = { null }
        advanceTimeBy(30_000)
        assertEquals(ListenableWorker.Result.success(), backgroundSyncResult(f.manager::syncInBackground))
        assertEquals("Remote", f.store.loadBook("book-a")!!.metadata.value.title)
        assertEquals("next", f.manager.cache.cursor)
        assertNull(f.manager.state.value.errorMessage)
    }

    private suspend fun TestScope.uploadFixture(
        dispatcher: TestDispatcher = UnconfinedTestDispatcher(testScheduler),
        afterUpload: suspend () -> Unit,
    ): Fixture {
        lateinit var f: Fixture
        f = fixture(dispatcher) {
            if (f.remote.entries.values.any { it.file.name == "book-a.epub" }) afterUpload()
        }
        val root = f.books.createBookDirectory("book-a")
        f.books.saveMetadata(root, BookMetadata("ID", "Local", folder = "book-a", epub = "book-a.epub", lastAccess = 0.0))
        root.resolve("book-a.epub").writeText("epub-content")
        f.store.handleBookImport(f.books.loadMetadata(root)!!, root)
        return f
    }

    private fun Fixture.publishedEpub(): String? = SyncFormat.decode<SyncBook>(
        remote.entries.values.single { it.file.name == "book-a.json" }.data,
    ).files[SyncFileType.epub]?.value

    @Test fun backgroundSyncWaitsUntilUploadedFileReferenceIsPublished() = backgroundUploadWaits(foregroundFirst = false)

    @Test fun backgroundSyncJoinsPublicationAlreadyStartedInForeground() = backgroundUploadWaits(foregroundFirst = true)

    private fun backgroundUploadWaits(foregroundFirst: Boolean) = runTest {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val dispatcher = if (foregroundFirst) StandardTestDispatcher(testScheduler) else UnconfinedTestDispatcher(testScheduler)
        val f = uploadFixture(dispatcher) { reached.complete(Unit); release.await() }
        if (foregroundFirst) {
            f.manager.sync()
            runCurrent()
            assertTrue(reached.isCompleted)
        }
        val worker = async { backgroundSyncResult(f.manager::syncInBackground) }
        runCurrent()
        assertTrue(reached.isCompleted)
        assertNull(f.publishedEpub())
        assertTrue(f.store.state.books.getValue("book-a").pending)
        assertFalse("Uploading the EPUB alone must not finish the background job", worker.isCompleted)
        assertTrue(f.manager.state.value.isSyncing)

        release.complete(Unit)
        assertEquals(ListenableWorker.Result.success(), worker.await())
        assertEquals("book-a.epub", f.publishedEpub())
        assertFalse(f.store.state.books.getValue("book-a").pending)
        assertFalse(f.manager.state.value.isSyncing)
        assertTrue(ErrorDiagnosticEvents.buffer.drain().isEmpty())
    }

    @Test fun backgroundSyncRetriesPostUploadPublicationFailureAndRecovers() = runTest {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val original = IOException("Publication connection lost", IOException("Socket closed"))
        var failure: Exception? = original
        val f = uploadFixture {
            reached.complete(Unit)
            release.await()
            failure?.let { throw it }
        }
        val worker = async { backgroundSyncResult(f.manager::syncInBackground) }
        runCurrent()
        assertTrue(reached.isCompleted)
        release.complete(Unit)
        assertEquals(ListenableWorker.Result.retry(), worker.await())
        assertNull(f.publishedEpub())
        assertTrue(f.store.state.books.getValue("book-a").pending)
        assertFalse(f.manager.state.value.isSyncing)
        val message = f.manager.state.value.errorMessage.details()
        for (detail in listOf("Publication connection lost", "Socket closed")) assertTrue(message, message.contains(detail))
        assertSame(original, ErrorDiagnosticEvents.buffer.drain().single().error!!.cause)

        failure = null
        assertEquals(ListenableWorker.Result.success(), backgroundSyncResult(f.manager::syncInBackground))
        assertEquals("book-a.epub", f.publishedEpub())
        assertFalse(f.store.state.books.getValue("book-a").pending)
    }

    @Test fun cancellingBackgroundPublicationWaitStaysQuietAndAllowsForegroundCompletion() = runTest {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val f = uploadFixture { reached.complete(Unit); release.await() }
        val worker = async { backgroundSyncResult(f.manager::syncInBackground) }
        runCurrent()
        assertTrue(reached.isCompleted)
        worker.cancel()
        worker.join()
        assertTrue(worker.isCancelled)
        assertTrue(ErrorDiagnosticEvents.buffer.drain().isEmpty())
        release.complete(Unit)
        runCurrent()
        assertEquals("book-a.epub", f.publishedEpub())
        assertFalse(f.manager.state.value.isSyncing)
        assertNull(f.manager.state.value.errorMessage)
    }

    @Test fun backgroundSyncReportsPermanentPostUploadBookFailure() = runTest {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val f = uploadFixture { reached.complete(Unit); release.await() }
        val worker = async { backgroundSyncResult(f.manager::syncInBackground) }
        runCurrent()
        assertTrue(reached.isCompleted)
        val error = GoogleDriveApiException("Publication permission denied", 403).apply {
            initCause(IOException("Book state is read-only"))
        }
        f.remote.beforeWrite = { throw error }
        release.complete(Unit)
        assertEquals(ListenableWorker.Result.failure(), worker.await())
        assertNull(f.publishedEpub())
        assertTrue(f.store.state.books.getValue("book-a").pending)
        assertFalse(f.manager.state.value.isSyncing)
        val message = f.manager.state.value.queue.single().error.details()
        for (detail in listOf("Local", "403", "Publication permission denied", "Book state is read-only")) assertTrue(message, message.contains(detail))
        assertSame(error, ErrorDiagnosticEvents.buffer.drain().single().error)
    }

    @Test fun stoppingSyncCancelsPostUploadPublicationWithoutRestartingTransfers() = runTest {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val f = uploadFixture(StandardTestDispatcher(testScheduler)) { reached.complete(Unit); release.await() }
        val worker = async { backgroundSyncResult(f.manager::syncInBackground) }
        runCurrent()
        assertTrue(reached.isCompleted)
        f.manager.stop()
        worker.await()
        val requests = f.remote.requests.size
        release.complete(Unit)
        runCurrent()
        assertEquals(requests, f.remote.requests.size)
        assertNull(f.publishedEpub())
        assertTrue(f.store.state.books.getValue("book-a").pending)
        assertFalse(f.manager.state.value.isSyncing)
        assertNull(f.manager.state.value.errorMessage)
        assertTrue(ErrorDiagnosticEvents.buffer.drain().isEmpty())
    }

    @Test fun backgroundUploadPublishesAfterOverlappingForegroundStatePass() = runTest {
        val fileReached = CompletableDeferred<Unit>()
        val releaseFile = CompletableDeferred<Unit>()
        var pauseFile = true
        lateinit var f: Fixture
        f = fixture(StandardTestDispatcher(testScheduler)) {
            if (pauseFile && f.manager.state.value.progress != null) {
                pauseFile = false
                fileReached.complete(Unit)
                releaseFile.await()
            }
        }
        val root = f.books.createBookDirectory("book-a")
        f.books.saveMetadata(root, BookMetadata("ID", "Local", folder = "book-a", epub = "book-a.epub", lastAccess = 0.0))
        root.resolve("book-a.epub").writeText("epub-content")
        f.store.handleBookImport(f.books.loadMetadata(root)!!, root)
        val worker = async { backgroundSyncResult(f.manager::syncInBackground) }
        runCurrent()
        assertTrue(fileReached.isCompleted)

        val stateReached = CompletableDeferred<Unit>()
        val releaseState = CompletableDeferred<Unit>()
        f.remote.addState("b", "book-b.json", SyncFormat.encode(remoteBook))
        f.manager.flushReader = { key ->
            if (key == "book-b") { stateReached.complete(Unit); releaseState.await() }
        }
        val foreground = async { f.manager.sync() }
        runCurrent()
        assertTrue(stateReached.isCompleted)
        releaseFile.complete(Unit)
        runCurrent()
        assertTrue(f.remote.entries.values.any { it.file.name == "book-a.epub" })
        assertTrue(f.store.state.books.getValue("book-a").pending)
        assertNull(f.publishedEpub())

        releaseState.complete(Unit)
        foreground.await()
        assertEquals(ListenableWorker.Result.success(), worker.await())
        assertEquals("book-a.epub", f.publishedEpub())
        assertFalse(f.store.state.books.getValue("book-a").pending)
        assertFalse(f.manager.state.value.isSyncing)
    }

    @Test fun backgroundUploadPublishesAfterTargetedSyncReplacesPublicationPass() = runTest {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var pausePublication = true
        val f = uploadFixture(StandardTestDispatcher(testScheduler)) {
            if (pausePublication) {
                pausePublication = false
                reached.complete(Unit)
                release.await()
            }
        }
        val worker = async { backgroundSyncResult(f.manager::syncInBackground) }
        runCurrent()
        assertTrue(reached.isCompleted)
        val root = f.books.createBookDirectory("book-b")
        val otherBook = BookMetadata("OTHER", "Other", folder = "book-b", lastAccess = 0.0)
        f.books.saveMetadata(root, otherBook)
        f.store.handleBookImport(otherBook, root)
        f.manager.sync(otherBook)
        assertEquals(ListenableWorker.Result.success(), worker.await())
        assertEquals("book-a.epub", f.publishedEpub())
        assertFalse(f.store.state.books.getValue("book-a").pending)
        assertFalse(f.manager.state.value.isSyncing)
        assertTrue(ErrorDiagnosticEvents.buffer.drain().isEmpty())
    }

    @Test fun backgroundSyncReportsPerBookServerFailureUntilRetrySucceeds() = runTest {
        val f = fixture()
        f.remote.addState("a", "book-a.json", SyncFormat.encode(remoteBook))
        f.remote.failRead = { HttpStatus(500) }
        val error = runCatching { f.manager.syncInBackground() }.exceptionOrNull()
        assertTrue(error is GoogleDriveApiException)
        assertEquals(500, (error as GoogleDriveApiException).statusCode)
        assertEquals(ListenableWorker.Result.retry(), backgroundSyncResult(f.manager::syncInBackground))
        f.remote.failRead = { null }
        assertEquals(ListenableWorker.Result.success(), backgroundSyncResult(f.manager::syncInBackground))
        assertTrue(f.manager.state.value.queue.isEmpty())
        assertEquals("next", f.manager.cache.cursor)
    }

    @Test fun listedStateFilesSkipPerBookLookup() = runTest {
        val f = fixture()
        f.remote.reportChanges = false
        f.remote.addState("a", "book-a.json", SyncFormat.encode(remoteBook))
        f.manager.sync()
        runCurrent()
        assertNull(f.manager.state.value.errorMessage)
        assertEquals(1, f.remote.reads["a"])
        assertEquals("Remote", f.store.loadBook("book-a")!!.metadata.value.title)
        assertTrue(f.remote.queries.none { "name='book-a.json'" in it })
    }

    private data class Fixture(val remote: DriveService, val books: BookRepository, val store: SyncStorage, val manager: GoogleDriveSyncManager, val settings: SyncSettingsRepository, val client: GoogleDriveClient)

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(data.value).also { data.value = it }
    }

    private class DriveService {
        data class Entry(var file: GoogleDriveFile, var data: String = "")
        val entries = linkedMapOf<String, Entry>()
        val reads = mutableMapOf<String, Int>()
        val writes = mutableListOf<String>()
        val trashed = mutableListOf<String>()
        val requests = mutableListOf<String>()
        val queries = mutableListOf<String>()
        var listPageSize = Int.MAX_VALUE
        var reportChanges = true
        var failRead: (String) -> Exception? = { null }
        var failRequest: (() -> Exception)? = null
        var beforeWrite: (suspend () -> Unit)? = null

        init {
            folder("r", "Hoshi Reader", "root")
            folder("s", "state", "r")
            folder("b", "books", "r")
        }

        fun folder(id: String, name: String, parent: String) {
            entries[id] = Entry(GoogleDriveFile(id, name, "application/vnd.google-apps.folder", "1", parents = listOf(parent), createdTime = "2020-01-01T00:00:00Z"))
        }

        fun addState(id: String, name: String, data: String) {
            entries[id] = Entry(GoogleDriveFile(id, name, "application/json", "1", parents = listOf("s"), createdTime = "2020-01-01T00:00:00Z"), data)
        }

        fun connection(address: String): HttpURLConnection = object : HttpURLConnection(URL(address)) {
            private val output = ByteArrayOutputStream()
            private var response: ByteArray? = null
            private var status = 200
            override fun setRequestMethod(value: String) { method = value }
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getOutputStream() = output
            override fun getResponseCode(): Int {
                if (response == null) response = try {
                    respond(url, method, output.toString(Charsets.UTF_8.name())).toByteArray()
                } catch (error: HttpStatus) {
                    status = error.status
                    byteArrayOf()
                }
                return status
            }
            override fun getInputStream() = response!!.inputStream()
            override fun getErrorStream() = response!!.inputStream()
        }

        private fun respond(url: URL, method: String, body: String): String {
            val path = url.path.substringAfter("drive/v3/")
            val query = url.query.orEmpty().split('&').filter { it.contains('=') }.associate {
                it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            requests += path
            failRequest?.let { throw it() }
            if (path == "changes/startPageToken") return "{\"startPageToken\":\"start\"}"
            if (path == "changes") return buildJsonObject {
                put("newStartPageToken", "next")
                put("changes", JsonArray(entries.values.filter { reportChanges && it.file.parents == listOf("s") }.map {
                    buildJsonObject { put("removed", false); put("file", SyncFormat.json.parseToJsonElement(SyncFormat.json.encodeToString(it.file))) }
                }))
            }.toString()
            if (method == "GET" && path == "files") {
                val q = query.getValue("q")
                queries += q
                val parent = Regex("'([^']+)' in parents").find(q)?.groupValues?.get(1)
                val name = Regex("name='([^']*)'").find(q)?.groupValues?.get(1)
                val matching = entries.values.filter { (parent == null || parent in it.file.parents.orEmpty()) && (name == null || name == it.file.name) }
                val offset = query["pageToken"]?.toInt() ?: 0
                return buildJsonObject {
                    put("files", JsonArray(matching.drop(offset).take(listPageSize).map {
                        SyncFormat.json.parseToJsonElement(SyncFormat.json.encodeToString(it.file))
                    }))
                    if (matching.size - offset > listPageSize) put("nextPageToken", (offset + listPageSize).toString())
                }.toString()
            }
            if (method == "GET") {
                val id = path.substringAfter("files/")
                requests += "read/$id"
                failRead(id)?.let { throw it }
                reads[id] = (reads[id] ?: 0) + 1
                return entries.getValue(id).data
            }
            if (query["uploadType"] == "multipart") {
                beforeWrite?.let { action -> beforeWrite = null; kotlinx.coroutines.runBlocking { action() } }
                val metadata = SyncFormat.json.parseToJsonElement(body.substringAfter("\r\n\r\n").substringBefore("\r\n--")).jsonObject
                val name = metadata.getValue("name").jsonPrimitive.content
                val id = if (method == "PATCH") path.substringAfter("files/") else "new-${entries.size}"
                val parent = metadata["parents"]?.jsonArray?.first()?.jsonPrimitive?.content ?: entries.getValue(id).file.parents!!.first()
                val data = body.substringAfter("\r\n--", "").substringAfter("\r\n\r\n").substringBeforeLast("\r\n--")
                val file = GoogleDriveFile(id, name, "application/octet-stream", ((entries[id]?.file?.md5Checksum?.toInt() ?: 0) + 1).toString(), size = data.toByteArray().size.toString(), parents = listOf(parent), createdTime = "2020-01-01T00:00:00Z")
                entries[id] = Entry(file, data)
                writes += id
                return SyncFormat.json.encodeToString(file)
            }
            val metadata = SyncFormat.json.parseToJsonElement(body).jsonObject
            if (metadata["trashed"] == JsonPrimitive(true)) {
                val id = path.substringAfter("files/")
                requests += "trash/$id"
                entries.remove(id)
                trashed += id
                return "{}"
            }
            val id = "new-${entries.size}"
            folder(id, metadata.getValue("name").jsonPrimitive.content, metadata.getValue("parents").jsonArray.first().jsonPrimitive.content)
            return SyncFormat.json.encodeToString(entries.getValue(id).file)
        }
    }

    private class HttpStatus(val status: Int) : Exception()

    private object NoTtu : DriveSyncDataSource {
        override suspend fun findRootFolder() = error("TTU")
        override suspend fun ensureBookFolder(bookTitle: String, rootFolderId: String, coverImageDataProvider: (suspend () -> ByteArray?)?) = error("TTU")
        override suspend fun listSyncFiles(folderId: String) = error("TTU")
        override suspend fun getProgressFile(fileId: String) = error("TTU")
        override suspend fun getStatsFile(fileId: String) = error("TTU")
        override suspend fun getAudioBookFile(fileId: String) = error("TTU")
        override suspend fun updateProgressFile(folderId: String, fileId: String?, progress: TtuProgress) = error("TTU")
        override suspend fun updateStatsFile(folderId: String, fileId: String?, stats: List<ReadingStatistics>) = error("TTU")
        override suspend fun updateAudioBookFile(folderId: String, fileId: String?, audioBook: TtuAudioBook) = error("TTU")
        override fun clearCache() = Unit
    }
}
