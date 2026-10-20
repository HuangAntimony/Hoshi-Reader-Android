package moe.antimony.hoshi.features.sasayaki.transcription

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@kotlinx.serialization.Serializable
internal data class SasayakiModelFile(val name: String, val url: String, val bytes: Long, val sha256: String)

internal class SasayakiDownloadResponse(
    val input: InputStream,
    val length: Long,
    val offset: Long = 0,
    val totalLength: Long = -1,
    private val closeAction: () -> Unit = {},
) : Closeable {
    private val closed = AtomicBoolean(false)
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            // Disconnect first: closing some HTTP input streams can otherwise wait
            // for the remaining body, including during cancellation.
            runCatching(closeAction)
            runCatching(input::close)
        }
    }
}

internal fun interface SasayakiModelTransport {
    suspend fun open(url: String, offset: Long): SasayakiDownloadResponse
}

/** Resource context travels with the original exception through the UI/diagnostic boundary. */
internal class SasayakiDownloadFailure(
    val file: SasayakiModelFile,
    val receivedBytes: Long,
    val attempts: Int,
    cause: Throwable,
) : IOException(cause.message, cause)

internal class SasayakiDownloadValidationException(message: String) : IOException(message)

private class InterruptedTransfer(cause: IOException) : IOException(cause.message, cause)

