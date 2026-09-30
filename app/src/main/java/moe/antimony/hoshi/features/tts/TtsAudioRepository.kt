package moe.antimony.hoshi.features.tts

import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.di.CacheDir
import moe.antimony.hoshi.di.IoDispatcher

internal interface TtsAudioSource {
    suspend fun audio(sentence: TtsSentence, bookId: String?, preferences: TtsPreferences): List<File>
    suspend fun clearPlaybackCache()
}

@Singleton
class TtsAudioRepository @Inject constructor(
    private val engine: TtsEngineRouter,
    @param:CacheDir private val cache: File,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : TtsAudioSource {
    private val lock = Mutex()
    private val files = linkedMapOf<String, List<File>>()
    override suspend fun audio(sentence: TtsSentence, bookId: String?, preferences: TtsPreferences): List<File> =
        withContext(ioDispatcher) {
            lock.withLock {
                val spoken = TtsText.spoken(sentence, bookId, preferences.words)
                val identity = "${preferences.settings}\u0000$spoken"
                val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
                files[key]?.takeIf { list -> list.all { it.isFile } }?.let { return@withLock it }
                engine.synthesize(spoken, preferences.settings, File(cache, "tts/playback")).also {
                    files[key] = it
                    // Keep references bounded. Files are pruned after they are no longer part of an active session.
                    if (files.size > 32) files.remove(files.keys.first())?.forEach { old -> old.delete() }
                }
            }
        }

    override suspend fun clearPlaybackCache() = lock.withLock {
        withContext(ioDispatcher) {
            files.clear()
            File(cache, "tts/playback").deleteRecursively()
            Unit
        }
    }

    /** Export has its own lifetime so changing books cannot delete Anki's pending upload. */
    suspend fun export(sentence: TtsSentence, bookId: String?, preferences: TtsPreferences): File =
        withTtsExportFile(File(cache, "tts/export/${java.util.UUID.randomUUID()}.${engine.extension(preferences.settings)}"), ioDispatcher) { target ->
            val parts = engine.synthesize(TtsText.spoken(sentence, bookId, preferences.words), preferences.settings, File(cache, "tts/export"))
            try {
                if (target.extension == "mp3") TtsMp3.concatenate(parts, target) else TtsWav.concatenate(parts, target)
            } finally { parts.forEach { it.delete() } }
        }
}

/** Own the final file outside the dispatcher boundary, including cancelled return delivery. */
internal suspend fun withTtsExportFile(
    target: File,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    write: suspend (File) -> Unit,
): File {
    try {
        return withContext(dispatcher) { write(target); target }
    } catch (error: Throwable) {
        withContext(NonCancellable + dispatcher) { target.delete() }
        throw error
    }
}

/** Android synthesizeToFile produces PCM WAV. Preserve format and concatenate data chunks. */
internal object TtsWav {
    fun concatenate(parts: List<File>, target: File): File {
        if (parts.size == 1) { parts.single().copyTo(target, overwrite = true); return target }
        var format: ByteArray? = null
        val data = java.io.ByteArrayOutputStream()
        parts.forEach { file ->
            val bytes = file.readBytes()
            require(bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE")
            var cursor = 12
            while (cursor + 8 <= bytes.size) {
                val id = String(bytes, cursor, 4)
                val size = java.nio.ByteBuffer.wrap(bytes, cursor + 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                require(size >= 0 && cursor.toLong() + 8 + size <= bytes.size)
                val body = bytes.copyOfRange(cursor + 8, cursor + 8 + size)
                if (id == "fmt ") {
                    require(format == null || format.contentEquals(body))
                    format = body
                } else if (id == "data") data.write(body)
                cursor += 8 + size + size % 2
            }
        }
        val fmt = requireNotNull(format)
        fun java.io.OutputStream.le(value: Int) = write(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(value).array())
        target.outputStream().use { out ->
            out.write("RIFF".toByteArray()); out.le(20 + fmt.size + fmt.size % 2 + data.size() + data.size() % 2)
            out.write("WAVEfmt ".toByteArray()); out.le(fmt.size); out.write(fmt)
            if (fmt.size % 2 != 0) out.write(0)
            out.write("data".toByteArray()); out.le(data.size()); data.writeTo(out)
            if (data.size() % 2 != 0) out.write(0)
        }
        return target
    }
}
