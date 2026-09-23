package moe.antimony.hoshi.features.sasayaki.transcription

import kotlin.math.pow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SasayakiEnergyVadTest {
    @Test fun quietRecordingCanBeginWithSpeechWithoutCalibrationSilence() {
        val detector = SasayakiEnergyVad()
        repeat(60) { assertTrue(detector.probability(tone(-44.0)) >= .5f) }
    }

    @Test fun digitalSilenceStaysFiniteAndBelowSpeechThreshold() {
        val detector = SasayakiEnergyVad()
        repeat(2_000) {
            val score = detector.probability(FloatArray(512))
            assertTrue(score.isFinite() && score < .35f)
        }
    }

    @Test fun adaptsToSteadyBackgroundButRetainsLouderSpeech() {
        val detector = SasayakiEnergyVad()
        val background = tone(-43.0)
        repeat(1_000) { detector.probability(background) }
        assertTrue(detector.probability(background) < .35f)
        assertTrue(detector.probability(tone(-20.0)) >= .5f)
    }

    @Test fun oldLoudBackgroundDoesNotHideLaterQuietRecording() {
        val detector = SasayakiEnergyVad()
        repeat(1_000) { detector.probability(tone(-40.0)) }
        repeat(1_000) { detector.probability(tone(-80.0)) }
        assertTrue(detector.probability(tone(-44.0)) >= .5f)
        assertTrue(detector.probability(tone(-80.0)) < .35f)
    }

    @Test fun loudDialogueWithShortSyllableGapsReachesRecognition() = runBlocking {
        val detector = SasayakiEnergyVad()
        val inputs = mutableListOf<FloatArray>()
        val pipeline = SasayakiSpeechPipeline(0.0, 4.096, detector::probability,
            recognize = { samples -> inputs += samples; RecognitionTokens(emptyArray(), floatArrayOf()) },
            schedule = { work -> work(); Unit })
        repeat(128) { frame ->
            val samples = if (frame in 32..95 && frame % 6 < 3) tone(-18.0) else FloatArray(512)
            pipeline.accept(AudioSamples(frame * 512L, samples))
        }
        pipeline.finish()
        assertEquals(1, inputs.size)
        assertTrue(inputs.single().count { it != 0f } >= 15_000)
    }

    private fun tone(db: Double): FloatArray {
        val amplitude = 10.0.pow(db / 20).toFloat()
        return FloatArray(512) { if (it % 2 == 0) amplitude else -amplitude }
    }
}
