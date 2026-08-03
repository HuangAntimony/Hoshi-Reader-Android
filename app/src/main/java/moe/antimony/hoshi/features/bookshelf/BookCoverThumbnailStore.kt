package moe.antimony.hoshi.features.bookshelf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.BuildConfig
import moe.antimony.hoshi.di.CacheDir
import moe.antimony.hoshi.di.IoDispatcher

private const val ThumbnailCacheVersion = 2
private const val DefaultMaxDiskBytes = 160L * 1024L * 1024L
private const val AccessTimeRefreshIntervalMillis = 60L * 60L * 1_000L

internal fun coverThumbnailBucket(requestedMaxDimensionPx: Int): Int = when {
    requestedMaxDimensionPx <= 256 -> 256
    requestedMaxDimensionPx <= 512 -> 512
    else -> 768
}

internal fun interface BookCoverThumbnailEncoder {
    fun encode(source: File, destination: File, maxDimensionPx: Int): Boolean
}

internal sealed interface BookCoverThumbnailOpenResult {
    data class Ready(val input: InputStream) : BookCoverThumbnailOpenResult

    data object InvalidSource : BookCoverThumbnailOpenResult
    data object CacheUnavailable : BookCoverThumbnailOpenResult
}

@Singleton
internal class BookCoverThumbnailStore internal constructor(
    internal val cacheDirectory: File,
    private val encoder: BookCoverThumbnailEncoder,
    private val ioDispatcher: CoroutineDispatcher,
    private val maxDiskBytes: Long,
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
    private val transientRetryDelayMillis: Long = 30_000L,
    private val inputStreamProvider: (File) -> InputStream = File::inputStream,
) {
    @Inject
    constructor(
        @CacheDir cacheDir: File,
        encoder: AndroidBookCoverThumbnailEncoder,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(
        cacheDirectory = cacheDir.resolve("book-cover-thumbnails-v$ThumbnailCacheVersion"),
        encoder = encoder,
        ioDispatcher = ioDispatcher,
        maxDiskBytes = DefaultMaxDiskBytes,
    )

    private val generationMutex = Mutex()
    private val cacheMutationMutex = Mutex()
    private val invalidSourceKeys = mutableSetOf<String>()
    private val transientRetryAfterMillis = mutableMapOf<String, Long>()
    private val decodeFailedKeys = mutableSetOf<String>()

    suspend fun thumbnail(
        coverSource: BookCoverSource,
        requestedMaxDimensionPx: Int,
    ): File? = withContext(ioDispatcher) {
        val source = File(coverSource.path)
        if (!source.isFile) return@withContext null

        val bucket = coverThumbnailBucket(requestedMaxDimensionPx)
        val key = thumbnailKey(coverSource.cacheKey, bucket)
        val shouldInvalidate = synchronized(decodeFailedKeys) { decodeFailedKeys.remove(key) }
        if (shouldInvalidate) {
            cacheMutationMutex.withLock {
                cacheDirectory.resolve("$key.webp").delete()
            }
        }
        cachedFile(key)?.let { return@withContext it }
        if (isTransientCoolingDown(key)) return@withContext null
        synchronized(invalidSourceKeys) {
            if (key in invalidSourceKeys) return@withContext null
        }

        generationMutex.withLock {
            cachedFile(key)?.let { return@withLock it }
            if (isTransientCoolingDown(key)) return@withLock null
            synchronized(invalidSourceKeys) {
                if (key in invalidSourceKeys) return@withLock null
            }

            cacheDirectory.mkdirs()
            val destination = cacheDirectory.resolve("$key.webp")
            val temporary = cacheDirectory.resolve(".$key-${UUID.randomUUID()}.tmp")
            try {
                val encoded = encoder.encode(source, temporary, bucket)
                if (!encoded || !temporary.isFile || temporary.length() == 0L) {
                    synchronized(invalidSourceKeys) { invalidSourceKeys += key }
                    return@withLock null
                }
                cacheMutationMutex.withLock {
                    moveAtomically(temporary, destination)
                    trimToSize(protectedFile = destination)
                }
                synchronized(transientRetryAfterMillis) { transientRetryAfterMillis.remove(key) }
                destination
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                recordTransientFailure(key)
                null
            } finally {
                temporary.delete()
            }
        }
    }

    suspend fun openThumbnailResult(
        coverSource: BookCoverSource,
        requestedMaxDimensionPx: Int,
    ): BookCoverThumbnailOpenResult = withContext(ioDispatcher) {
        if (!File(coverSource.path).isFile) return@withContext BookCoverThumbnailOpenResult.InvalidSource
        val bucket = coverThumbnailBucket(requestedMaxDimensionPx)
        val key = thumbnailKey(coverSource.cacheKey, bucket)
        val thumbnail = thumbnail(coverSource, requestedMaxDimensionPx)
            ?: return@withContext if (synchronized(invalidSourceKeys) { key in invalidSourceKeys }) {
                BookCoverThumbnailOpenResult.InvalidSource
            } else {
                BookCoverThumbnailOpenResult.CacheUnavailable
            }
        try {
            BookCoverThumbnailOpenResult.Ready(inputStreamProvider(thumbnail))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            cacheMutationMutex.withLock {
                thumbnail.delete()
            }
            recordTransientFailure(key)
            BookCoverThumbnailOpenResult.CacheUnavailable
        }
    }

    fun markDerivativeDecodeFailed(coverSource: BookCoverSource, bucket: Int) {
        synchronized(decodeFailedKeys) {
            decodeFailedKeys += thumbnailKey(coverSource.cacheKey, bucket)
        }
    }

    private fun isTransientCoolingDown(key: String): Boolean = synchronized(transientRetryAfterMillis) {
        val retryAfter = transientRetryAfterMillis[key] ?: return@synchronized false
        if (elapsedRealtimeMillis() < retryAfter) return@synchronized true
        transientRetryAfterMillis.remove(key)
        false
    }

    private fun recordTransientFailure(key: String) {
        synchronized(transientRetryAfterMillis) {
            transientRetryAfterMillis[key] = elapsedRealtimeMillis() + transientRetryDelayMillis
        }
    }

    private fun cachedFile(key: String): File? =
        cacheDirectory.resolve("$key.webp")
            .takeIf { it.isFile && it.length() > 0L }
            ?.also { file ->
                if (System.currentTimeMillis() - file.lastModified() >= AccessTimeRefreshIntervalMillis) {
                    file.setLastModified(System.currentTimeMillis())
                }
            }

    private fun trimToSize(protectedFile: File) {
        val files = cacheDirectory.listFiles()
            ?.filter { it.isFile && it.extension == "webp" }
            ?.sortedBy(File::lastModified)
            .orEmpty()
        var totalBytes = files.sumOf(File::length)
        if (totalBytes <= maxDiskBytes) return
        for (file in files) {
            if (file == protectedFile) continue
            val length = file.length()
            if (file.delete()) totalBytes -= length
            if (totalBytes <= maxDiskBytes) return
        }
    }
}

private fun thumbnailKey(sourceFingerprint: String, bucket: Int): String {
    val input = "v$ThumbnailCacheVersion:$bucket:$sourceFingerprint"
    return MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun moveAtomically(source: File, destination: File) {
    try {
        Files.move(
            source.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(
            source.toPath(),
            destination.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}

internal class AndroidBookCoverThumbnailEncoder @Inject constructor() : BookCoverThumbnailEncoder {
    override fun encode(source: File, destination: File, maxDimensionPx: Int): Boolean {
        Trace.beginSection("HoshiCoverSourceDecode")
        return try {
            if (BuildConfig.DEBUG) {
                Log.d(LogTag, "source_decode bucket=$maxDimensionPx")
            }
            val bitmap = decodeCoverThumbnail(source, maxDimensionPx) ?: return false
            try {
                destination.outputStream().buffered().use { output ->
                    if (!bitmap.compress(webpFormat(bitmap), webpQuality(bitmap), output)) {
                        throw IOException("Unable to encode book cover thumbnail.")
                    }
                    true
                }
            } finally {
                bitmap.recycle()
            }
        } finally {
            Trace.endSection()
        }
    }

    private companion object {
        const val LogTag = "HoshiCoverPipeline"
    }
}

private fun decodeCoverThumbnail(source: File, maxDimensionPx: Int): Bitmap? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        decodeCoverThumbnailWithImageDecoder(source, maxDimensionPx)
    } else {
        decodeCoverThumbnailWithBitmapFactory(source, maxDimensionPx)
    }

@RequiresApi(Build.VERSION_CODES.P)
private fun decodeCoverThumbnailWithImageDecoder(source: File, maxDimensionPx: Int): Bitmap? =
    runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
            val target = coverThumbnailSize(info.size.width, info.size.height, maxDimensionPx)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(target.width, target.height)
        }
    }.getOrNull()

private fun decodeCoverThumbnailWithBitmapFactory(source: File, maxDimensionPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(source.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = coverDecodeSampleSize(bounds.outWidth, bounds.outHeight, maxDimensionPx)
    }
    val decoded = BitmapFactory.decodeFile(source.absolutePath, options) ?: return null
    val target = coverThumbnailSize(decoded.width, decoded.height, maxDimensionPx)
    if (decoded.width == target.width && decoded.height == target.height) return decoded
    return Bitmap.createScaledBitmap(decoded, target.width, target.height, true).also {
        if (it !== decoded) decoded.recycle()
    }
}

@Suppress("DEPRECATION")
private fun webpFormat(bitmap: Bitmap): Bitmap.CompressFormat = when {
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> Bitmap.CompressFormat.WEBP
    bitmap.hasAlpha() -> Bitmap.CompressFormat.WEBP_LOSSLESS
    else -> Bitmap.CompressFormat.WEBP_LOSSY
}

private fun webpQuality(bitmap: Bitmap): Int = if (bitmap.hasAlpha()) 100 else 88

internal fun coverDecodeSampleSize(width: Int, height: Int, maxDimensionPx: Int): Int {
    if (width <= 0 || height <= 0 || maxDimensionPx <= 0) return 1
    var sampleSize = 1
    while (max(width / (sampleSize * 2), height / (sampleSize * 2)) >= maxDimensionPx) {
        sampleSize *= 2
    }
    return sampleSize
}

internal data class CoverThumbnailSize(
    val width: Int,
    val height: Int,
)

internal fun coverThumbnailSize(width: Int, height: Int, maxDimensionPx: Int): CoverThumbnailSize {
    if (width <= 0 || height <= 0 || maxDimensionPx <= 0) {
        return CoverThumbnailSize(width = width, height = height)
    }
    val longest = max(width, height)
    if (longest <= maxDimensionPx) {
        return CoverThumbnailSize(width = width, height = height)
    }
    val scale = maxDimensionPx.toDouble() / longest.toDouble()
    return CoverThumbnailSize(
        width = max(1, (width * scale).toInt()),
        height = max(1, (height * scale).toInt()),
    )
}
