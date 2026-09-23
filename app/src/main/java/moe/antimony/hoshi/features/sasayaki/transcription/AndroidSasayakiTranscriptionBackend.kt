package moe.antimony.hoshi.features.sasayaki.transcription

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.di.DefaultDispatcher

/** Native model lifetime is one active transcription, never a whole audiobook's
 * PCM. Calls are serialized so multiple readers cannot load concurrent 400MB sessions. */
@Singleton
internal class AndroidSasayakiTranscriptionBackend @Inject constructor(
    private val decoder: AndroidSasayakiAudioDecoder,
    private val models: SasayakiModelRepository,
    private val runtime: SasayakiRuntimeRepository,
    @param:DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : SasayakiTranscriptionBackend {
    private val mutex = Mutex()

    override suspend fun duration(source: String): Double = decoder.duration(source)

    override suspend fun transcribe(
        source: String,
        from: Double,
        onDownloadRequired: suspend (Long) -> Unit,
        onDownload: suspend (Double) -> Unit,
        onBatch: suspend (SasayakiTranscriptionBatch) -> Unit,
        parallelism: Int,
    ) = mutex.withLock {
        withContext(defaultDispatcher) {
            require(parallelism in 1..3)
            require(from.isFinite() && from >= 0)
            val duration = decoder.duration(source)
            if (from >= duration) {
                onBatch(SasayakiTranscriptionBatch(emptyList(), duration))
                return@withContext
            }
            val directories = prepareSasayakiResources(listOf(runtime.store(), models.store), onDownloadRequired, onDownload)
            currentCoroutineContext().ensureActive()
            try {
                runtime.load(directories[0])
                transcribeWithModels(source, from, duration, directories[1], parallelism, onBatch)
            } catch (error: LinkageError) {
                throw IOException("Native transcription runtime is unavailable", error)
            }
        }
    }

    private suspend fun transcribeWithModels(
        source: String,
        from: Double,
        duration: Double,
        directory: File,
        parallelism: Int,
        onBatch: suspend (SasayakiTranscriptionBatch) -> Unit,
    ) {
        val recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = TRANSCRIPTION_SAMPLE_RATE, featureDim = 80, dither = 0f),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(directory, "encoder.int8.onnx").absolutePath,
                    decoder = File(directory, "decoder.int8.onnx").absolutePath,
                    joiner = File(directory, "joiner.int8.onnx").absolutePath,
                ),
                tokens = File(directory, "tokens.txt").absolutePath,
                numThreads = 1,
                provider = "cpu",
                modelType = "transducer",
            ),
            decodingMethod = "greedy_search",
        ))
        try {
            currentCoroutineContext().ensureActive()
            val energy = SasayakiEnergyVad()
            // Intentional ASR context is distinct from decoder sync
            // preroll; already committed tokens are filtered below.
            val decodeFrom = maxOf(0.0, from - .5)
            transcribeSpeechAudio(from, duration, decodeFrom, parallelism,
                decode = { send -> decoder.decode(source, decodeFrom, send) },
                probability = energy::probability, recognize = { samples ->
                    currentCoroutineContext().ensureActive()
                    val stream = recognizer.createStream()
                    try {
                        stream.acceptWaveform(samples, TRANSCRIPTION_SAMPLE_RATE)
                        recognizer.decode(stream)
                        currentCoroutineContext().ensureActive()
                        val result = recognizer.getResult(stream)
                        RecognitionTokens(result.tokens, result.timestamps)
                    } finally { stream.release() }
            }, onBatch = onBatch)
        } finally { recognizer.release() }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SasayakiTranscriptionBackendModule {
    @Binds
    @Singleton
    abstract fun bindBackend(backend: AndroidSasayakiTranscriptionBackend): SasayakiTranscriptionBackend
}
