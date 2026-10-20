package moe.antimony.hoshi.features.tts

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Atomic disk storage owned by TtsVoiceCatalogCache; all calls run on IO. */
@Singleton
class TtsVoiceCatalogStore internal constructor(private val directory: File) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "tts/voices"))
    @Serializable private data class Entry(val configuration: String, val voices: List<TtsVoiceInfo>)
    private val json = Json { ignoreUnknownKeys = true }
    internal fun read(key: TtsVoiceCatalogKey): List<TtsVoiceInfo>? = try {
        json.decodeFromString<Entry>(File(directory, key.fileName).readText()).takeIf {
            it.configuration == key.configuration && (key.allowEmpty || it.voices.isNotEmpty())
        }?.voices
    } catch (_: IOException) { null } catch (_: SerializationException) { null } catch (_: IllegalArgumentException) { null }

    internal fun write(key: TtsVoiceCatalogKey, voices: List<TtsVoiceInfo>) {
        writeEntry(File(directory, key.fileName), Entry(key.configuration, voices))
    }
    private fun writeEntry(target: File, entry: Entry) {
        val temporary = File(directory, "${UUID.randomUUID()}.tmp")
        try {
            directory.mkdirs()
            temporary.writeText(json.encodeToString(entry))
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (_: IOException) {
            // A cache write failure must not discard successfully fetched voices.
        } finally { temporary.delete() }
    }
}
