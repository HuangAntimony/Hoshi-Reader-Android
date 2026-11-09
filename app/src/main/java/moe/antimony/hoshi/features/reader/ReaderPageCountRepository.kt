package moe.antimony.hoshi.features.reader

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile
import androidx.compose.ui.unit.IntSize
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.di.CacheDir
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.content.ContentLanguageProfile

/** Rebuildable device-local layout data, deliberately outside book sidecars. */
@Singleton
internal class ReaderPageCountRepository @Inject constructor(
    @CacheDir private val cacheDir: File,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(bookId: String, key: String, book: EpubBook): ReaderPageIndex? = withContext(ioDispatcher) {
        val file = cacheFile(bookId)
        if (!file.isFile) return@withContext null
        val cache = runCatching { json.decodeFromString<PageCache>(file.readText()) }.getOrNull()
            ?.takeIf { it.key == key } ?: return@withContext null
        ReaderPageIndex.create(book, cache.starts)
    }

    suspend fun save(bookId: String, key: String, index: ReaderPageIndex) = withContext(ioDispatcher) {
        val file = cacheFile(bookId)
        file.parentFile!!.mkdirs()
        val temporary = File.createTempFile(".pages-", ".tmp", file.parentFile)
        try {
            temporary.writeText(json.encodeToString(PageCache(key, index.starts)))
            coroutineContext.ensureActive()
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    suspend fun contentFingerprint(book: EpubBook, packedEpub: File? = null): String = withContext(ioDispatcher) {
        val digest = MessageDigest.getInstance("SHA-256")
        book.chapters.forEach { chapter ->
            digest.add(chapter.href)
            digest.add(book.bookInfo.chapterInfo[chapter.href].toString())
            if (packedEpub == null) digest.add(chapter.html)
        }
        if (packedEpub != null) {
            ZipFile(packedEpub).use { archive ->
                archive.entries().asSequence().sortedBy { it.name }.forEach { entry ->
                    coroutineContext.ensureActive()
                    digest.add("${entry.name}:${entry.size}:${entry.crc}")
                }
            }
        } else {
            book.resources.keys.sorted().forEach { href ->
                coroutineContext.ensureActive()
                digest.add(href)
                book.readResource(href)?.let(digest::update)
            }
        }
        digest.hex()
    }

    suspend fun fontFingerprint(files: List<File>): String = withContext(ioDispatcher) {
        val digest = MessageDigest.getInstance("SHA-256")
        for (file in files.sortedBy { it.path }) {
            digest.add(file.name)
            file.inputStream().use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
        }
        digest.hex()
    }

    private fun cacheFile(bookId: String) = File(cacheDir, "reader-pages/${pageCountDigest(bookId)}.json")

    @Serializable private data class PageCache(val key: String, val starts: List<List<Int>>)
}

internal fun readerPageCountKey(
    content: String,
    geometry: ReaderContentReloadKey,
    cssSize: IntSize,
    density: Float,
    font: ReaderFontRenderSpec,
    fontFingerprint: String,
    contentLanguage: ContentLanguageProfile,
    renderer: String?,
    systemVersion: String,
): String = pageCountDigest(listOf("reader-pages-v4", content, geometry, cssSize, density,
    font.copy(revision = 0), fontFingerprint, contentLanguage.id, renderer, systemVersion).joinToString("\n"))

internal fun pageCountDigest(value: String): String = MessageDigest.getInstance("SHA-256").apply { add(value) }.hex()
private fun MessageDigest.add(value: String) { update(value.toByteArray()); update(0.toByte()) }
private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }
