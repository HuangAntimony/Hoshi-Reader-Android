package moe.antimony.hoshi.features.audio

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class LocalAudioBinding(
    val generation: String,
    val displayName: String,
    val sizeBytes: Long,
    val uri: String? = null,
    val privateFileName: String? = null,
    val fingerprint: String = "",
    val ownsGrant: Boolean = false,
    val sources: LocalAudioSourceConfig = LocalAudioSourceConfig(),
) {
    fun info() = LocalAudioDatabaseInfo(displayName, sizeBytes, uri != null, generation)
}

@Serializable
internal data class LocalAudioStoredState(
    val version: Int = 1,
    val active: LocalAudioBinding? = null,
    val retainedPrivate: LocalAudioBinding? = null,
)

/** References and source settings publish together; restored external references require a new URI grant. */
internal class LocalAudioBindingStore(directory: File) {
    private val file = directory.resolve("database.json")
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun read(): LocalAudioStoredState? {
        if (!file.exists()) return null
        val state = json.decodeFromString<LocalAudioStoredState>(file.readText())
        if (state.version != 1) throw IOException("Unsupported local audio binding version ${state.version}.")
        listOfNotNull(state.active, state.retainedPrivate).forEach { binding ->
            if ((binding.uri == null) == (binding.privateFileName == null) || binding.generation.isBlank()) {
                throw IOException("Invalid local audio binding.")
            }
            binding.privateFileName?.let { name ->
                if (!name.matches(Regex("android(?:-[a-f0-9-]+)?\\.db"))) {
                    throw IOException("Invalid private audio database filename.")
                }
            }
        }
        if (state.retainedPrivate?.uri != null) throw IOException("Invalid retained private database.")
        return state
    }

    fun write(state: LocalAudioStoredState) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(json.encodeToString(state).toByteArray())
                output.fd.sync()
            }
            moveLocalAudioFile(temp, file)
        } finally {
            temp.delete()
        }
    }
}

internal fun moveLocalAudioFile(source: File, target: File) {
    try {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
