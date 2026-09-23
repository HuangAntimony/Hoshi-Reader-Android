package moe.antimony.hoshi.features.sasayaki.transcription

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.features.sasayaki.inspectSeekableAudiobook
import moe.antimony.hoshi.features.sasayaki.openSeekableAudioChannel

internal class AndroidSasayakiAudioDecoder @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun duration(source: String): Double = withContext(ioDispatcher) {
        resolveSasayakiAudioDuration(
            extractorDuration = { extractorDuration(source) },
            containerDuration = { containerDuration(source) },
            metadataRetrieverDuration = { metadataRetrieverDuration(source) },
        )
    }

    private fun extractorDuration(source: String): Double? {
        val extractor = open(source)
        try {
            val track = audioTrack(extractor)
            val format = extractor.getTrackFormat(track)
            return if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0
            } else {
                null
            }
        } finally { extractor.release() }
    }

    private fun containerDuration(source: String): Double? {
        val uri = localUri(source)
        val channel = when (uri.scheme) {
            "content" -> context.contentResolver.openSeekableAudioChannel(uri)
            "file" -> uri.path?.let { Files.newByteChannel(File(it).toPath()) }
            else -> null
        } ?: return null
        return channel.use { inspectSeekableAudiobook(it).durationSeconds }
    }

    private fun metadataRetrieverDuration(source: String): Double? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, localUri(source))
            return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toDoubleOrNull()
                ?.div(1000.0)
        } finally {
            retriever.release()
        }
    }

    /** Native decoding owns a duplicate of the SAF descriptor and emits bounded
     * mono chunks on the original media clock. Cancellation closes both handles. */
    suspend fun decode(source: String, from: Double, onSamples: suspend (AudioSamples) -> Unit) = withContext(ioDispatcher) {
        require(from.isFinite() && from >= 0)
        val asset = context.contentResolver.openAssetFileDescriptor(localUri(source), "r")
            ?: throw IOException("Audio descriptor is unavailable")
        asset.use {
            val handle = NativeSasayakiAudio.open(it.parcelFileDescriptor.fd, it.startOffset, it.declaredLength, from)
            try {
                var start = kotlin.math.ceil(from * TRANSCRIPTION_SAMPLE_RATE - 1e-8).toLong()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val samples = NativeSasayakiAudio.read(handle) ?: break
                    onSamples(AudioSamples(start, samples))
                    start += samples.size
                }
            } finally { NativeSasayakiAudio.close(handle) }
        }
    }

    private fun open(source: String): MediaExtractor = MediaExtractor().also { extractor ->
        try { extractor.setDataSource(context, localUri(source), null) } catch (error: Exception) {
            extractor.release()
            throw error
        }
    }

    private fun localUri(source: String): Uri = Uri.parse(source).also {
        require(it.scheme == "content" || it.scheme == "file") { "Transcription requires a local audio URI" }
    }

    private fun audioTrack(extractor: MediaExtractor): Int = (0 until extractor.trackCount)
        .firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        ?: throw IOException("No audio track")
}
