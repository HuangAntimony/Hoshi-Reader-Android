package moe.antimony.hoshi.features.sasayaki.transcription

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.R
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.ui.ErrorDiagnosticBuffer
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.UiText

/** Retired downloads only. Retry unfinished removals on the next process start. */
@Singleton
internal class LegacySasayakiResourceCleanup internal constructor(
    private val root: File,
    private val ioDispatcher: CoroutineDispatcher,
    private val events: ErrorDiagnosticBuffer,
    private val delete: (Path) -> Unit = { Files.deleteIfExists(it); Unit },
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(context.noBackupFilesDir, ioDispatcher, ErrorDiagnosticEvents.buffer)

    suspend fun clean() = withContext(ioDispatcher) {
        val context = currentCoroutineContext()
        fun attempt(path: Path, action: () -> Unit): Boolean {
            context.ensureActive()
            return try {
                action()
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: NoSuchFileException) {
                true
            } catch (error: Exception) {
                events.record(UiText.Resource(R.string.sasayaki_legacy_resources_cleanup, root.toPath().relativize(path).toString()), error)
                false
            }
        }
        fun removeTree(path: Path): Boolean {
            var childrenRemoved = true
            val visited = attempt(path) {
                if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
                    Files.newDirectoryStream(path).use { entries ->
                        for (child in entries) {
                            if (!removeTree(child)) childrenRemoved = false
                        }
                    }
                }
            }
            return visited && childrenRemoved && attempt(path) { delete(path) }
        }
        val models = root.toPath().resolve("SasayakiModels")
        if (Files.isDirectory(models, NOFOLLOW_LINKS)) {
            removeTree(models.resolve("reazonspeech-k2-v2-int8-v1"))
        }
        val runtime = root.toPath().resolve("SasayakiRuntime")
        if (Files.isDirectory(runtime, NOFOLLOW_LINKS)) attempt(runtime) {
            Files.newDirectoryStream(runtime).use { abis ->
                for (abi in abis) {
                    context.ensureActive()
                    if (!Files.isDirectory(abi, NOFOLLOW_LINKS)) continue
                    attempt(abi) {
                        Files.newDirectoryStream(abi).use { entries ->
                            for (entry in entries) {
                                context.ensureActive()
                                if (retiredLibrary.matches(entry.fileName.toString())) attempt(entry) { delete(entry) }
                            }
                        }
                    }
                }
            }
        }
        Unit
    }

    companion object {
        private val retiredLibrary = Regex(
            """(?:arm64-v8a|armeabi-v7a|x86_64)-[0-9a-f]{64}-(?:libonnxruntime|libsherpa-onnx-jni)\.so(?:[0-9]+\.part)?""",
        )
    }
}
