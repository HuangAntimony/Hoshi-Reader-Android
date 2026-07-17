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

internal fun fitCenterRect(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
): PixelRect {
    require(sourceWidth > 0 && sourceHeight > 0 && targetWidth > 0 && targetHeight > 0)
    val scale = minOf(
        targetWidth.toDouble() / sourceWidth,
        targetHeight.toDouble() / sourceHeight,
    )
    val width = (sourceWidth * scale).roundToInt().coerceIn(1, targetWidth)
    val height = (sourceHeight * scale).roundToInt().coerceIn(1, targetHeight)
    return PixelRect(
        left = (targetWidth - width) / 2,
        top = (targetHeight - height) / 2,
        width = width,
        height = height,
    )
}

internal fun coverDecodeSampleSize(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
): Int {
    val destination = fitCenterRect(sourceWidth, sourceHeight, targetWidth, targetHeight)
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
) {
    val hasFailures: Boolean
        get() = lockScreen is BookCoverTargetResult.Failed || export is BookCoverTargetResult.Failed

    companion object {
        val Skipped = BookCoverPublishResult(
            lockScreen = BookCoverTargetResult.Skipped,
            export = BookCoverTargetResult.Skipped,
        )
    }
}

fun interface BookCoverImageRenderer {
    suspend fun render(source: File): File
}

fun interface BookCoverLockScreenTarget {
    suspend fun publish(image: File): BookCoverPublishFailure?
}

fun interface BookCoverExportTarget {
    suspend fun publish(image: File, targetUri: String): BookCoverPublishFailure?
}

interface BookCoverPublisher {
    suspend fun publish(coverFile: File): BookCoverPublishResult
}

class DefaultBookCoverPublisher(
    private val settings: Flow<BookCoverWallpaperSettings>,
    private val renderer: BookCoverImageRenderer,
    private val lockScreenTarget: BookCoverLockScreenTarget,
    private val exportTarget: BookCoverExportTarget,
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
            return BookCoverPublishResult.failedForBoth(BookCoverPublishFailure.SettingsUnavailable)
        }
        val lockEnabled = current.updateLockScreen
        val exportUri = current.exportTargetUri?.takeIf { current.exportEnabled && it.isNotBlank() }
        val exportMissing = current.exportEnabled && exportUri == null
        if (!lockEnabled && !current.exportEnabled) return BookCoverPublishResult.Skipped
        if (!lockEnabled && exportMissing) {
            return BookCoverPublishResult(
                lockScreen = BookCoverTargetResult.Skipped,
                export = BookCoverTargetResult.Failed(BookCoverPublishFailure.ExportTargetMissing),
            )
        }

        val rendered = try {
            renderer.render(coverFile)
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
        return BookCoverPublishResult(lockScreen = lockResult, export = exportResult)
    }
}

internal fun BookCoverPublishResult.Companion.failedForBoth(
    reason: BookCoverPublishFailure,
): BookCoverPublishResult {
    val failure = BookCoverTargetResult.Failed(reason)
    return BookCoverPublishResult(lockScreen = failure, export = failure)
}

private fun BookCoverPublishFailure?.toTargetResult(): BookCoverTargetResult =
    this?.let(BookCoverTargetResult::Failed) ?: BookCoverTargetResult.Success
