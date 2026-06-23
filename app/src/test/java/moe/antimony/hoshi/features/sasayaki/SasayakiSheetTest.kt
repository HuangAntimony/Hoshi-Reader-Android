package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertEquals
import org.junit.Test

class SasayakiSheetTest {
    @Test
    fun playbackSpeedSliderAllowsTwoTimesSpeedWithExistingStepSize() {
        assertEquals(0.5f, SasayakiSpeedSliderRange.start, 0.0f)
        assertEquals(2.0f, SasayakiSpeedSliderRange.endInclusive, 0.0f)

        val intervalCount = SasayakiSpeedSliderSteps + 1
        val stepSize = (SasayakiSpeedSliderRange.endInclusive - SasayakiSpeedSliderRange.start) / intervalCount
        assertEquals(0.05f, stepSize, 0.0001f)
    }

    @Test
    fun defaultTabIsResourcesUntilAudiobookWithChaptersIsAvailable() {
        assertEquals(
            SasayakiSheetTab.Resources,
            sasayakiDefaultSheetTab(hasAudio = false, hasChapters = false),
        )
        assertEquals(
            SasayakiSheetTab.Resources,
            sasayakiDefaultSheetTab(hasAudio = true, hasChapters = false),
        )
        assertEquals(
            SasayakiSheetTab.Chapters,
            sasayakiDefaultSheetTab(hasAudio = true, hasChapters = true),
        )
    }
}
