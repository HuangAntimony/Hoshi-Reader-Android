package moe.antimony.hoshi.features.sasayaki.transcription

import org.junit.Assert.*
import org.junit.Test

class SasayakiSpeechSegmentationTest {
    @Test fun shortGapsBetweenSyllablesDoNotPreventSpeechFromStarting() {
        val segmenter = SpeechSegmenter()
        val segments = mutableListOf<SpeechBounds>()
        repeat(60) { frame ->
            segmenter.accept(if (frame % 6 < 3) 1f else 0f, 512)?.let(segments::add)
        }
        repeat(16) { segmenter.accept(0f, 512)?.let(segments::add) }
        assertEquals(listOf(SpeechBounds(0, 29_184)), segments)
        assertNull(segmenter.finish())
    }

    @Test fun isolatedClickDoesNotBecomeAnUtterance() {
        val segmenter = SpeechSegmenter()
        val segments = mutableListOf<SpeechBounds>()
        repeat(2) { segmenter.accept(1f, 512)?.let(segments::add) }
        repeat(20) { segmenter.accept(0f, 512)?.let(segments::add) }
        assertTrue(segments.isEmpty())
        assertNull(segmenter.finish())
    }

    @Test fun finishingKeepsShortReplyButDiscardsAnIsolatedClick() {
        val reply = SpeechSegmenter()
        repeat(12) { reply.accept(if (it % 4 < 2) 1f else 0f, 512) }
        assertEquals(SpeechBounds(0, 5_120), reply.finish())
        val click = SpeechSegmenter()
        repeat(2) { click.accept(1f, 512) }
        assertNull(click.finish())
    }

    @Test fun shortContinuationAfterHardLimitIsNotDiscardedAsNoise() {
        val segmenter = SpeechSegmenter()
        repeat(625) { segmenter.accept(1f, 512) }
        repeat(3) { segmenter.accept(1f, 512) }
        assertEquals(SpeechBounds(320_000, 321_536), segmenter.finish())

        val followedBySilence = SpeechSegmenter()
        repeat(625) { followedBySilence.accept(1f, 512) }
        repeat(3) { followedBySilence.accept(1f, 512) }
        val segments = (0 until 16).mapNotNull { followedBySilence.accept(0f, 512) }
        assertEquals(listOf(SpeechBounds(320_000, 321_536)), segments)
    }
}
