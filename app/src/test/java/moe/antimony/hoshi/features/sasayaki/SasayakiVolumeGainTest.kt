package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SasayakiVolumeGainTest {
    @Test
    fun attenuationAndUnityUsePlayerVolumeWithoutAnEffect() {
        val output = Output()
        output.gain.setAudioSessionId(12)
        output.gain.setGainDb(-6)

        assertEquals(0.501187f, output.volume, 0.000001f)
        assertTrue(output.effects.isEmpty())

        output.gain.setGainDb(0)

        assertEquals(1f, output.volume, 0f)
        assertTrue(output.effects.isEmpty())
    }

    @Test
    fun boostBindsWhenSessionBecomesAvailableAndResetsWhenCleared() {
        val output = Output()
        output.gain.setGainDb(6)
        assertTrue(output.effects.isEmpty())
        assertEquals(1f, output.volume, 0f)

        output.gain.setAudioSessionId(12)
        val firstEffect = output.effects.getValue(12)
        assertEquals(600, firstEffect.gainMb)

        output.gain.setGainDb(8)
        assertEquals(800, firstEffect.gainMb)
        assertEquals(1f, output.volume, 0f)

        output.gain.setAudioSessionId(13)
        assertEquals(1, firstEffect.releases)
        assertEquals(800, output.effects.getValue(13).gainMb)

        output.gain.setGainDb(0)
        output.gain.setAudioSessionId(14)
        assertEquals(1, output.effects.getValue(13).releases)
        assertEquals(setOf(12, 13), output.effects.keys)
        assertEquals(1f, output.volume, 0f)
    }

    @Test
    fun failedEnableReleasesEffectAndRetriesOnGainChange() {
        val output = Output()
        val failure = IllegalStateException("Effect enable failed")
        output.nextEffectFailure = failure
        output.gain.setAudioSessionId(12)
        output.gain.setGainDb(6)
        val failedEffect = output.effects.getValue(12)

        assertEquals(1f, output.volume, 0f)
        assertEquals(1, failedEffect.releases)
        assertSame(failure, output.error)

        output.gain.setGainDb(7)

        assertEquals(700, output.effects.getValue(12).gainMb)
        assertNull(output.error)
    }

    private class Output {
        val effects = mutableMapOf<Int, Effect>()
        var nextEffectFailure: RuntimeException? = null
        var volume = 1f
        var error: RuntimeException? = null
        val gain = SasayakiVolumeGainOutput(
            setPlayerVolume = { volume = it },
            createEffect = { sessionId ->
                Effect(nextEffectFailure).also {
                    effects[sessionId] = it
                    nextEffectFailure = null
                }
            },
            onError = { error = it },
        )
    }

    private class Effect(private val failure: RuntimeException?) : SasayakiLoudnessEffect {
        var gainMb = 0
        var releases = 0

        override fun applyGain(targetGainMb: Int) {
            failure?.let { throw it }
            gainMb = targetGainMb
        }

        override fun release() {
            releases++
        }
    }
}
