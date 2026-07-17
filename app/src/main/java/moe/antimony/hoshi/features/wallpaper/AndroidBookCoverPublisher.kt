package moe.antimony.hoshi.features.wallpaper

import android.app.WallpaperManager
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.DisplayMetrics
import android.view.WindowManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.di.CacheDir
import moe.antimony.hoshi.di.IoDispatcher

data class BookCoverWallpaperCapability(
    val isSupported: Boolean,
    val isSetAllowed: Boolean,
) {
    val canUpdateLockScreen: Boolean
        get() = isSupported && isSetAllowed
}

fun interface BookCoverWallpaperCapabilityProvider {
    fun capability(): BookCoverWallpaperCapability
}

data class BookCoverScreenPixelSize(
    val width: Int,
    val height: Int,
)

fun interface BookCoverScreenSizeProvider {
    fun screenSize(): BookCoverScreenPixelSize
}

class AndroidBookCoverScreenSizeProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : BookCoverScreenSizeProvider {
    override fun screenSize(): BookCoverScreenPixelSize {
        val windowManager = context.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            BookCoverScreenPixelSize(width = bounds.width(), height = bounds.height())
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics().also(windowManager.defaultDisplay::getRealMetrics)
            BookCoverScreenPixelSize(width = metrics.widthPixels, height = metrics.heightPixels)
        }
    }
}

class AndroidBookCoverImageRenderer @Inject constructor(
    private val screenSizeProvider: BookCoverScreenSizeProvider,
    @param:CacheDir private val cacheDir: File,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : BookCoverImageRenderer {
    override suspend fun render(source: File): File = withContext(ioDispatcher) {
        require(source.isFile) { "Cover file is missing." }
        val screenSize = screenSizeProvider.screenSize()
        val targetWidth = screenSize.width.coerceAtLeast(1)
        val targetHeight = screenSize.height.coerceAtLeast(1)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Cover image cannot be decoded." }
        val decoded = BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = coverDecodeSampleSize(
                    sourceWidth = bounds.outWidth,
                    sourceHeight = bounds.outHeight,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                )
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: error("Cover image cannot be decoded.")
        val canvasBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(canvasBitmap)
            canvas.drawColor(Color.WHITE)
            val destination = fitCenterRect(decoded.width, decoded.height, targetWidth, targetHeight)
            canvas.drawBitmap(
                decoded,
                null,
                Rect(
                    destination.left,
                    destination.top,
                    destination.left + destination.width,
                    destination.top + destination.height,
                ),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
            val outputDirectory = cacheDir.resolve("book-cover-wallpaper").apply { mkdirs() }
            val temporary = outputDirectory.resolve("current-cover.tmp")
            val output = outputDirectory.resolve("current-cover.png")
            FileOutputStream(temporary).use { stream ->
                check(canvasBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    "Cover image cannot be encoded."
                }
                stream.fd.sync()
            }
            runCatching {
                Files.move(
                    temporary.toPath(),
                    output.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            output
        } finally {
            decoded.recycle()
            canvasBitmap.recycle()
        }
    }
}

class AndroidBookCoverLockScreenTarget @Inject constructor(
    @ApplicationContext context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : BookCoverLockScreenTarget, BookCoverWallpaperCapabilityProvider {
    private val wallpaperManager = WallpaperManager.getInstance(context)

    override fun capability(): BookCoverWallpaperCapability = BookCoverWallpaperCapability(
        isSupported = wallpaperManager.isWallpaperSupported,
        isSetAllowed = wallpaperManager.isSetWallpaperAllowed,
    )

    override suspend fun publish(image: File): BookCoverPublishFailure? = withContext(ioDispatcher) {
        val capability = capability()
        when {
            !capability.isSupported -> BookCoverPublishFailure.WallpaperUnsupported
            !capability.isSetAllowed -> BookCoverPublishFailure.WallpaperNotAllowed
            else -> runCatching {
                image.inputStream().buffered().use { stream ->
                    wallpaperManager.setStream(
                        stream,
                        null,
                        false,
                        WallpaperManager.FLAG_LOCK,
                    )
                }
            }.fold(
                onSuccess = { wallpaperId ->
                    if (wallpaperId == 0) BookCoverPublishFailure.WallpaperUpdateFailed else null
                },
                onFailure = { BookCoverPublishFailure.WallpaperUpdateFailed },
            )
        }
    }
}

class AndroidBookCoverExportTarget @Inject constructor(
    private val contentResolver: ContentResolver,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : BookCoverExportTarget {
    override suspend fun publish(image: File, targetUri: String): BookCoverPublishFailure? =
        withContext(ioDispatcher) {
            val uri = runCatching { Uri.parse(targetUri) }.getOrNull()
                ?: return@withContext BookCoverPublishFailure.ExportTargetMissing
            val hasWriteGrant = contentResolver.persistedUriPermissions.any { permission ->
                permission.uri == uri && permission.isWritePermission
            }
            if (!hasWriteGrant) return@withContext BookCoverPublishFailure.ExportPermissionLost
            try {
                val descriptor = contentResolver.openFileDescriptor(uri, "rwt")
                    ?: return@withContext BookCoverPublishFailure.ExportWriteFailed
                ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                    image.inputStream().buffered().use { input -> input.copyTo(output) }
                    output.flush()
                    descriptor.fileDescriptor.sync()
                }
                null
            } catch (_: SecurityException) {
                BookCoverPublishFailure.ExportPermissionLost
            } catch (_: Exception) {
                BookCoverPublishFailure.ExportWriteFailed
            }
        }
}
