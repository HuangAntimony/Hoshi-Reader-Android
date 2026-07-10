package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiMatch

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.FrameworkMuxer
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@OptIn(UnstableApi::class)
internal class SasayakiCueAudioExporter(
    context: Context,
    private val outputRoot: File = File(context.applicationContext.cacheDir, "anki-media/sasayaki"),
    private val timeoutMs: Long = ExportTimeoutMs,
) {
    private val appContext = context.applicationContext

    fun export(
        source: SasayakiPlaybackSource,
        cue: SasayakiMatch,
        range: SasayakiCueAudioRange,
    ): File? {
        return if (Build.VERSION.SDK_INT >= 31) {
            exportWithTransformer(source, cue, range)
        } else {
            exportWithCodecs(source, cue, range)
        }
    }

    private fun exportWithTransformer(
        source: SasayakiPlaybackSource,
        cue: SasayakiMatch,
        range: SasayakiCueAudioRange,
    ): File? = runCatching {
        outputRoot.mkdirs()
        val output = outputRoot.resolve(outputFileName(cue))
        val transformerOutput = outputRoot.resolve("${output.name}.tmp.m4a")
        if (output.exists()) output.delete()
        if (transformerOutput.exists()) transformerOutput.delete()
        val completed = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val transformerRef = AtomicReference<Transformer?>(null)
        val done = CountDownLatch(1)
        val exportThread = HandlerThread("HoshiSasayakiCueExport").also { it.start() }
        val handler = Handler(exportThread.looper)

        try {
            handler.post {
                runCatching {
                    val transformer = Transformer.Builder(appContext)
                        .setLooper(exportThread.looper)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setMuxerFactory(FrameworkMuxer.Factory())
                        .addListener(
                            object : Transformer.Listener {
                                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                    completed.set(true)
                                    done.countDown()
                                }

                                override fun onError(
                                    composition: Composition,
                                    exportResult: ExportResult,
                                    exportException: ExportException,
                                ) {
                                    failure.set(exportException)
                                    done.countDown()
                                }
                            },
                        )
                        .build()
                    transformerRef.set(transformer)
                    transformer.start(editedMediaItem(source = source, range = range), transformerOutput.absolutePath)
                }.onFailure {
                    failure.set(it)
                    done.countDown()
                }
            }

            val finished = done.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                handler.post { transformerRef.get()?.cancel() }
                output.delete()
                transformerOutput.delete()
                return@runCatching null
            }
            if (!completed.get() || failure.get() != null) {
                output.delete()
                transformerOutput.delete()
                return@runCatching null
            }
            if (!AacAdtsCueAudioRewriter.rewrite(input = transformerOutput, output = output)) {
                output.delete()
                transformerOutput.delete()
                return@runCatching null
            }
            transformerOutput.delete()
            output.takeIf { it.isFile && it.length() > 0L }
        } finally {
            exportThread.quitSafely()
            transformerOutput.delete()
        }
    }.getOrNull()

    @SuppressLint("WrongConstant")
    private fun exportWithCodecs(
        source: SasayakiPlaybackSource,
        cue: SasayakiMatch,
        range: SasayakiCueAudioRange,
    ): File? = runCatching {
        outputRoot.mkdirs()
        val output = outputRoot.resolve(outputFileName(cue))
        if (output.exists()) output.delete()

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        try {
            extractor.setDataSource(appContext, source.uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                val format = extractor.getTrackFormat(i)
                format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@runCatching null
            extractor.selectTrack(trackIndex)
            val trackFormat = extractor.getTrackFormat(trackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: return@runCatching null

            val startUs = (range.startTime * 1_000_000.0).toLong()
            val endUs = (range.endTime * 1_000_000.0).toLong() + 500_000L
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(trackFormat, null, null, 0)
            decoder.start()

            val aacOut = java.io.ByteArrayOutputStream()
            var sampleRate = 0
            var channels = 0
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val inputBuf = decoder.getInputBuffer(inputIndex) ?: return@runCatching null
                        inputBuf.clear()
                        val sampleSize = extractor.readSampleData(inputBuf, 0)
                        if (sampleSize < 0 || extractor.sampleTime > endUs) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, extractor.sampleFlags)
                            extractor.advance()
                        }
                    }
                }

                val decBufInfo = android.media.MediaCodec.BufferInfo()
                val decIndex = decoder.dequeueOutputBuffer(decBufInfo, 10_000L)
                when {
                    decIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = decoder.outputFormat
                        sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (encoder == null) {
                            val aacFormat = android.media.MediaFormat.createAudioFormat(
                                MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels
                            )
                            aacFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
                                android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                            aacFormat.setInteger(MediaFormat.KEY_BIT_RATE, 64000)
                            aacFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8 * 1024)
                            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                            encoder.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                            encoder.start()
                        }
                    }
                    decIndex >= 0 -> {
                        val outBuf = decoder.getOutputBuffer(decIndex) ?: return@runCatching null
                        outBuf.position(decBufInfo.offset)
                        outBuf.limit(decBufInfo.offset + decBufInfo.size)
                        val pcm = ByteArray(decBufInfo.size)
                        outBuf.get(pcm)
                        val isEos = decBufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0

                        val enc = encoder ?: return@runCatching null
                        val encInputIndex = enc.dequeueInputBuffer(10_000L)
                        if (encInputIndex >= 0) {
                            val encInputBuf = enc.getInputBuffer(encInputIndex) ?: return@runCatching null
                            encInputBuf.clear()
                            encInputBuf.put(pcm)
                            enc.queueInputBuffer(encInputIndex, 0, pcm.size, decBufInfo.presentationTimeUs, 0)
                        }

                        val encBufInfo = android.media.MediaCodec.BufferInfo()
                        var encIndex = enc.dequeueOutputBuffer(encBufInfo, 10_000L)
                        while (encIndex >= 0) {
                            if (encBufInfo.size > 0 && encBufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val encOutBuf = enc.getOutputBuffer(encIndex) ?: return@runCatching null
                                encOutBuf.position(encBufInfo.offset)
                                encOutBuf.limit(encBufInfo.offset + encBufInfo.size)
                                val frame = ByteArray(encBufInfo.size)
                                encOutBuf.get(frame)
                                aacOut.write(aacAdtsHeader(frame.size, sampleRate, channels))
                                aacOut.write(frame)
                            }
                            enc.releaseOutputBuffer(encIndex, false)
                            encIndex = enc.dequeueOutputBuffer(encBufInfo, 0)
                        }

                        decoder.releaseOutputBuffer(decIndex, false)
                        if (isEos) {
                            drainEncoder(enc, aacOut, sampleRate, channels)
                            outputDone = true
                        }
                    }
                    decIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputDone) {
                            val enc = encoder
                            if (enc != null) drainEncoder(enc, aacOut, sampleRate, channels)
                            outputDone = true
                        }
                    }
                }
            }

            if (aacOut.size() == 0) {
                output.delete()
                return@runCatching null
            }

            output.writeBytes(aacOut.toByteArray())
            output.takeIf { it.isFile && it.length() > 0L }
        } finally {
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }.getOrNull()

    private fun aacAdtsHeader(frameSize: Int, sampleRate: Int, channels: Int): ByteArray {
        val sampleRateIndex = when (sampleRate) {
            96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3
            44100 -> 4; 32000 -> 5; 24000 -> 6; 22050 -> 7
            16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11
            7350 -> 12; else -> 4
        }
        val profile = 1
        val totalLen = frameSize + 7
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(),
            ((profile and 0x03) shl 6 or ((sampleRateIndex and 0x0F) shl 2) or ((channels shr 2) and 0x01)).toByte(),
            (((channels and 0x03) shl 6) or ((totalLen shr 11) and 0x03)).toByte(),
            ((totalLen shr 3) and 0xFF).toByte(),
            (((totalLen and 0x07) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }

    private fun drainEncoder(enc: MediaCodec, aacOut: java.io.ByteArrayOutputStream, sampleRate: Int, channels: Int) {
        val bufInfo = android.media.MediaCodec.BufferInfo()
        var timeoutExtend = 3
        while (timeoutExtend > 0) {
            val index = enc.dequeueOutputBuffer(bufInfo, 50_000L)
            when (index) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> timeoutExtend--
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> timeoutExtend = 3
                else -> if (index >= 0) {
                    timeoutExtend = 3
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    if (bufInfo.size > 0 && bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val buf = enc.getOutputBuffer(index) ?: return
                        buf.position(bufInfo.offset)
                        buf.limit(bufInfo.offset + bufInfo.size)
                        val frame = ByteArray(bufInfo.size)
                        buf.get(frame)
                        aacOut.write(aacAdtsHeader(frame.size, sampleRate, channels))
                        aacOut.write(frame)
                    }
                    enc.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    private fun editedMediaItem(
        source: SasayakiPlaybackSource,
        range: SasayakiCueAudioRange,
    ): EditedMediaItem {
        val mediaItem = MediaItem.Builder()
            .setUri(source.uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(range.startPositionMs)
                    .setEndPositionMs(range.endPositionMs)
                    .build(),
            )
            .build()
        return EditedMediaItem.Builder(mediaItem)
            .setRemoveVideo(true)
            .build()
    }

    private val SasayakiPlaybackSource.uri: Uri
        get() = when (this) {
            is SasayakiPlaybackSource.ExternalUri -> uri
            is SasayakiPlaybackSource.PrivateFile -> Uri.fromFile(file)
        }

    private val SasayakiCueAudioRange.startPositionMs: Long
        get() = (startTime * 1000.0).toLong().coerceAtLeast(0L)

    private val SasayakiCueAudioRange.endPositionMs: Long
        get() = (endTime * 1000.0).toLong().coerceAtLeast(startPositionMs + 1L)

    private fun outputFileName(cue: SasayakiMatch): String =
        "hoshi_sasayaki_${cue.id.hashCode().toLong().and(0xffffffffL)}.aac"

    private companion object {
        const val ExportTimeoutMs = 30_000L
    }
}
