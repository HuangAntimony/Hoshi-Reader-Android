package moe.antimony.hoshi.features.sasayaki.transcription

import com.sun.jna.Native
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.di.DefaultDispatcher
import moe.antimony.hoshi.features.sasayaki.SasayakiFailureKind
import moe.antimony.hoshi.features.sasayaki.SasayakiToken
import moe.antimony.hoshi.features.sasayaki.asSasayakiFailure
import kotlin.math.roundToLong

internal class NakuruTranscriptionBackend @Inject constructor(
    private val decoder: AndroidSasayakiAudioDecoder,
    private val resources: NakuruResources,
    private val runtime: SasayakiRuntimeRepository,
    @param:DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : SasayakiTranscriptionBackend {
    override suspend fun duration(source: String): Double = decoder.duration(source)

    override suspend fun transcribe(
        source: String,
        from: Double,
        onDownloadRequired: suspend (Long) -> Unit,
        onDownload: suspend (Double) -> Unit,
        onBatch: suspend (SasayakiTranscriptionBatch) -> Unit,
        parallelism: Int,
        previousTokens: List<SasayakiToken>,
    ) = withContext(defaultDispatcher) {
        try {
            val directories = try {
                prepareSasayakiResources(resources.stores() + runtime.store(includeInference = false), onDownloadRequired, onDownload)
            } catch (error: Exception) {
                throw error.asSasayakiFailure(SasayakiFailureKind.ModelResources)
            }
            val root = resources.root
            runtime.load(directories.last(), includeInference = false)
            val api = Native.load(File(root, "libnakuru.so").absolutePath, NakuruNative::class.java)
            currentCoroutineContext().ensureActive()
            val duration = decoder.duration(source)
            val count = (duration * TRANSCRIPTION_SAMPLE_RATE).roundToLong()
            val offset = (from * TRANSCRIPTION_SAMPLE_RATE).roundToLong().coerceAtMost(count)
            val origin = offset.toDouble() / TRANSCRIPTION_SAMPLE_RATE
            val recognizer = api.nakuru_create(File(root, "nakuru.pack").absolutePath, parallelism - 1)
                ?: throw IOException(api.nakuru_error())
            try {
                coroutineScope {
                    val context = currentCoroutineContext()
                    val chunks = Channel<AudioSamples>(16)
                    val decoding = launch {
                        try {
                            decoder.decode(source, origin) { chunks.send(it) }
                            chunks.close()
                        } catch (error: Exception) {
                            chunks.close(error)
                            throw error.asSasayakiFailure(SasayakiFailureKind.AudioSource)
                        }
                    }
                    val reader = NakuruAudioReader(chunks)
                    val failure = AtomicReference<Throwable?>()
                    fun callback(action: suspend () -> Unit): Int = try {
                        runBlocking(context[Job]!!) { ensureActive(); action() }
                        1
                    } catch (error: Throwable) {
                        failure.compareAndSet(null, error)
                        0
                    }
                    val read = NakuruNative.Read { _, start, size, destination ->
                        callback {
                            val samples = reader.read(offset + start.toLong(), size.toInt())
                            destination.write(0, samples, 0, samples.size)
                        }
                    }
                    val emit = NakuruNative.Emit { _, pointer ->
                        callback {
                            val segment = NakuruNative.Segment(pointer)
                            var position = 0L
                            val tokens = List(segment.count.toInt()) {
                                val token = NakuruNative.Token(segment.tokens!!.share(position))
                                position += token.size()
                                SasayakiToken(token.text!!.getString(0, "UTF-8"), origin + token.start, origin + token.end)
                            }
                            onBatch(SasayakiTranscriptionBatch(tokens, origin + segment.end))
                        }
                    }
                    try {
                        val result = api.nakuru_transcribe(recognizer, read, NakuruNative.Size(count - offset), emit, null)
                        failure.get()?.let { throw it }
                        context.ensureActive()
                        if (result != 1) throw IOException(if (result == -1) api.nakuru_error() else "Nakuru stopped before completion")
                        onBatch(SasayakiTranscriptionBatch(emptyList(), duration))
                    } finally {
                        decoding.cancel()
                        chunks.cancel()
                    }
                }
            } finally {
                api.nakuru_destroy(recognizer)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw error.asSasayakiFailure(SasayakiFailureKind.Recognition)
        } catch (error: LinkageError) {
            throw error.asSasayakiFailure(SasayakiFailureKind.Recognition)
        }
    }
}
