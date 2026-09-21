package moe.antimony.hoshi.features.sasayaki.transcription

internal const val SPEECH_LEAD_SAMPLES = 8_000L
internal const val SPEECH_TAIL_SAMPLES = 4_000L

internal data class SpeechBounds(val start: Long, val end: Long, val hardCut: Boolean = false) {
    val paddedStart: Long get() = (start - SPEECH_LEAD_SAMPLES).coerceAtLeast(0)
    val paddedEnd: Long get() = end + SPEECH_TAIL_SAMPLES
}

/** Silero probabilities with explicit bounded segments; the native VAD's
 * maxSpeechDuration changes its threshold but does not impose a hard limit. */
internal class SpeechSegmenter {
    var position: Long = 0
        private set
    private var candidateStart: Long? = null
    private var speech = false
    private var silenceStart: Long? = null

    val safeThroughSample: Long
        get() = ((candidateStart ?: position) - SPEECH_LEAD_SAMPLES).coerceAtLeast(0)

    fun accept(probability: Float, count: Int): SpeechBounds? {
        require(count in 1..512 && probability.isFinite())
        val frameStart = position
        position += count
        if (!speech) {
            if (probability >= .5f) {
                if (candidateStart == null) candidateStart = frameStart
                if (position - candidateStart!! >= 4_000) speech = true
            } else {
                candidateStart = null
            }
        }
        if (!speech) return null
        if (probability < .35f) {
            if (silenceStart == null) silenceStart = frameStart
            if (position - silenceStart!! >= 8_000) {
                val result = SpeechBounds(candidateStart!!, silenceStart!!)
                candidateStart = null
                silenceStart = null
                speech = false
                return result
            }
        } else {
            silenceStart = null
        }
        if (position - candidateStart!! >= 320_000) {
            val end = silenceStart ?: position
            val result = SpeechBounds(candidateStart!!, end, hardCut = silenceStart == null)
            speech = silenceStart == null
            candidateStart = if (speech) position else null
            silenceStart = null
            return result
        }
        return null
    }

    fun finish(): SpeechBounds? {
        val result = if (speech && position > candidateStart!!) SpeechBounds(candidateStart!!, silenceStart ?: position) else null
        candidateStart = null
        silenceStart = null
        speech = false
        return result
    }
}

/** Fixed-size PCM history supporting ASR's leading and trailing context. */
internal class AudioSampleHistory(private val capacity: Int) {
    private val samples = FloatArray(capacity)
    var endSample: Long = 0
        private set
    val startSample: Long get() = (endSample - capacity).coerceAtLeast(0)

    fun append(input: FloatArray) {
        input.forEach { samples[(endSample++ % capacity).toInt()] = it }
    }

    fun read(from: Long, through: Long): FloatArray {
        require(from >= startSample && through <= endSample && from <= through) { "Speech exceeds bounded audio history" }
        return FloatArray((through - from).toInt()) { samples[((from + it) % capacity).toInt()] }
    }
}
