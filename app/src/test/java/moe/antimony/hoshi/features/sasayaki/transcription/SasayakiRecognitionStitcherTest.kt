package moe.antimony.hoshi.features.sasayaki.transcription

import moe.antimony.hoshi.features.sasayaki.SasayakiToken
import org.junit.Assert.*
import org.junit.Test

class SasayakiRecognitionStitcherTest {
    @Test fun repeatedContextSurvivesTimestampDriftWithinTheWord() {
        val stitcher = SasayakiRecognitionStitcher(0.0)
        stitcher.accept(SasayakiRecognitionBatch(listOf(
            SasayakiToken("な", 6023.066, 6023.226), SasayakiToken("い", 6023.226, 6023.506),
            SasayakiToken("わ", 6023.506, 6023.898)), 6023.648, 6003.066))
        val next = stitcher.accept(SasayakiRecognitionBatch(listOf(
            SasayakiToken("な", 6023.148, 6023.508), SasayakiToken("い", 6023.508, 6024.508),
            SasayakiToken("わ", 6024.508, 6024.698)), 6024.698, 6023.148))
        assertTrue(next.tokens.isEmpty())
    }

    @Test fun overlapIncludesAWordWhoseStartPrecedesTheInputWindow() {
        val stitcher = SasayakiRecognitionStitcher(0.0)
        stitcher.accept(SasayakiRecognitionBatch(listOf(
            SasayakiToken("南", 6182.314, 6182.754), SasayakiToken("さ", 6182.754, 6182.834),
            SasayakiToken("ん", 6182.834, 6183.418)), 6183.168, 6162.874))
        val next = stitcher.accept(SasayakiRecognitionBatch(listOf(
            SasayakiToken("南", 6182.748, 6183.148), SasayakiToken("さ", 6183.148, 6183.188),
            SasayakiToken("ん", 6183.188, 6183.308), SasayakiToken("に", 6183.308, 6183.548)), 6183.548, 6182.668))
        assertEquals(listOf("に"), next.tokens.map { it.text })
        assertTrue(next.tokens.single().start >= 6183.418)
    }

    @Test fun resumeAfterALongTokenRetainsANewEarlyWord() {
        val stitcher = SasayakiRecognitionStitcher(20.25, listOf(SasayakiToken("前", 19.0, 20.25)))
        val next = stitcher.accept(SasayakiRecognitionBatch(listOf(
            SasayakiToken("新", 20.1, 20.5), SasayakiToken("後", 20.5, 21.0)), 21.0, 19.75))
        assertEquals(listOf("新", "後"), next.tokens.map { it.text })
    }

    @Test fun keepsDistinctWordsOnBothSidesOfOverlappingAudio() {
        val stitcher = SasayakiRecognitionStitcher(0.0)
        val first = stitcher.accept(SasayakiRecognitionBatch(listOf(
            SasayakiToken("でしょ", 19.5, 20.162), SasayakiToken("う", 20.162, 20.25)), 20.25, 0.0))
        val second = stitcher.accept(SasayakiRecognitionBatch(listOf(
            SasayakiToken("二", 19.5, 19.78), SasayakiToken("人", 19.78, 19.98),
            SasayakiToken("で", 19.98, 20.5), SasayakiToken("歌う", 20.5, 22.0)), 22.0, 19.5))
        assertEquals("でしょう二人で歌う", (first.tokens + second.tokens).joinToString("") { it.text })
        assertEquals(20.162, first.tokens.last().start, 0.0)
        assertEquals(20.25, second.tokens.first().start, 0.0)
        assertTrue(second.tokens.all { it.start < it.end })
        assertTrue(second.tokens.zipWithNext().all { (a, b) -> a.end <= b.start })
    }

    @Test fun removesOnlyRepeatedContextAtTheSameAudioPosition() {
        val stitcher = SasayakiRecognitionStitcher(0.0)
        val first = stitcher.accept(SasayakiRecognitionBatch(listOf(
            SasayakiToken("うん", 19.6, 20.25)), 20.25, 0.0))
        val second = stitcher.accept(SasayakiRecognitionBatch(listOf(
            SasayakiToken("うん", 19.6, 20.3), SasayakiToken("うん", 20.3, 21.0)), 21.0, 19.5))
        assertEquals(listOf("うん", "うん"), (first.tokens + second.tokens).map { it.text })
        assertEquals(20.3, second.tokens.single().start, 0.0)
    }

    @Test fun repeatedWordAtANewTimeIsNotContext() {
        val stitcher = SasayakiRecognitionStitcher(0.0)
        stitcher.accept(SasayakiRecognitionBatch(listOf(SasayakiToken("ね", 19.0, 20.25)), 20.25, 0.0))
        val next = stitcher.accept(SasayakiRecognitionBatch(listOf(SasayakiToken("ね", 20.1, 21.0)), 21.0, 19.5))
        assertEquals("ね", next.tokens.single().text)
    }

    @Test fun savedTailDistinguishesOldContextFromANewEarlyWordOnResume() {
        val stitcher = SasayakiRecognitionStitcher(20.25, listOf(SasayakiToken("う", 20.162, 20.25)))
        val batch = stitcher.accept(SasayakiRecognitionBatch(listOf(
            SasayakiToken("う", 20.162, 20.2), SasayakiToken("二", 20.2, 20.3),
            SasayakiToken("人で", 20.3, 21.0)), 21.0, 19.75))
        assertEquals(listOf("二", "人で"), batch.tokens.map { it.text })
        assertEquals(20.25, batch.tokens.first().start, 0.0)
    }

    @Test fun resumeDoesNotReemitSpeechBeforeTheSavedCheckpoint() {
        val stitcher = SasayakiRecognitionStitcher(30.0)
        val batch = stitcher.accept(SasayakiRecognitionBatch(listOf(
            SasayakiToken("旧", 29.7, 30.5), SasayakiToken("新", 30.5, 31.0)), 31.0, 29.5))
        assertEquals(listOf("新"), batch.tokens.map { it.text })
    }
}
