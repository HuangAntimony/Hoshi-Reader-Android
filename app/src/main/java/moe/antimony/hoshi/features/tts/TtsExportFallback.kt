package moe.antimony.hoshi.features.tts

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class TtsAudioFormatMismatch : Exception()

internal fun interface TtsExportFallback {
    suspend fun join(parts: List<File>, target: File)
}

internal suspend fun joinTtsAudio(parts: List<File>, target: File, fallback: TtsExportFallback,
    dispatcher: CoroutineDispatcher = Dispatchers.IO): File {
    return try {
        withTtsExportFile(target, dispatcher) {
            if (target.extension == "mp3") TtsMp3.concatenate(parts, it) else TtsWav.concatenate(parts, it)
        }
    } catch (_: TtsAudioFormatMismatch) {
        withTtsExportFile(File(target.parentFile, "${target.nameWithoutExtension}.m4a"), dispatcher) { fallback.join(parts, it) }
    }
}

/** Only incompatible voice formats take this path; ordinary MP3/WAV exports stay lossless. */
@OptIn(UnstableApi::class)
class AndroidTtsExportFallback @Inject constructor(@param:ApplicationContext private val context: Context) : TtsExportFallback {
    override suspend fun join(parts: List<File>, target: File) {
        var transformer: Transformer? = null
        try {
            withTimeout(30_000) {
                withContext(Dispatchers.Main.immediate) {
                    val items = parts.map { file ->
                        val mono = ChannelMixingAudioProcessor().apply {
                            putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(1, 1))
                            putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(2, 1))
                        }
                        val sampleRate = SonicAudioProcessor().apply { setOutputSampleRateHz(24000) }
                        EditedMediaItem.Builder(MediaItem.fromUri(file.toURI().toString())).setRemoveVideo(true)
                            .setEffects(Effects(listOf(mono, sampleRate), emptyList())).build()
                    }
                    val sequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO)).addItems(items).build()
                    val composition = Composition.Builder(sequence).setTransmuxAudio(false).build()
                    suspendCancellableCoroutine<Unit> { continuation ->
                        transformer = Transformer.Builder(context).setAudioMimeType(MimeTypes.AUDIO_AAC)
                            .setMuxerFactory(FrameworkMuxer.Factory())
                            .addListener(object : Transformer.Listener {
                                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                    if (continuation.isActive) continuation.resume(Unit)
                                }
                                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                    if (continuation.isActive) continuation.resumeWithException(TtsException(TtsFailure.AudioUnavailable))
                                }
                            }).build()
                        transformer?.start(composition, target.absolutePath)
                    }
                }
            }
            if (!target.isFile || target.length() == 0L) throw TtsException(TtsFailure.AudioUnavailable)
        } catch (_: TimeoutCancellationException) {
            throw TtsException(TtsFailure.Timeout)
        } finally {
            // Complete cancellation on Transformer's looper before the caller removes inputs/output.
            withContext(NonCancellable + Dispatchers.Main.immediate) { transformer?.cancel() }
        }
    }
}
