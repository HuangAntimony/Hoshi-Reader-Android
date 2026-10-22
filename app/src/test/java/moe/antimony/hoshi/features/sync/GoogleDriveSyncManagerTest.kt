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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
        beforeRequest: suspend () -> Unit = {},
    ): Fixture {
        val remote = DriveService()
        val books = BookRepository(temporary.root, dispatcher)
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
            temporary.root.resolve("cache").apply { mkdirs() }, CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job])), dispatcher, dispatcher, { true }, {}, {}, transferFactory(client))
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

    @Test fun backgroundSyncReportsOfflineFailureAndRecoversOnNextAttempt() = runTest {
        val f = fixture()
        f.remote.addState("a", "book-a.json", SyncFormat.encode(remoteBook))
        f.remote.failRead = { IOException("offline") }
        val error = runCatching { f.manager.syncInBackground() }.exceptionOrNull()
        assertTrue(error is GoogleDriveUnavailableException)
        assertNull(f.manager.cache.cursor)
        assertEquals(ListenableWorker.Result.retry(), backgroundSyncResult(f.manager::syncInBackground))
        f.remote.failRead = { null }
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
