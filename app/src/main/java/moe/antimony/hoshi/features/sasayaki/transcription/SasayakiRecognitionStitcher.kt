package moe.antimony.hoshi.features.sasayaki.transcription

import kotlin.math.abs
import moe.antimony.hoshi.features.sasayaki.SasayakiToken

internal data class SasayakiRecognitionBatch(
    val tokens: List<SasayakiToken>,
    val through: Double,
    val segmentStart: Double,
)

/** Consumed in audio order, after parallel recognition. Keep acoustic padding until
 * text can distinguish repeated context from a new word with an early timestamp. */
internal class SasayakiRecognitionStitcher(from: Double, previousTokens: List<SasayakiToken> = emptyList()) {
    private var committed = from
    private var previous = previousTokens.filter { it.end >= from - 1.0 }
    private var recognized = previous.isNotEmpty()
    private var emittedThrough = maxOf(from, previous.maxOfOrNull { it.end } ?: from)

    fun accept(batch: SasayakiRecognitionBatch): SasayakiTranscriptionBatch {
        if (batch.through <= committed) return SasayakiTranscriptionBatch(emptyList(), committed)
        val raw = batch.tokens
        val overlap = previous.filter { it.end >= batch.segmentStart }
        val duplicate = (1..minOf(overlap.size, raw.size)).lastOrNull { count ->
            val old = overlap.takeLast(count)
            val next = raw.take(count)
            val sameText = old.zip(next).all { (a, b) -> a.text == b.text }
            val phrase = count > 1 && next.joinToString("") { it.text }.codePoints().distinct().count() > 1
            sameText && next.first().start < emittedThrough &&
                if (phrase) abs(old.first().start - next.first().start) <= .75
                else abs(old.first().start - next.first().start) <= .25
        } ?: 0
        val retained = if (!recognized) raw.filter { it.start >= committed } else raw.drop(duplicate)
        val early = if (retained.firstOrNull()?.start?.let { it < emittedThrough } == true)
            retained.indexOfFirst { it.start > emittedThrough }.let { if (it < 0) retained.size else it }
        else 0
        val end = retained.getOrNull(early)?.start ?: retained.lastOrNull()?.end ?: batch.through
        // Only the leading overlap is retimed, within the next segment's owned
        // interval. Distinct RNN-T tokens otherwise retain their original times.
        val tokens = retained.mapIndexed { index, token ->
            if (index >= early) token else token.copy(
                start = emittedThrough + (end - emittedThrough) * index / early,
                end = emittedThrough + (end - emittedThrough) * (index + 1) / early,
            )
        }
        previous = (previous + retained.filterIndexed { index, _ -> tokens[index].end > tokens[index].start })
            .filter { it.end >= batch.through - 1.0 }
        recognized = true
        val published = tokens.filter { it.end > it.start }
        emittedThrough = maxOf(emittedThrough, published.maxOfOrNull { it.end } ?: emittedThrough)
        committed = batch.through
        return SasayakiTranscriptionBatch(published, committed)
    }
}
