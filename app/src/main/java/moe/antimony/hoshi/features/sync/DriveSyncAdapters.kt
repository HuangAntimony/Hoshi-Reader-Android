package moe.antimony.hoshi.features.sync

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookInfo
import moe.antimony.hoshi.epub.Bookmark
import java.io.File

@OptIn(ExperimentalSerializationApi::class)
val adapterJson: Json = Json {
    prettyPrint = true
    prettyPrintIndent = "    "
    encodeDefaults = true
    ignoreUnknownKeys = true
}

class DriveFolderFileCache(private val file: File) : DriveFolderCache {
    private var state: CacheState = loadState()
    private val folderMap = state.titleToFolderId.toMutableMap()

    override var rootFolderId: String?
        get() = state.rootFolderId
        set(value) {
            state = state.copy(rootFolderId = value)
            save()
        }

    override var titleToFolderId: MutableMap<String, String>
        get() = folderMap
        set(value) {
            folderMap.clear()
            folderMap.putAll(value)
            state = state.copy(titleToFolderId = value.toMap())
            save()
        }

    fun save() {
        state = state.copy(titleToFolderId = folderMap.toMap())
        file.parentFile?.mkdirs()
        file.writeText(adapterJson.encodeToString(state))
    }

    override fun clear() {
        state = CacheState()
        folderMap.clear()
        if (file.exists()) file.delete()
    }

    private fun loadState(): CacheState {
        if (!file.isFile) return CacheState()
        return runCatching { adapterJson.decodeFromString<CacheState>(file.readText()) }
            .getOrDefault(CacheState())
    }

    @Serializable
    private data class CacheState(
        val rootFolderId: String? = null,
        val titleToFolderId: Map<String, String> = emptyMap(),
    )
}

class BookStorageSyncAdapter(
    filesDir: File,
) : SyncBookStorage {
    private val booksDir = File(filesDir, "Books")
    override val rootDir: File = booksDir

    override fun loadBookmark(folder: String): Bookmark? {
        val file = bookRoot(folder).resolve("bookmark.json")
        if (!file.isFile) return null
        return runCatching { adapterJson.decodeFromString<Bookmark>(file.readText()) }.getOrNull()
    }

    override fun saveBookmark(folder: String, bookmark: Bookmark) {
        val root = bookRoot(folder)
        root.mkdirs()
        root.resolve("bookmark.json").writeText(adapterJson.encodeToString(bookmark))
    }

    override fun loadBookInfo(folder: String): BookInfo? {
        val file = bookRoot(folder).resolve("bookinfo.json")
        if (!file.isFile) return null
        return runCatching { adapterJson.decodeFromString<BookInfo>(file.readText()) }.getOrNull()
    }

    private fun bookRoot(folder: String): File {
        val root = booksDir.resolve(folder).canonicalFile
        val canonicalBooks = booksDir.canonicalFile
        require(root.path == canonicalBooks.path || root.path.startsWith(canonicalBooks.path + File.separator)) {
            "Unsafe book folder: $folder"
        }
        return root
    }
}
