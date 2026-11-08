package moe.antimony.hoshi.features.audio

import java.io.Closeable
import java.io.IOException

/** Owns one read-only SQLite session and its duplicate of the caller's descriptor. */
internal class LocalAudioNativeDatabase private constructor(
    private var handle: Long,
    val sizeBytes: Long,
) : Closeable {
    @Synchronized
    fun queryEntries(term: String, reading: String): List<LocalAudioEntry> =
        LocalAudioNativeBridge.queryEntries(
            requireHandle(),
            term.encodeToByteArray(),
            reading.takeUnless(String::isBlank)?.encodeToByteArray(),
        ).map { row ->
            LocalAudioEntry(
                source = checkNotNull(row[0]),
                expression = checkNotNull(row[1]),
                reading = row[2],
                file = checkNotNull(row[3]),
                display = row[4].orEmpty(),
            )
        }

    @Synchronized
    fun querySources(): List<String> =
        LocalAudioNativeBridge.querySources(requireHandle())

    @Synchronized
    fun loadAudio(source: String, file: String): ByteArray? =
        LocalAudioNativeBridge.loadAudio(requireHandle(), source.encodeToByteArray(), file.encodeToByteArray())

    /** Physical read calls, bytes read, statements prepared, and queries run. */
    @Synchronized
    fun statistics(): LongArray =
        LocalAudioNativeBridge.statistics(requireHandle())

    @Synchronized
    override fun close() {
        val openHandle = handle
        handle = 0L
        if (openHandle != 0L) LocalAudioNativeBridge.close(openHandle)
    }

    private fun requireHandle(): Long =
        handle.takeUnless { it == 0L } ?: throw IOException("Local audio database is closed.")

    companion object {
        fun open(fd: Int, offset: Long, length: Long): LocalAudioNativeDatabase {
            val handle = try {
                LocalAudioNativeBridge.open(fd, offset, length)
            } catch (error: LinkageError) {
                throw IOException("Unable to load the local audio database engine.", error)
            }
            try {
                return LocalAudioNativeDatabase(handle, LocalAudioNativeBridge.sizeBytes(handle))
            } catch (error: Throwable) {
                LocalAudioNativeBridge.close(handle)
                throw error
            }
        }
    }
}

private object LocalAudioNativeBridge {
    init {
        System.loadLibrary("hoshilocalaudio_jni")
    }

    external fun open(fd: Int, offset: Long, length: Long): Long
    external fun sizeBytes(handle: Long): Long
    external fun queryEntries(handle: Long, term: ByteArray, reading: ByteArray?): List<Array<String?>>
    external fun querySources(handle: Long): List<String>
    external fun loadAudio(handle: Long, source: ByteArray, file: ByteArray): ByteArray?
    external fun statistics(handle: Long): LongArray
    external fun close(handle: Long)
}
