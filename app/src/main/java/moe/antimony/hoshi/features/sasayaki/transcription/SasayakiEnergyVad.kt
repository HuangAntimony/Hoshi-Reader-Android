package moe.antimony.hoshi.features.sasayaki.transcription

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.roundToInt

/** Clean audiobook speech is separated from silence by energy, not by a voice
 * classifier. A bounded 30-second histogram follows the recording's noise floor.
 * Quiet prehistory keeps the beginning of a low-volume recording eligible. */
internal class SasayakiEnergyVad {
    private val history = IntArray(938)
    private val histogram = IntArray(101).apply { this[0] = history.size }
    private var cursor = 0

    fun probability(samples: FloatArray): Float {
        require(samples.isNotEmpty() && samples.size <= 512)
        var energy = 0.0
        for (sample in samples) energy += sample.toDouble() * sample
        val db = if (energy > 0) (10 * log10(energy / samples.size)).coerceIn(-100.0, 0.0) else -100.0
        val bin = db.roundToInt() + 100
        histogram[history[cursor]]--
        history[cursor] = bin
        histogram[bin]++
        cursor = (cursor + 1) % history.size

        // 10th percentile + 12 dB; clamps protect quiet voices and prevent
        // digital silence from making tiny numerical noise count as speech.
        var count = 0
        var floor = 0
        while (floor < histogram.lastIndex) {
            count += histogram[floor]
            if (count > history.size / 10) break
            floor++
        }
        val threshold = (floor - 100 + 12).coerceIn(-55, -30)
        return (1.0 / (1.0 + exp(-(db - threshold) / 2.0))).toFloat()
    }
}
