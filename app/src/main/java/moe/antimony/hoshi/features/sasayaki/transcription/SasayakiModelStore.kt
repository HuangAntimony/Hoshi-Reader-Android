package moe.antimony.hoshi.features.sasayaki.transcription

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@kotlinx.serialization.Serializable
internal data class SasayakiModelFile(val name: String, val url: String, val bytes: Long, val sha256: String)

internal object ReazonSpeechModelCatalog {
    private const val BASE = "https://huggingface.co/reazon-research/reazonspeech-k2-v2/resolve/291488c8151be24d7da4bf7af26e533fad96e407/"
    val files = listOf(
        SasayakiModelFile("encoder.int8.onnx", BASE + "encoder-epoch-99-avg-1.int8.onnx", 154670139,
            "2c7bd08a8a99f9ddd0d9e458456577b1f6279214e51426f114f9eced44c54e1d"),
        SasayakiModelFile("decoder.int8.onnx", BASE + "decoder-epoch-99-avg-1.int8.onnx", 2959337,
            "8f0bff94d38797b03b762634ed03211a8e303d06cc4603cdd0cf4199d6eb1485"),
        SasayakiModelFile("joiner.int8.onnx", BASE + "joiner-epoch-99-avg-1.int8.onnx", 2696970,
            "49cc7ea1d3d35a40a27442db5e89996da64bf0e683a903dce76e99e57a12e4de"),
        SasayakiModelFile("tokens.txt", BASE + "tokens.txt", 45754,
            "2c3ac659818a48a0c04010e0593bbc4d7c8a24a054340b01131499c05fd52def"),
        SasayakiModelFile("silero_vad.onnx", "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx", 643854,
            "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"),
    )
}

internal class SasayakiDownloadResponse(
    val input: InputStream,
    val length: Long,
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
    suspend fun open(url: String): SasayakiDownloadResponse
}

internal class SasayakiModelStore(
    private val directory: File,
    private val transport: SasayakiModelTransport,
    private val ioDispatcher: CoroutineDispatcher,
    private val files: List<SasayakiModelFile> = ReazonSpeechModelCatalog.files,
    private val readOnly: Boolean = false,
) {
    private val mutex = Mutex()

    init {
        require(files.all { it.name.isNotBlank() && it.name != "." && it.name != ".." && '/' !in it.name && '\\' !in it.name })
        require(files.map { it.name }.distinct().size == files.size)
    }

    suspend fun missingBytes(): Long = mutex.withLock {
        withContext(ioDispatcher) { files.filterNot { valid(File(directory, it.name), it) }.sumOf { it.bytes } }
    }

    suspend fun ensure(onDownloadRequired: suspend (Long) -> Unit, onProgress: suspend (Double) -> Unit): File = mutex.withLock {
        withContext(ioDispatcher) {
            try {
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create model directory")
                // A killed process cannot execute the download's finally block.
                // Only this serialized model store owns these temporary files.
                directory.listFiles()?.filter { file ->
                    file.name.endsWith(".part") && files.any { file.name.startsWith(it.name) }
                }?.forEach { file -> if (!file.delete()) throw IOException("Cannot remove incomplete model") }
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
                    val partial = File.createTempFile(spec.name, ".part", directory)
                    try {
                        val response = transport.open(spec.url)
                        coroutineScope {
                            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                                try { awaitCancellation() } finally { response.close() }
                            }
                            try {
                                if (response.length >= 0 && response.length != spec.bytes) throw IOException("Model size mismatch")
                                copyVerified(response.input, partial, spec) { bytes -> onProgress((completed + bytes) / total) }
                            } finally {
                                response.close()
                                cancellation.cancel()
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        Files.move(partial.toPath(), File(directory, spec.name).toPath(),
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        completed += spec.bytes
                    } finally {
                        partial.delete()
                    }
                }
                onProgress(1.0)
                directory
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                throw error
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

    private suspend fun copyVerified(input: InputStream, target: File, spec: SasayakiModelFile, progress: suspend (Long) -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        FileOutputStream(target).use { output ->
            // Android dynamic-code guidance: remove write permission before writing via the open descriptor.
            if (readOnly && !target.setReadOnly()) throw IOException("Cannot protect runtime file")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                if (total > spec.bytes) throw IOException("Model exceeds expected size")
                digest.update(buffer, 0, count)
                output.write(buffer, 0, count)
                progress(total)
            }
            output.fd.sync()
        }
        if (total != spec.bytes || digest.hex() != spec.sha256) throw IOException("Model integrity verification failed")
    }

    private fun MessageDigest.hex() = digest().joinToString("") { "%02x".format(it) }
}
