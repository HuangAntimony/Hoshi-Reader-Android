package moe.antimony.hoshi.features.sasayaki

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.di.IoDispatcher

/** iOS-compatible sidecar, atomically replaced so interruption retains the last complete checkpoint. */
@Singleton
internal class SasayakiTranscriptStore @Inject constructor(
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun load(root: File): SasayakiTranscript? = withContext(ioDispatcher) {
        val file = root.resolve(FileName)
        if (!file.isFile) return@withContext null
        try {
            json.decodeFromString<SasayakiTranscript>(file.readText()).also(::validate)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) { null }
    }

    suspend fun save(root: File, transcript: SasayakiTranscript) = withContext(ioDispatcher) {
        validate(transcript)
        if (!root.isDirectory) throw IOException("Book was removed")
        val temporary = File.createTempFile(".sasayaki-transcript-", ".tmp", root)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(json.encodeToString(transcript).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(temporary.toPath(), root.resolve(FileName).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            Unit
        } finally { temporary.delete() }
    }

    suspend fun clear(root: File) = withContext(ioDispatcher) {
        Files.deleteIfExists(root.resolve(FileName).toPath())
        Unit
    }

    private fun validate(transcript: SasayakiTranscript) {
        require(transcript.duration.isFinite() && transcript.duration > 0)
        require(transcript.through.isFinite() && transcript.through in 0.0..transcript.duration)
        var previous = 0.0
        transcript.tokens.forEach { token ->
            require(token.text.isNotEmpty() && token.start.isFinite() && token.end.isFinite())
            require(token.start >= previous && token.end > token.start && token.end <= transcript.duration + 1.5)
            previous = token.start
        }
    }

    companion object { const val FileName = "sasayaki_transcript.json" }
}
