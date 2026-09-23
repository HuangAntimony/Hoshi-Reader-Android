package moe.antimony.hoshi.features.sasayaki.transcription

import java.io.IOException
import kotlin.math.ceil
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.antimony.hoshi.features.sasayaki.SasayakiToken

internal data class RecognitionTokens(val text: Array<String>, val timestamps: FloatArray)

/** A decoded segment owns token starts before its unpadded end. The next segment
 * may reuse audio context, but may not emit tokens before the committed time. */
internal fun projectRecognitionTokens(
    result: RecognitionTokens,
    segmentStart: Double,
    segmentEnd: Double,
    committed: Double,
    through: Double,
): List<SasayakiToken> {
    if (result.text.size != result.timestamps.size) throw IOException("ASR result has no token timestamps")
    return result.text.indices.mapNotNull { index ->
        val text = result.text[index].replace('▁', ' ')
        val offset = result.timestamps[index].toDouble()
        val start = segmentStart + offset
        // RNN-T can emit several characters on the same acoustic frame. Give
        // those characters the interval to the next distinct timestamp.
        val next = (index + 1 until result.timestamps.size)
            .firstOrNull { result.timestamps[it].toDouble() > offset }
            ?.let { segmentStart + result.timestamps[it] } ?: segmentEnd
        if (text.isBlank() || text == "<blk>" || text == "<unk>" || !start.isFinite() ||
            !next.isFinite() || start < committed || start >= through || offset < 0
        ) return@mapNotNull null
        SasayakiToken(text, start, minOf(maxOf(start, next), through))
    }
}

internal class SasayakiSpeechPipeline(
    from: Double,
    private val duration: Double,
    private val probability: (FloatArray) -> Float,
    private val recognize: suspend (FloatArray) -> RecognitionTokens,
    private val schedule: suspend (suspend () -> SasayakiTranscriptionBatch) -> Unit,
    audioFrom: Double = from,
) {
    private val originSample = ceil(audioFrom * 16000 - 1e-8).toLong()
    private val history = AudioSampleHistory(24 * 16000)
    private val segmenter = SpeechSegmenter()
    private val pending = ArrayDeque<SpeechBounds>()
    // Producer boundary only; the ordered consumer owns the persisted checkpoint.
    private var scheduledThrough = from
    private val frame = FloatArray(512)
    private var frameSize = 0
    private var receivedSamples = 0L

    suspend fun accept(chunk: AudioSamples) {
        check(chunk.startSample == originSample + receivedSamples) { "Non-contiguous resampled audio" }
        receivedSamples += chunk.samples.size
        var offset = 0
        while (offset < chunk.samples.size) {
            currentCoroutineContext().ensureActive()
            val count = minOf(frame.size - frameSize, chunk.samples.size - offset)
            chunk.samples.copyInto(frame, frameSize, offset, offset + count)
            frameSize += count
            offset += count
            if (frameSize == frame.size) processFrame(frame.size)
        }
    }

    suspend fun finish() {
        if (frameSize > 0) {
            frame.fill(0f, frameSize)
            processFrame(frameSize)
        }
        segmenter.finish()?.let(pending::addLast)
        drain(finishing = true)
        val decodedEnd = (originSample + receivedSamples) / 16000.0
        if (receivedSamples == 0L || decodedEnd < duration - 1.5) throw IOException("Audio ended before its declared duration")
        emit(emptyList(), duration)
    }

    private suspend fun processFrame(count: Int) {
        history.append(if (count == frame.size) frame else frame.copyOf(count))
        segmenter.accept(probability(frame), count)?.let(pending::addLast)
        frameSize = 0
        drain(finishing = false)
        val safeSample = minOf(segmenter.safeThroughSample, pending.firstOrNull()?.paddedStart ?: Long.MAX_VALUE)
        val through = minOf(duration, (originSample + safeSample) / 16000.0)
        if (through - scheduledThrough >= 5.0) emit(emptyList(), through)
    }

    private suspend fun drain(finishing: Boolean) {
        while (pending.isNotEmpty()) {
            val bounds = pending.first()
            if (!finishing && bounds.paddedEnd > history.endSample) return
            currentCoroutineContext().ensureActive()
            val end = minOf(bounds.paddedEnd, history.endSample)
            val scheduledThroughSample = ceil(scheduledThrough * 16000 - 1e-8).toLong() - originSample
            // Between distinct utterances, leading context can overlap the prior
            // segment's scheduled silence. ASR may timestamp the new word at the
            // start of that context, causing it to be mistaken for an old token.
            // Trim only before new speech; hard cuts and in-speech resume retain
            // their half-second context and timestamp-based deduplication.
            val start = if (bounds.start > scheduledThroughSample) {
                maxOf(bounds.paddedStart, scheduledThroughSample)
            } else {
                (bounds.start - SPEECH_CONTINUATION_LEAD_SAMPLES).coerceAtLeast(0)
            }
            val samples = history.read(start, end)
            // A normal silence boundary owns its trailing context, which can
            // contain the model's slightly delayed final token. Hard cuts must
            // leave that context to the next segment.
            val ownedEnd = if (bounds.hardCut) bounds.end else end
            val through = minOf(duration, (originSample + ownedEnd) / 16000.0)
            val previous = scheduledThrough
            if (through > previous) {
                schedule {
                    val result = recognize(samples)
                    currentCoroutineContext().ensureActive()
                    val tokens = projectRecognitionTokens(result,
                        segmentStart = (originSample + start) / 16000.0,
                        segmentEnd = (originSample + end) / 16000.0,
                        committed = previous, through = through)
                    SasayakiTranscriptionBatch(tokens, through)
                }
                scheduledThrough = through
            }
            pending.removeFirst()
        }
    }

    private suspend fun emit(tokens: List<SasayakiToken>, through: Double) {
        if (through <= scheduledThrough) return
        schedule { SasayakiTranscriptionBatch(tokens, through) }
        scheduledThrough = through
    }
}
