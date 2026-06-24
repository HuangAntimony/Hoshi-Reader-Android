package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiMatch
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.epub.SasayakiPlaybackData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun playbackHeaderIsHiddenUntilAudiobookIsAvailable() {
        assertFalse(sasayakiShouldShowPlaybackHeader(hasAudio = false))
        assertTrue(sasayakiShouldShowPlaybackHeader(hasAudio = true))
    }

    @Test
    fun subtitleMatchSummaryShowsCurrentMatchRateWhenMatchDataExists() {
        val matchData = SasayakiMatchData(
            matches = listOf(
                SasayakiMatch("a", 0.0, 1.0, "a", 0, 0, 1),
                SasayakiMatch("b", 1.0, 2.0, "b", 0, 1, 1),
            ),
            unmatched = 1,
        )

        assertEquals(
            "2/3 (66.7%)",
            sasayakiSubtitleMatchSummary(matchData),
        )
    }

    @Test
    fun subtitleMatchSummaryIsAbsentWithoutMatchData() {
        assertNull(sasayakiSubtitleMatchSummary(null))
    }

    @Test
    fun audiobookCoverUsesReaderStylePortraitRatio() {
        assertEquals(48, SasayakiAudiobookCoverWidthDp)
        assertEquals(68, SasayakiAudiobookCoverHeightDp)
        assertEquals(
            48.0 / 68.0,
            SasayakiAudiobookCoverWidthDp.toDouble() / SasayakiAudiobookCoverHeightDp.toDouble(),
            0.0001,
        )
    }

    @Test
    fun chapterRowInfoKeepsTitleAsPrimaryTextAndTimeAsTrailingText() {
        val info = sasayakiChapterRowInfo(
            SasayakiAudiobookChapter(
                index = 1,
                title = "  Chapter 2  ",
                startSeconds = 65.0,
                endSeconds = 120.0,
            ),
        )

        assertEquals("Chapter 2", info.title)
        assertEquals("1:05", formatSasayakiChapterRowTime(info.startSeconds))
    }

    @Test
    fun playbackHeaderInfoUsesAudiobookMetadataAndCurrentChapter() {
        val info = sasayakiPlaybackHeaderInfo(
            playback = SasayakiPlaybackData(
                lastPosition = 0.0,
                audioFileName = "sasayaki_audio.m4b",
            ),
            metadata = SasayakiAudiobookMetadata(
                title = "  M4B Title  ",
                artist = " Narrator ",
            ),
            fallbackBookTitle = "Reader Book",
            currentChapter = SasayakiAudiobookChapter(
                index = 2,
                title = " Chapter 3 ",
                startSeconds = 42.0,
                endSeconds = 84.0,
            ),
        )

        assertEquals("M4B Title", info.title)
        assertEquals("Narrator", info.artist)
        assertEquals("Chapter 3", info.chapterTitle)
    }

    @Test
    fun playbackHeaderInfoFallsBackToAudioFileNameThenBookTitle() {
        assertEquals(
            "Side Story",
            sasayakiPlaybackHeaderInfo(
                playback = SasayakiPlaybackData(
                    lastPosition = 0.0,
                    audioFileName = "Side Story.m4b",
                ),
                metadata = SasayakiAudiobookMetadata.Empty,
                fallbackBookTitle = "Reader Book",
                currentChapter = null,
            ).title,
        )
        assertEquals(
            "Reader Book",
            sasayakiPlaybackHeaderInfo(
                playback = SasayakiPlaybackData(
                    lastPosition = 0.0,
                    audioFileName = "sasayaki_audio.m4b",
                ),
                metadata = SasayakiAudiobookMetadata.Empty,
                fallbackBookTitle = "Reader Book",
                currentChapter = null,
            ).title,
        )
    }
}
