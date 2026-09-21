package moe.antimony.hoshi.features.sasayaki.transcription

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.di.IoDispatcher

internal class AndroidSasayakiAudioDecoder @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun duration(source: String): Double = withContext(ioDispatcher) {
        val extractor = open(source)
        try {
            val track = audioTrack(extractor)
            val format = extractor.getTrackFormat(track)
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0
            } else {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, localUri(source))
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.div(1000.0)
                        ?: throw IOException("Audio duration is unavailable")
                } finally { retriever.release() }
            }
            if (!duration.isFinite() || duration <= 0) throw IOException("Invalid audio duration")
            duration
        } finally { extractor.release() }
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
