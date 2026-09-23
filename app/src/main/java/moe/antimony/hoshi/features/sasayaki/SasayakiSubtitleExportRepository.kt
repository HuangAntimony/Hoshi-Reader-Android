package moe.antimony.hoshi.features.sasayaki

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.di.CacheDir
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.epub.SasayakiMatchData

internal class SasayakiSubtitleExportRepository @Inject constructor(
    private val contentResolver: ContentResolver,
    @param:CacheDir private val cacheDir: File,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun prepare(title: String, match: SasayakiMatchData): File = withContext(ioDispatcher) {
        val text = SasayakiSrt.encode(match)
        val directory = File(cacheDir, "sasayaki-subtitles").apply { mkdirs() }
        // Keep shared files available after the chooser closes; prune old exports on the next use.
        val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
        val export = File(File(directory, UUID.randomUUID().toString()), SasayakiSrt.fileName(title))
        export.parentFile!!.mkdirs()
        export.writeText(text, Charsets.UTF_8)
        export
    }

    suspend fun save(file: File, destination: Uri) = withContext(ioDispatcher) {
        // Open the snapshot first, so a missing/evicted cache file does not truncate the destination.
        file.inputStream().use { input ->
            requireNotNull(contentResolver.openOutputStream(destination, "wt")).use { output -> input.copyTo(output) }
        }
        Unit
    }
}
