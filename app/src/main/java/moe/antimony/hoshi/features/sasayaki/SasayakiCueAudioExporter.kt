package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiMatch

import android.content.Context
import android.net.Uri
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
