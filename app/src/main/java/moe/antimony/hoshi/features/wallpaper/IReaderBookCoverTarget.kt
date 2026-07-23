package moe.antimony.hoshi.features.wallpaper

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

fun interface IReaderRawBitmapEncoder {
    fun encode(source: File, output: File)
}

fun interface IReaderBookCoverNotifier {
    fun notifyChanged()
}

internal fun writeIReaderRawBitmap(
    output: File,
    width: Int,
    height: Int,
    configOrdinal: Int,
    pixels: ByteBuffer,
) {
    require(width > 0 && height > 0)
    val header = ByteBuffer.allocate(IReaderRawBitmapHeaderSize)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(IReaderRawBitmapMagic)
        .putInt(width)
        .putInt(height)
        .putInt(configOrdinal)
        .flip() as ByteBuffer
    FileOutputStream(output).use { stream ->
        val channel = stream.channel
        while (header.hasRemaining()) channel.write(header)
        while (pixels.hasRemaining()) channel.write(pixels)
        stream.fd.sync()
    }
}

class IReaderBookCoverFileTarget(
    private val directory: File,
    private val isBookCoverScreenSaverSelected: () -> Boolean,
    private val encoder: IReaderRawBitmapEncoder,
    private val notifier: IReaderBookCoverNotifier,
    private val outputName: () -> String,
    private val ioDispatcher: CoroutineDispatcher,
) : BookCoverIReaderTarget {
    override suspend fun publish(image: File): BookCoverPublishFailure? = withContext(ioDispatcher) {
        if (!isBookCoverScreenSaverSelected()) {
            return@withContext BookCoverPublishFailure.IReaderBookScreenSaverNotSelected
        }
        val directoryCreated = !directory.exists()
        if ((directoryCreated && !directory.mkdirs()) || !directory.isDirectory || !directory.canWrite()) {
            return@withContext BookCoverPublishFailure.IReaderUnsupported
        }
        if (directoryCreated && !setSharedReadOwnerWritePermissions(directory, executable = true)) {
            return@withContext BookCoverPublishFailure.IReaderUnsupported
        }
        val finalFile = directory.resolve(outputName())
        val temporary = directory.resolve("${finalFile.name}.tmp")
        try {
            encoder.encode(image, temporary)
            check(temporary.isFile && temporary.length() > IReaderRawBitmapHeaderSize)
            check(setSharedReadOwnerWritePermissions(temporary))
            runCatching {
                Files.move(
                    temporary.toPath(),
                    finalFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(
                    temporary.toPath(),
                    finalFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            check(setSharedReadOwnerWritePermissions(finalFile))
        } catch (exception: CancellationException) {
            temporary.delete()
            throw exception
        } catch (_: Exception) {
            temporary.delete()
            return@withContext BookCoverPublishFailure.IReaderWriteFailed
        }

        val staleFiles = directory.listFiles()
            ?: return@withContext BookCoverPublishFailure.IReaderWriteFailed
        staleFiles.forEach { file ->
            if (file != finalFile) file.delete()
        }
        val remainingFiles = directory.listFiles()
            ?: return@withContext BookCoverPublishFailure.IReaderWriteFailed
        if (remainingFiles.any { it != finalFile }) {
            return@withContext BookCoverPublishFailure.IReaderWriteFailed
        }
        try {
            notifier.notifyChanged()
            null
        } catch (_: Exception) {
            BookCoverPublishFailure.IReaderRefreshFailed
        }
    }
}

private fun setSharedReadOwnerWritePermissions(
    file: File,
    executable: Boolean = false,
): Boolean {
    if (!file.setReadable(true, false)) return false
    if (!file.setWritable(false, false) || !file.setWritable(true, true)) return false
    return !executable || file.setExecutable(true, false)
}

private const val IReaderRawBitmapMagic = 1_380_013_890
private const val IReaderRawBitmapHeaderSize = 16
