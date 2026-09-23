package moe.antimony.hoshi.features.sasayaki.transcription

import moe.antimony.hoshi.features.sasayaki.SasayakiToken
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Bounded lookahead with ordered publication. The scope joins all native calls
 * on cancellation/failure before the backend can release the shared models. */
internal suspend fun transcribeSpeechAudio(
    from: Double,
    duration: Double,
    audioFrom: Double,
    parallelism: Int,
    decode: suspend (suspend (AudioSamples) -> Unit) -> Unit,
    probability: (FloatArray) -> Float,
    recognize: suspend (FloatArray) -> RecognitionTokens,
    onBatch: suspend (SasayakiTranscriptionBatch) -> Unit,
    previousTokens: List<SasayakiToken> = emptyList(),
) = coroutineScope {
    require(parallelism in 1..3)
    val results = Channel<Deferred<SasayakiRecognitionBatch>>(parallelism + 1)
    val slots = Semaphore(parallelism)
    launch {
        val pipeline = SasayakiSpeechPipeline(from, duration, probability, recognize,
            schedule = { work ->
                results.send(async { slots.withPermit { work() } })
            }, audioFrom = audioFrom)
        try {
            feedTranscriptionAudio(decode, pipeline::accept)
            pipeline.finish()
        } finally { results.close() }
    }
    val stitcher = SasayakiRecognitionStitcher(from, previousTokens)
    var publishedThrough = from
    for (result in results) {
        val batch = result.await()
        currentCoroutineContext().ensureActive()
        if (batch.through > publishedThrough) {
            onBatch(stitcher.accept(batch))
            publishedThrough = batch.through
        }
    }
}
