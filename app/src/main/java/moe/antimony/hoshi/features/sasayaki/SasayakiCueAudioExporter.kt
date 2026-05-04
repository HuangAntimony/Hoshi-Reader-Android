package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiMatch

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.math.max

internal object SasayakiCueAudioExporter {
    fun export(
        context: Context,
        source: SasayakiPlaybackSource,
        cue: SasayakiMatch,
        range: SasayakiCueAudioRange,
        outputDir: File,
    ): File? = runCatching {
        outputDir.mkdirs()
        val output = outputDir.resolve("hoshi_sasayaki_${cue.id.hashCode().toLong().and(0xffffffffL)}.m4a")
        if (output.exists()) output.delete()
        val localSource = source.localExtractorFile(context = context, outputDir = outputDir)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(localSource.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return@runCatching null
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val startUs = (range.startTime * 1_000_000).toLong().coerceAtLeast(0L)
            val endUs = (range.endTime * 1_000_000).toLong().coerceAtLeast(startUs + 1L)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).use { muxer ->
                val muxerTrack = muxer.addTrack(format)
                muxer.start()
                val bufferSize = max(
                    64 * 1024,
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                    } else {
                        0
                    },
                )
                val buffer = ByteBuffer.allocateDirect(bufferSize)
                val info = android.media.MediaCodec.BufferInfo()
                var wroteSample = false
                var previousSampleTime = Long.MIN_VALUE
                while (true) {
                    val sampleTime = extractor.sampleTime
                    if (sampleTime < 0 || sampleTime > endUs) break
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    if (sampleTime >= startUs) {
                        info.set(0, size, sampleTime - startUs, 0)
                        muxer.writeSampleData(muxerTrack, buffer, info)
                        wroteSample = true
                    }
                    if (!extractor.advance() || extractor.sampleTime == previousSampleTime) break
                    previousSampleTime = sampleTime
                }
                if (!wroteSample) {
                    output.delete()
                    return@runCatching null
                }
                runCatching { muxer.stop() }.getOrElse {
                    output.delete()
                    return@runCatching null
                }
            }
        } finally {
            extractor.release()
        }
        output.takeIf { it.isFile && it.length() > 0L }
    }.getOrNull()

    private fun SasayakiPlaybackSource.localExtractorFile(context: Context, outputDir: File): File =
        when (this) {
            is SasayakiPlaybackSource.PrivateFile -> file
            is SasayakiPlaybackSource.ExternalUri -> {
                val input = context.contentResolver.openInputStream(uri) ?: error("Unable to open Sasayaki audio")
                val cacheFile = outputDir.resolve("sasayaki_export_source_${uri.toString().hashCode().toLong().and(0xffffffffL)}")
                input.useTo(cacheFile)
                cacheFile
            }
        }

    private fun InputStream.useTo(file: File) {
        use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }
}

private inline fun MediaMuxer.use(block: (MediaMuxer) -> Unit) {
    try {
        block(this)
    } finally {
        release()
    }
}
