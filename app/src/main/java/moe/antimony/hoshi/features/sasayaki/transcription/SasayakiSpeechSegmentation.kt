package moe.antimony.hoshi.features.sasayaki.transcription

internal const val SPEECH_LEAD_SAMPLES = 16_000L
internal const val SPEECH_CONTINUATION_LEAD_SAMPLES = 8_000L
internal const val SPEECH_TAIL_SAMPLES = 4_000L

internal data class SpeechBounds(val start: Long, val end: Long, val hardCut: Boolean = false) {
    val paddedStart: Long get() = (start - SPEECH_LEAD_SAMPLES).coerceAtLeast(0)
    val paddedEnd: Long get() = end + SPEECH_TAIL_SAMPLES
}

/** Keep short pauses within speech; reject brief sounds only after a segment
 * ends, rather than requiring every opening frame to stay above threshold. */
internal class SpeechSegmenter {
    var position: Long = 0
        private set
    private var candidateStart: Long? = null
    private var speech = false
    private var silenceStart: Long? = null
    private var continuation = false

    val safeThroughSample: Long
        get() = ((candidateStart ?: position) - SPEECH_LEAD_SAMPLES).coerceAtLeast(0)

    fun accept(probability: Float, count: Int): SpeechBounds? {
        require(count in 1..512 && probability.isFinite())
        val frameStart = position
        position += count
        if (!speech) {
            if (probability >= .5f) {
                if (candidateStart == null) candidateStart = frameStart
                speech = true
            } else {
                candidateStart = null
            }
        }
        if (!speech) return null
        if (probability < .35f) {
            if (silenceStart == null) silenceStart = frameStart
            if (position - silenceStart!! >= 8_000) {
                val result = speechBounds(silenceStart!!)
                candidateStart = null
                silenceStart = null
                speech = false
                continuation = false
                return result
            }
        } else {
            silenceStart = null
        }
        if (position - candidateStart!! >= 320_000) {
            val end = silenceStart ?: position
            val result = SpeechBounds(candidateStart!!, end, hardCut = silenceStart == null)
            speech = silenceStart == null
            continuation = speech
            candidateStart = if (speech) position else null
            silenceStart = null
            return result
        }
        return null
    }

    fun finish(): SpeechBounds? {
        val result = if (speech) speechBounds(silenceStart ?: position) else null
        candidateStart = null
        silenceStart = null
        speech = false
        continuation = false
        return result
    }

    private fun speechBounds(end: Long): SpeechBounds? = SpeechBounds(candidateStart!!, end)
        // Even an immediate silence after a hard cut owns its trailing context:
        // the recognizer may timestamp the last syllable just beyond the cut.
        .takeIf { it.end >= it.start && (continuation || it.end - it.start >= 4_000) }
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