internal class SasayakiModelStore(
    private val directory: File,
    private val transport: SasayakiModelTransport,
    private val ioDispatcher: CoroutineDispatcher,
    private val files: List<SasayakiModelFile>,
    private val readOnly: Boolean = false,
) {
    private val mutex = Mutex()

    init {
        require(files.all { it.name.isNotBlank() && it.name != "." && it.name != ".." && '/' !in it.name && '\\' !in it.name })
        require(files.map { it.name }.distinct().size == files.size)
    }

    suspend fun missingBytes(): Long = mutex.withLock {
        withContext(ioDispatcher) {
            files.filterNot { valid(File(directory, it.name), it) || valid(partialFile(it), it) }.sumOf { it.bytes }
        }
    }

    suspend fun ensure(onDownloadRequired: suspend (Long) -> Unit, onProgress: suspend (Double) -> Unit): File = mutex.withLock {
        withContext(ioDispatcher) {
            try {
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create model directory")
                val checkpoints = files.map(::partialFile).toSet()
                // Preserve hash-specific checkpoints, removing abandoned staging files.
                directory.listFiles()?.filter { file ->
                    file !in checkpoints && file.name.endsWith(".part") && files.any { file.name.startsWith(it.name) }
                }?.forEach { file -> if (!file.delete()) throw IOException("Cannot remove incomplete model") }
                for (spec in files) {
                    val partial = partialFile(spec)
                    if (!valid(File(directory, spec.name), spec) && valid(partial, spec)) {
                        publish(partial, spec)
                    } else if (partial.exists() && partial.length() >= spec.bytes) {
                        removePartial(partial)
                    }
                }
                val ready = files.filter { spec -> valid(File(directory, spec.name), spec) }.toSet()
                if (ready.size == files.size) return@withContext directory
                onDownloadRequired(files.filterNot { it in ready }.sumOf { it.bytes })
                currentCoroutineContext().ensureActive()
                val total = files.filterNot { it in ready }.sumOf { it.bytes }.toDouble()
                var completed = 0L
                onProgress(completed / total)
                for (spec in files) {
                    currentCoroutineContext().ensureActive()
                    if (spec in ready) continue
                    download(spec) { bytes -> onProgress((completed + bytes) / total) }
                    completed += spec.bytes
                }
                onProgress(1.0)
                directory
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                throw error
            }
        }
    }

    private fun partialFile(spec: SasayakiModelFile) = File(directory, "${spec.name}.${spec.sha256}.part")

    private fun publish(partial: File, spec: SasayakiModelFile) {
        Files.move(partial.toPath(), File(directory, spec.name).toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun removePartial(partial: File) {
        if (partial.exists() && !partial.delete()) throw IOException("Cannot remove incomplete model")
    }

    private suspend fun download(spec: SasayakiModelFile, progress: suspend (Long) -> Unit) {
        val partial = partialFile(spec)
        for (attempt in 1..3) {
            var received = partial.length()
            try {
                currentCoroutineContext().ensureActive()
                progress(received)
                // The last read can fail after all expected bytes arrived. Verify that
                // checkpoint before issuing Range at EOF (which would return HTTP 416).
                if (received == spec.bytes) {
                    if (!valid(partial, spec)) {
                        removePartial(partial)
                        throw SasayakiDownloadValidationException("Model integrity verification failed")
                    }
                    publish(partial, spec)
                    return
                }
                val response = try {
                    transport.open(spec.url, received)
                } catch (error: IOException) {
                    if (error !is SSLException && error !is SasayakiDownloadValidationException &&
                        (error !is SasayakiHttpException || error.retryable)) throw InterruptedTransfer(error)
                    throw error
                }
                coroutineScope {
                    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { response.close() }
                    }
                    try {
                        if (response.offset != 0L && response.offset != received ||
                            response.totalLength >= 0 && response.totalLength != spec.bytes ||
                            response.length >= 0 && response.length != spec.bytes - response.offset) {
                            throw SasayakiDownloadValidationException("Model response size or range mismatch")
                        }
                        // A server may ignore Range and return the complete resource with 200.
                        received = response.offset
                        appendResponse(response, partial, spec, { received = it }, progress)
                    } finally {
                        response.close()
                        cancellation.cancel()
                    }
                }
                if (!valid(partial, spec)) {
                    removePartial(partial)
                    throw SasayakiDownloadValidationException("Model integrity verification failed")
                }
                currentCoroutineContext().ensureActive()
                publish(partial, spec)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                val original = if (error is InterruptedTransfer) error.cause!! else error
                if (error !is InterruptedTransfer || attempt == 3) {
                    throw SasayakiDownloadFailure(spec, received, attempt, original)
                }
                delay(1_000L shl (attempt - 1))
            }
        }
    }

    private suspend fun valid(file: File, spec: SasayakiModelFile): Boolean {
        if (!file.isFile || file.length() != spec.bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        if (digest.hex() != spec.sha256) return false
        if (readOnly && !file.setReadOnly()) throw IOException("Cannot protect runtime file")
        return true
    }

    private suspend fun appendResponse(
        response: SasayakiDownloadResponse,
        partial: File,
        spec: SasayakiModelFile,
        received: (Long) -> Unit,
        progress: suspend (Long) -> Unit,
    ) {
        var total = response.offset
        openPartial(partial, response.offset > 0).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = try { response.input.read(buffer) } catch (error: IOException) { throw InterruptedTransfer(error) }
                if (count < 0) break
                if (count == 0) continue
                total += count
                received(total)
                if (total > spec.bytes) {
                    removePartial(partial)
                    throw SasayakiDownloadValidationException("Model exceeds expected size")
                }
                output.write(buffer, 0, count)
                progress(total)
            }
            output.fd.sync()
        }
        if (total != spec.bytes) throw InterruptedTransfer(EOFException("Expected ${spec.bytes} bytes, received $total"))
    }

    private suspend fun openPartial(partial: File, append: Boolean): FileOutputStream {
        if (!readOnly) return FileOutputStream(partial, append)
        // Never make a native checkpoint writable again. Copy its prefix through a new
        // descriptor, mark that file read-only before writing, and atomically replace it.
        val staging = File.createTempFile(partial.name.substringBeforeLast(".part"), ".part", directory)
        val output = FileOutputStream(staging)
        try {
            if (!staging.setReadOnly()) throw IOException("Cannot protect runtime file")
            if (append) partial.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
            Files.move(staging.toPath(), partial.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return output
        } catch (error: Exception) {
            runCatching { output.close() }
            throw error
        } finally {
            staging.delete()
        }
    }

    private fun MessageDigest.hex() = digest().joinToString("") { "%02x".format(it) }
}
