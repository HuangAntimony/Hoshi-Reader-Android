package moe.antimony.hoshi.features.sasayaki

import android.media.audiofx.AudioEffect
import android.media.audiofx.LoudnessEnhancer
import kotlin.math.pow

internal class SasayakiVolumeGainOutput(
    private val setPlayerVolume: (Float) -> Unit,
    private val createEffect: (Int) -> SasayakiLoudnessEffect,
    private val onError: (RuntimeException?) -> Unit,
) {
    private var audioSessionId = 0
    private var gainDb = 0
    private var effect: SasayakiLoudnessEffect? = null
    fun setAudioSessionId(value: Int) {
        if (audioSessionId == value) return
        releaseEffect()
        audioSessionId = value
        applyGain()
    }

    fun setGainDb(value: Int) {
        gainDb = value
        applyGain()
    }

    private fun applyGain() {
        setPlayerVolume(10.0.pow(gainDb.coerceAtMost(0) / 20.0).toFloat())
        if (gainDb <= 0 || audioSessionId <= 0) {
            releaseEffect()
            onError(null)
            return
        }

        try {
            val current = effect ?: createEffect(audioSessionId).also { effect = it }
            current.applyGain(gainDb * 100)
        } catch (error: RuntimeException) {
            releaseEffect()
            onError(error)
            return
        }
        onError(null)
    }

    private fun releaseEffect() {
        effect?.release()
        effect = null
    }
}

internal interface SasayakiLoudnessEffect {
    fun applyGain(targetGainMb: Int)
    fun release()
}

internal class AndroidSasayakiLoudnessEffect(audioSessionId: Int) : SasayakiLoudnessEffect {
    private val effect = LoudnessEnhancer(audioSessionId)

    override fun applyGain(targetGainMb: Int) {
        effect.setTargetGain(targetGainMb)
        if (!effect.enabled) {
            val status = effect.setEnabled(true)
            check(status == AudioEffect.SUCCESS) { "LoudnessEnhancer.setEnabled returned status $status" }
        }
    }

    override fun release() {
        effect.release()
    }
}
