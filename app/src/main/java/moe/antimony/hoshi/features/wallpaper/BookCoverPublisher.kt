package moe.antimony.hoshi.features.wallpaper

import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PixelRect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

internal fun coverDestinationRect(
    mode: BookCoverScaleMode,
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
): PixelRect {
    require(sourceWidth > 0 && sourceHeight > 0 && targetWidth > 0 && targetHeight > 0)
    if (mode == BookCoverScaleMode.Stretch) {
        return PixelRect(left = 0, top = 0, width = targetWidth, height = targetHeight)
    }
    val widthScale = targetWidth.toDouble() / sourceWidth
    val heightScale = targetHeight.toDouble() / sourceHeight
    val scale = when (mode) {
        BookCoverScaleMode.Fit -> minOf(widthScale, heightScale)
        BookCoverScaleMode.Fill -> maxOf(widthScale, heightScale)
        BookCoverScaleMode.Stretch -> error("Handled above")
    }
    val width = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
    val height = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
    return PixelRect(
        left = (targetWidth - width) / 2,
        top = (targetHeight - height) / 2,
        width = width,
        height = height,
    )
}

internal fun coverDecodeSampleSize(
    mode: BookCoverScaleMode,
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
): Int {
    val destination = coverDestinationRect(mode, sourceWidth, sourceHeight, targetWidth, targetHeight)
    var sample = 1
    while (
        sourceWidth / (sample * 2) >= destination.width &&
        sourceHeight / (sample * 2) >= destination.height
    ) {
        sample *= 2
    }
    return sample
}

enum class BookCoverPublishFailure {
    MissingCover,
    RenderFailed,
    WallpaperUnsupported,
    WallpaperNotAllowed,
    WallpaperUpdateFailed,
    ExportTargetMissing,
    ExportPermissionLost,
    ExportWriteFailed,
    IReaderUnsupported,
    IReaderBookScreenSaverNotSelected,
    IReaderWriteFailed,
    IReaderRefreshFailed,
    SettingsUnavailable,
    UnexpectedFailure,
}

sealed interface BookCoverTargetResult {
    data object Skipped : BookCoverTargetResult
    data object Success : BookCoverTargetResult
    data class Failed(val reason: BookCoverPublishFailure) : BookCoverTargetResult
}

data class BookCoverPublishResult(
    val lockScreen: BookCoverTargetResult,
    val export: BookCoverTargetResult,
    val iReader: BookCoverTargetResult = BookCoverTargetResult.Skipped,
) {
    val hasFailures: Boolean
        get() = lockScreen is BookCoverTargetResult.Failed ||
            export is BookCoverTargetResult.Failed ||
            iReader is BookCoverTargetResult.Failed

    companion object {
        val Skipped = BookCoverPublishResult(
            lockScreen = BookCoverTargetResult.Skipped,
            export = BookCoverTargetResult.Skipped,
            iReader = BookCoverTargetResult.Skipped,
        )
    }
}

fun interface BookCoverImageRenderer {
    suspend fun render(source: File, scaleMode: BookCoverScaleMode): File
}

fun interface BookCoverLockScreenTarget {
    suspend fun publish(image: File): BookCoverPublishFailure?
}

fun interface BookCoverExportTarget {
    suspend fun publish(image: File, targetUri: String): BookCoverPublishFailure?
}

fun interface BookCoverIReaderTarget {
    suspend fun publish(image: File): BookCoverPublishFailure?
}

interface BookCoverPublisher {
    suspend fun publish(coverFile: File): BookCoverPublishResult
}

class DefaultBookCoverPublisher(
    private val settings: Flow<BookCoverWallpaperSettings>,
    private val renderer: BookCoverImageRenderer,
    private val lockScreenTarget: BookCoverLockScreenTarget,
    private val exportTarget: BookCoverExportTarget,
    private val iReaderTarget: BookCoverIReaderTarget,
) : BookCoverPublisher {
    private val publishMutex = Mutex()

    override suspend fun publish(coverFile: File): BookCoverPublishResult = publishMutex.withLock {
        publishLocked(coverFile)
    }

    private suspend fun publishLocked(coverFile: File): BookCoverPublishResult {
        val current = try {
            settings.first()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            return BookCoverPublishResult.failedForAll(BookCoverPublishFailure.SettingsUnavailable)
        }
        val lockEnabled = current.updateLockScreen
        val iReaderEnabled = current.updateIReaderBookCover
        val exportUri = current.exportTargetUri?.takeIf { current.exportEnabled && it.isNotBlank() }
        val exportMissing = current.exportEnabled && exportUri == null
        if (!lockEnabled && !iReaderEnabled && !current.exportEnabled) return BookCoverPublishResult.Skipped
        if (!lockEnabled && !iReaderEnabled && exportMissing) {
            return BookCoverPublishResult(
                lockScreen = BookCoverTargetResult.Skipped,
                export = BookCoverTargetResult.Failed(BookCoverPublishFailure.ExportTargetMissing),
                iReader = BookCoverTargetResult.Skipped,
            )
        }

        val rendered = try {
            renderer.render(coverFile, current.scaleMode)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            val failed = BookCoverTargetResult.Failed(BookCoverPublishFailure.RenderFailed)
            return BookCoverPublishResult(
                lockScreen = if (lockEnabled) failed else BookCoverTargetResult.Skipped,
                export = when {
                    exportMissing -> BookCoverTargetResult.Failed(BookCoverPublishFailure.ExportTargetMissing)
                    exportUri != null -> failed
                    else -> BookCoverTargetResult.Skipped
                },
                iReader = if (iReaderEnabled) failed else BookCoverTargetResult.Skipped,
            )
        }
        val lockResult = if (lockEnabled) {
            try {
                lockScreenTarget.publish(rendered).toTargetResult()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                BookCoverTargetResult.Failed(BookCoverPublishFailure.WallpaperUpdateFailed)
            }
        } else {
            BookCoverTargetResult.Skipped
        }
        val iReaderResult = if (iReaderEnabled) {
            try {
                iReaderTarget.publish(rendered).toTargetResult()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                BookCoverTargetResult.Failed(BookCoverPublishFailure.IReaderWriteFailed)
            }
        } else {
            BookCoverTargetResult.Skipped
        }
        val exportResult = when {
            exportMissing -> BookCoverTargetResult.Failed(BookCoverPublishFailure.ExportTargetMissing)
            exportUri != null -> try {
                exportTarget.publish(rendered, exportUri).toTargetResult()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                BookCoverTargetResult.Failed(BookCoverPublishFailure.ExportWriteFailed)
            }
            else -> BookCoverTargetResult.Skipped
        }
        return BookCoverPublishResult(
            lockScreen = lockResult,
            export = exportResult,
            iReader = iReaderResult,
        )
    }
}

internal fun BookCoverPublishResult.Companion.failedForAll(
    reason: BookCoverPublishFailure,
): BookCoverPublishResult {
    val failure = BookCoverTargetResult.Failed(reason)
    return BookCoverPublishResult(lockScreen = failure, export = failure, iReader = failure)
}

private fun BookCoverPublishFailure?.toTargetResult(): BookCoverTargetResult =
    this?.let(BookCoverTargetResult::Failed) ?: BookCoverTargetResult.Success
