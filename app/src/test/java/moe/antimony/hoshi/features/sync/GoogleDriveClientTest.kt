package moe.antimony.hoshi.features.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleDriveClientTest {
    @Test
    fun findOrCreateRootFolderReusesExistingTtuReaderDataFolder() {
        val transport = FakeDriveTransport(
            listResponses = ArrayDeque(
                listOf(
                    listOf(
                        DriveFile(id = "root-id", name = "ttu-reader-data"),
                    ),
                ),
            ),
        )
        val cache = InMemoryDriveCache()
        val client = GoogleDriveClient(transport = transport, cache = cache)

        assertEquals("root-id", client.findOrCreateRootFolder("token"))
        assertEquals("root-id", cache.rootFolderId)
        assertEquals(1, transport.requests.size)
        assertTrue(transport.requests.single().url.contains("name%20%3D%20%27ttu-reader-data%27"))
    }

    @Test
    fun findOrCreateRootFolderCreatesFolderWhenMissing() {
        val transport = FakeDriveTransport(
            listResponses = ArrayDeque(listOf(emptyList())),
            createResponses = ArrayDeque(listOf(DriveFile(id = "created-root", name = "ttu-reader-data"))),
        )
        val client = GoogleDriveClient(transport = transport, cache = InMemoryDriveCache())

        assertEquals("created-root", client.findOrCreateRootFolder("token"))

        val createRequest = transport.requests.last()
        assertEquals("POST", createRequest.method)
        assertTrue(createRequest.body.decodeToString().contains("\"mimeType\":\"application/vnd.google-apps.folder\""))
    }

    @Test
    fun ensureBookFolderUsesCacheBeforeNetwork() {
        val cache = InMemoryDriveCache(titleToFolderId = mutableMapOf("Book" to "cached-book"))
        val client = GoogleDriveClient(transport = FakeDriveTransport(), cache = cache)

        assertEquals("cached-book", client.ensureBookFolder("token", "root", "Book"))
    }

    @Test
    fun ensureBookFolderSearchesAndCreatesSanitizedBookFolder() {
        val transport = FakeDriveTransport(
            listResponses = ArrayDeque(listOf(emptyList())),
            createResponses = ArrayDeque(listOf(DriveFile(id = "book-id", name = "Book%2FOne"))),
        )
        val cache = InMemoryDriveCache()
        val client = GoogleDriveClient(transport = transport, cache = cache)

        assertEquals("book-id", client.ensureBookFolder("token", "root", "Book/One"))
        assertEquals("book-id", cache.titleToFolderId["Book%2FOne"])
    }

    @Test
    fun ensureBookFolderPersistsCreatedFolderThroughCacheSetter() {
        val transport = FakeDriveTransport(
            listResponses = ArrayDeque(listOf(emptyList())),
            createResponses = ArrayDeque(listOf(DriveFile(id = "book-id", name = "Book"))),
        )
        val cache = CopyingDriveCache()
        val client = GoogleDriveClient(transport = transport, cache = cache)

        assertEquals("book-id", client.ensureBookFolder("token", "root", "Book"))

        assertEquals(mapOf("Book" to "book-id"), cache.persistedTitleToFolderId)
    }

    @Test
    fun uploadProgressCreatesMultipartFileWhenFileIdMissing() {
        val transport = FakeDriveTransport(uploadResponses = ArrayDeque(listOf(DriveFile(id = "progress-id", name = "progress"))))
        val client = GoogleDriveClient(transport = transport, cache = InMemoryDriveCache())

        client.uploadProgress(
            accessToken = "token",
            folderId = "book-folder",
            fileId = null,
            progress = TtuProgress(
                dataId = 0,
                exploredCharCount = 42,
                progress = 0.5,
                lastBookmarkModified = 1_762_000_123_456L,
            ),
        )

        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertTrue(request.url.contains("/upload/drive/v3/files?uploadType=multipart"))
        val body = request.body.decodeToString()
        assertTrue(body.contains("\"name\":\"progress_1_6_1762000123456_0.5.json\""))
        assertTrue(body.contains("\"parents\":[\"book-folder\"]"))
        assertTrue(body.contains("\"exploredCharCount\":42"))
    }

    @Test
    fun staleDriveRequestClearsCacheAndRetriesOnce() {
        val transport = FakeDriveTransport(
            listResponses = ArrayDeque(listOf(emptyList())),
            createResponses = ArrayDeque(listOf(DriveFile(id = "fresh-book", name = "Book"))),
            failFirstRequestWith404 = true,
        )
        val cache = InMemoryDriveCache(titleToFolderId = mutableMapOf("Other" to "stale-book"))
        val client = GoogleDriveClient(transport = transport, cache = cache)

        assertEquals("fresh-book", client.ensureBookFolder("token", "root", "Book"))
        assertEquals("fresh-book", cache.titleToFolderId["Book"])
        assertTrue(cache.didClear)
    }
}

private class FakeDriveTransport(
    val listResponses: ArrayDeque<List<DriveFile>> = ArrayDeque(),
    val createResponses: ArrayDeque<DriveFile> = ArrayDeque(),
    val uploadResponses: ArrayDeque<DriveFile> = ArrayDeque(),
    val failFirstRequestWith404: Boolean = false,
) : DriveHttpTransport {
    val requests = mutableListOf<DriveHttpRequest>()
    private var failed = false

    override fun execute(request: DriveHttpRequest): DriveHttpResponse {
        requests += request
        if (failFirstRequestWith404 && !failed) {
            failed = true
            return DriveHttpResponse(statusCode = 404, body = """{"error":{"message":"not found"}}""".encodeToByteArray())
        }
        return when {
            request.url.contains("/upload/drive/v3/files") -> DriveHttpResponse(200, encodeFile(uploadResponses.removeFirst()))
            request.method == "POST" -> DriveHttpResponse(200, encodeFile(createResponses.removeFirst()))
            else -> DriveHttpResponse(200, encodeList(listResponses.removeFirst()))
        }
    }

    private fun encodeList(files: List<DriveFile>): ByteArray =
        """{"files":[${files.joinToString(",") { """{"id":"${it.id}","name":"${it.name}"}""" }}]}""".encodeToByteArray()

    private fun encodeFile(file: DriveFile): ByteArray =
        """{"id":"${file.id}","name":"${file.name}"}""".encodeToByteArray()
}

private class InMemoryDriveCache(
    override var rootFolderId: String? = null,
    override var titleToFolderId: MutableMap<String, String> = mutableMapOf(),
) : DriveFolderCache {
    var didClear = false

    override fun clear() {
        didClear = true
        rootFolderId = null
        titleToFolderId.clear()
    }
}

private class CopyingDriveCache : DriveFolderCache {
    override var rootFolderId: String? = null
    var persistedTitleToFolderId: Map<String, String> = emptyMap()

    override var titleToFolderId: MutableMap<String, String>
        get() = persistedTitleToFolderId.toMutableMap()
        set(value) {
            persistedTitleToFolderId = value.toMap()
        }

    override fun clear() {
        rootFolderId = null
        persistedTitleToFolderId = emptyMap()
    }
}
