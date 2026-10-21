package moe.antimony.hoshi.features.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.ReadingStatistics
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in loopback fixture. Never uses application stores, settings, accounts or tokens. */
@RunWith(AndroidJUnit4::class)
class DriveMetadataIsolationDeviceTest {
    @Test fun metadataTimeoutLeavesEightUploadsRunningAndLaterPublishesReferences() = runBlocking {
        val endpoint = InstrumentationRegistry.getArguments().getString("driveIsolationUrl").orEmpty()
        assumeTrue("Requires isolated loopback endpoint", endpoint.startsWith("http://127.0.0.1:"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "metadata-isolation-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val auth = object : DriveAccessTokenProvider { override suspend fun accessToken() = "fixture-token" }
        val client = GoogleDriveClient(auth, { SyncProvider.Gdrive }, {}, Dispatchers.IO) { address ->
            (URL(endpoint + URL(address).file).openConnection() as HttpURLConnection)
        }
        val transport = GoogleDriveFileTransfer(auth, DriveUploadSessions(File(root, "sessions")), Dispatchers.IO,
            OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS).build(), endpoint = "$endpoint/upload", checkInternet = {})
        suspend fun control(path: String, method: String = "GET"): JsonObject = withContext(Dispatchers.IO) {
            val connection = URL(endpoint + path).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                SyncFormat.json.parseToJsonElement(connection.inputStream.use { it.readBytes().decodeToString() }).jsonObject
            } finally { connection.disconnect() }
        }
        val books = BookRepository(root, Dispatchers.IO)
        val store = SyncStorage(root, books, Dispatchers.IO)
        val settings = SyncSettingsRepository(MemoryPreferences(), NoTtu, Dispatchers.IO)
        settings.update { it.copy(enabled = true, transferConcurrency = 8) }
        val manager = GoogleDriveSyncManager(store, GoogleDriveSyncHandler(client), client, settings, books, root,
            File(root, "cache").apply { mkdirs() }, scope, Dispatchers.Main.immediate, Dispatchers.IO, { true }, {}, {}, transport)
        try {
            withContext(Dispatchers.IO) {
                repeat(8) { index ->
                    val name = "fixture-$index"
                    val folder = books.createBookDirectory(name)
                    val book = BookMetadata(name, name, folder = name, epub = "$name.epub", lastAccess = 0.0)
                    books.saveMetadata(folder, book)
                    folder.resolve("$name.epub").outputStream().use { output ->
                        val block = ByteArray(1024 * 1024) { (it % 251).toByte() }
                        repeat(if (index == 0) 60 else 24) { output.write(block) }
                    }
                    store.handleBookImport(book, folder)
                }
            }
            manager.sync()
            withTimeout(30_000) { while (manager.state.value.activeTransfers.size != 8) delay(100) }
            val before = withTimeout(30_000) {
                var received: Map<String, Long>
                do {
                    delay(100)
                    received = control("/stats").getValue("received").jsonObject.mapValues { it.value.jsonPrimitive.long }
                } while (received.size != 8 || received.values.any { it == 0L })
                received
            }
            control("/fault", "POST")
            val started = System.nanoTime()
            withTimeout(130_000) { manager.sync() }
            val elapsed = (System.nanoTime() - started) / 1_000_000_000.0
            assertTrue("Must exercise the real 60s socket timeout: $elapsed", elapsed >= 60)
            assertNotNull(manager.state.value.errorMessage)
            assertEquals("Metadata retry exhaustion cancelled uploads", 8, manager.state.value.activeTransfers.size)
            val after = control("/stats")
            assertTrue(after.getValue("failures").jsonPrimitive.int >= 5)
            val received = after.getValue("received").jsonObject
            assertTrue("Every payload must keep moving", before.all { (key, bytes) -> received.getValue(key).jsonPrimitive.long > bytes })
            control("/release", "POST")
            withTimeout(120_000) { while (manager.state.value.isSyncing) delay(100) }
            assertNull(manager.state.value.errorMessage)
            assertTrue(manager.state.value.queue.isEmpty())
            assertEquals(8, store.state.books.values.count { !it.pending && it.files[SyncFileType.epub]?.value != null })
            val stats = control("/stats")
            assertEquals(8, stats.getValue("completed").jsonPrimitive.int)
            assertEquals(8, stats.getValue("published").jsonPrimitive.int)
            assertEquals(List(7) { 24L * 1024 * 1024 } + 60L * 1024 * 1024,
                stats.getValue("received").jsonObject.values.map { it.jsonPrimitive.long }.sorted())
            val result = "metadataSeconds=$elapsed failures=${after["failures"]} activeAfterFailure=8 completed=${stats["completed"]} publishedReferences=8"
            android.util.Log.i("HoshiMetadataIsolation", result)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("metadataIsolation", result) })
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            client.stop()
            root.deleteRecursively()
        }
    }

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(data.value).also { data.value = it }
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
