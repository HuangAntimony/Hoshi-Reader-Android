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
        val cueKey = cue.id.hashCode().toLong().and(0xffffffffL)
        val output = File.createTempFile("hoshi_sasayaki_${cueKey}_", ".aac", outputRoot)
        val transformerOutput = outputRoot.resolve("${output.name}.tmp.m4a")
        val completed = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val transformerRef = AtomicReference<Transformer?>(null)
        val done = CountDownLatch(1)
        val exportThread = HandlerThread("HoshiSasayakiCueExport")
        var exportHandler: Handler? = null
        var keepOutput = false

        try {
            exportThread.start()
            val handler = Handler(exportThread.looper).also { exportHandler = it }
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

            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) return@runCatching null
            if (!completed.get() || failure.get() != null) return@runCatching null
            if (!AacAdtsCueAudioRewriter.rewrite(input = transformerOutput, output = output)) return@runCatching null
            keepOutput = output.length() > 0L
            output.takeIf { keepOutput }
        } finally {
            // The muxer may still be opening its path after a timeout.
            val cleanupPosted = done.count > 0L && exportHandler?.post {
                try {
                    transformerRef.get()?.cancel()
                } finally {
                    transformerOutput.delete()
                }
            } == true
            if (!cleanupPosted) transformerOutput.delete()
            exportThread.quitSafely()
            if (!keepOutput) output.delete()
        }
    }.getOrElse { error ->
        if (error is InterruptedException) throw error
        null
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

    private companion object {
        const val ExportTimeoutMs = 30_000L
    }
}
