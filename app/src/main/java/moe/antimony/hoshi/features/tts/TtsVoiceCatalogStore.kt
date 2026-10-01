package moe.antimony.hoshi.features.tts

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Long-lived online voice catalogs, read/written only on the engine's IO dispatcher. */
@Singleton
class TtsVoiceCatalogStore internal constructor(private val directory: File) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "tts/voices"))
    @Serializable private data class Entry(val configuration: String, val voices: List<TtsVoiceInfo>)
    private val json = Json { ignoreUnknownKeys = true }
    private fun file(source: TtsOnlineSource) = File(directory, "${source.name}.json")
    private fun fingerprint(config: TtsOnlineConfig): String = MessageDigest.getInstance("SHA-256")
        .digest("${config.region}\u0000${config.encryptedKey}".toByteArray()).joinToString("") { "%02x".format(it) }

    internal fun read(source: TtsOnlineSource, config: TtsOnlineConfig): List<TtsVoiceInfo>? = try {
        json.decodeFromString<Entry>(file(source).readText()).takeIf {
            it.configuration == fingerprint(config) && it.voices.isNotEmpty()
        }?.voices
    } catch (_: IOException) { null } catch (_: SerializationException) { null } catch (_: IllegalArgumentException) { null }

    internal fun write(source: TtsOnlineSource, config: TtsOnlineConfig, voices: List<TtsVoiceInfo>) {
        val temporary = File(directory, "${UUID.randomUUID()}.tmp")
        try {
            directory.mkdirs()
            temporary.writeText(json.encodeToString(Entry(fingerprint(config), voices)))
            try {
                Files.move(temporary.toPath(), file(source).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file(source).toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (_: IOException) {
            // A cache write failure must not discard successfully fetched voices.
        } finally { temporary.delete() }
    }
}
