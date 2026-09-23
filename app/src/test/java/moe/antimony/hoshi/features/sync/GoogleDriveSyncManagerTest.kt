package moe.antimony.hoshi.features.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
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

    private suspend fun TestScope.fixture(): Fixture {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val remote = DriveService()
        val books = BookRepository(temporary.root, dispatcher)
        val store = SyncStorage(temporary.root, books, dispatcher)
        val settings = SyncSettingsRepository(MemoryPreferences(), NoTtu, dispatcher)
        settings.update { it.copy(enabled = true) }
        val token = object : DriveAccessTokenProvider { override suspend fun accessToken() = "token" }
        val client = GoogleDriveClient(token, { SyncProvider.Gdrive }, {}, dispatcher, remote::connection)
        val handler = GoogleDriveSyncHandler(client)
        val manager = GoogleDriveSyncManager(store, handler, client, settings, books, temporary.root,
            temporary.root.resolve("cache").apply { mkdirs() }, CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job])), dispatcher, dispatcher, { true }, {}, {})
        return Fixture(remote, books, store, manager)
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
        assertEquals(1, f.remote.reads["a"])
        f.manager.stop()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SyncSettingsRepository(MemoryPreferences(), NoTtu, dispatcher)
        settings.update { it.copy(enabled = true) }
        val client = GoogleDriveClient(object : DriveAccessTokenProvider { override suspend fun accessToken() = "token" }, { SyncProvider.Gdrive }, {}, dispatcher, f.remote::connection)
        val restarted = GoogleDriveSyncManager(f.store, GoogleDriveSyncHandler(client), client, settings, f.books, temporary.root, temporary.root.resolve("cache"), CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job])), dispatcher, dispatcher, { true }, {}, {})
        restarted.sync(metadata)
        assertEquals(1, f.remote.reads["a"])
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
        assertTrue(f.store.state.books.getValue("book-a").pending)
        f.manager.sync()
        f.store.deleteLocalBook("book-a")
        f.manager.sync()
        runCurrent()
        assertFalse(root.resolve("book-a.epub").exists())
        f.manager.downloadBook(f.books.loadMetadata(root)!!) {}
        assertEquals("epub-content", root.resolve("book-a.epub").readText())
        assertEquals(reference.modified, f.store.state.books.getValue("book-a").sources[SyncFileType.epub])
    }

    @Test fun debouncesFromFirstEditForThirtySeconds() = runTest {
        val f = fixture()
        f.remote.addState("a", "book-a.json", SyncFormat.encode(remoteBook))
        f.manager.sync()
        runCurrent()
        val root = f.store.bookDirectory("book-a")
        f.books.saveMetadata(root, f.books.loadMetadata(root)!!.copy(renamedTitle = "First", modified = 2000))
        runCurrent()
        advanceTimeBy(20_000)
        f.books.saveMetadata(root, f.books.loadMetadata(root)!!.copy(renamedTitle = "Latest", modified = 3000))
        runCurrent()
        advanceTimeBy(9_999)
        runCurrent()
        assertTrue(f.remote.writes.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("a"), f.remote.writes)
        assertEquals("Latest", SyncFormat.decode<SyncBook>(f.remote.entries.getValue("a").data).metadata.value.title)
    }

    @Test fun pollsWhileActiveAndRunsOneFinalPassOnPause() = runTest {
        val f = fixture()
        f.manager.start()
        runCurrent()
        val first = f.remote.requests.count { it == "changes" }
        assertEquals(1, first)
        advanceTimeBy(119_999)
        runCurrent()
        assertEquals(first, f.remote.requests.count { it == "changes" })
        advanceTimeBy(1)
        runCurrent()
        assertEquals(first + 1, f.remote.requests.count { it == "changes" })
        f.manager.pause()
        runCurrent()
        assertEquals(first + 2, f.remote.requests.count { it == "changes" })
        advanceTimeBy(120_000)
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
        f.manager.stopReader = { key ->
            stopped = true
            f.books.statisticsStore.saveTrackedSession(f.store.bookDirectory(key), "ACTIVE", moe.antimony.hoshi.epub.ReadingSession(1, 2, 10, 1.0))
        }
        val entry = f.remote.entries.getValue("a")
        entry.file = entry.file.copy(version = "2")
        entry.data = SyncFormat.encode(remoteBook.copy(generation = 2))
        f.manager.sync()
        runCurrent()
        assertTrue(stopped)
        assertEquals(2, f.store.loadBook("book-a")!!.generation)
        assertEquals(10, f.store.loadBook("book-a")!!.sessions.getValue("ACTIVE").value!!.charactersRead)
    }

    private data class Fixture(val remote: DriveService, val books: BookRepository, val store: SyncStorage, val manager: GoogleDriveSyncManager)

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
        var listPageSize = Int.MAX_VALUE
        var beforeWrite: (suspend () -> Unit)? = null

        init {
            folder("r", "Hoshi Reader", "root")
            folder("s", "state", "r")
            folder("b", "books", "r")
        }

        private fun folder(id: String, name: String, parent: String) {
            entries[id] = Entry(GoogleDriveFile(id, name, "application/vnd.google-apps.folder", "1", parents = listOf(parent), createdTime = "2020-01-01T00:00:00Z"))
        }

        fun addState(id: String, name: String, data: String) {
            entries[id] = Entry(GoogleDriveFile(id, name, "application/json", "1", parents = listOf("s"), createdTime = "2020-01-01T00:00:00Z"), data)
        }

        fun connection(address: String): HttpURLConnection = object : HttpURLConnection(URL(address)) {
            private val output = ByteArrayOutputStream()
            private var response: ByteArray? = null
            override fun setRequestMethod(value: String) { method = value }
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getOutputStream() = output
            override fun getResponseCode(): Int {
                if (response == null) response = respond(url, method, output.toString(Charsets.UTF_8.name())).toByteArray()
                return 200
            }
            override fun getInputStream() = response!!.inputStream()
        }

        private fun respond(url: URL, method: String, body: String): String {
            val path = url.path.substringAfter("drive/v3/")
            val query = url.query.orEmpty().split('&').filter { it.contains('=') }.associate {
                it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            requests += path
            if (path == "changes/startPageToken") return "{\"startPageToken\":\"start\"}"
            if (path == "changes") return buildJsonObject {
                put("newStartPageToken", "next")
                put("changes", JsonArray(entries.values.filter { it.file.parents == listOf("s") }.map {
                    buildJsonObject { put("removed", false); put("file", SyncFormat.json.parseToJsonElement(SyncFormat.json.encodeToString(it.file))) }
                }))
            }.toString()
            if (method == "GET" && path == "files") {
                val q = query.getValue("q")
                val parent = Regex("'([^']+)' in parents").find(q)!!.groupValues[1]
                val name = Regex("name='([^']*)'").find(q)?.groupValues?.get(1)
                val matching = entries.values.filter { parent in it.file.parents.orEmpty() && (name == null || name == it.file.name) }
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
                val file = GoogleDriveFile(id, name, "application/octet-stream", ((entries[id]?.file?.version?.toInt() ?: 0) + 1).toString(), size = data.toByteArray().size.toString(), parents = listOf(parent), createdTime = "2020-01-01T00:00:00Z")
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
