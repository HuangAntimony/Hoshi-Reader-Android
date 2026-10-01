package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertEquals
import org.junit.Test

class SasayakiSentenceAudioSpeedTest {
    @Test
    fun explicitSentenceAudioSpeedOverridesAudiobookSpeed() {
        val settings = SasayakiSettings(sentenceAudioRate = 1.75f)

        assertEquals(1.75f, settings.effectiveSentenceAudioRate(0.75f), 0f)
        assertEquals(1.75f, settings.effectiveSentenceAudioRate(2.5f), 0f)
    }

    @Test
    fun invalidOverrideFallsBackToAudiobookSpeed() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(1.25f, SasayakiSettings(sentenceAudioRate = invalid).effectiveSentenceAudioRate(1.25f), 0f)
        }
    }

    @Test
    fun effectiveSpeedClampsOverrideAndFollowedAudiobookToSupportedRange() {
        assertEquals(0.5f, SasayakiSettings(sentenceAudioRate = 0.1f).effectiveSentenceAudioRate(1f), 0f)
        assertEquals(3f, SasayakiSettings(sentenceAudioRate = 4f).effectiveSentenceAudioRate(1f), 0f)
        assertEquals(0.5f, SasayakiSettings().effectiveSentenceAudioRate(0.1f), 0f)
        assertEquals(3f, SasayakiSettings().effectiveSentenceAudioRate(4f), 0f)
    }

    @Test
    fun invalidAudiobookSpeedFallsBackToNormalSpeed() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(1f, SasayakiSettings().effectiveSentenceAudioRate(invalid), 0f)
        }
    }
}
