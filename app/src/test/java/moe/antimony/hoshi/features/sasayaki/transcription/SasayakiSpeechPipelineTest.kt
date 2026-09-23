package moe.antimony.hoshi.features.sasayaki.transcription

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SasayakiSpeechPipelineTest {
    @Test fun speechEndingExactlyAtHardCutStillOwnsDelayedFinalToken() = runBlocking {
        var frames = 0
        var calls = 0
        val batches = mutableListOf<SasayakiTranscriptionBatch>()
        val pipeline = SasayakiSpeechPipeline(0.0, 20.512,
            probability = { if (frames++ < 625) 1f else 0f },
            recognize = {
                RecognitionTokens(arrayOf("終"), floatArrayOf(if (calls++ == 0) 20.1f else .6f))
            }, schedule = { work -> batches += work() })
        pipeline.accept(AudioSamples(0, FloatArray(328_192)))
        pipeline.finish()
        val token = batches.flatMap { it.tokens }.single()
        assertEquals("終", token.text)
        assertEquals(20.1, token.start, .00001)
        assertEquals(20.25, token.end, .00001)
    }

    @Test fun lateVadOnsetRetainsQuietSpeechPrefix() = runBlocking {
        val inputs = mutableListOf<FloatArray>()
        var windows = 0
        val pipeline = SasayakiSpeechPipeline(0.0, 3.584, probability = {
            val frame = windows++
            if (frame in 0..31 || frame in 60..91) .9f else 0f
        }, recognize = { samples ->
            inputs += samples
            RecognitionTokens(emptyArray(), floatArrayOf())
        }, schedule = { work -> work(); Unit })
        // The first utterance ends at 1.024 s. The next quiet prefix at 1.3 s
        // precedes the VAD's 1.92 s onset, outside the old half-second context.
        val audio = FloatArray(57_344)
        audio.fill(.25f, 20_800, 21_120)
        pipeline.accept(AudioSamples(0, audio))
        pipeline.finish()
        assertEquals(2, inputs.size)
        assertTrue("The quiet prefix must reach recognition", inputs[1].any { it == .25f })
    }

    @Test fun adjacentSilenceSegmentsDoNotDiscardNextWordPrefixAsOldContext() = runBlocking {
        val batches = mutableListOf<SasayakiTranscriptionBatch>()
        val inputStarts = mutableListOf<Float>()
        var windows = 0
        val pipeline = SasayakiSpeechPipeline(0.0, 3.072, probability = {
            val frame = windows++
            if (frame in 0..31 || frame in 48..79) .9f else 0f
        }, recognize = { samples ->
            inputStarts += samples.first()
            if (inputStarts.size == 1) RecognitionTokens(arrayOf("から"), floatArrayOf(.8f))
            else RecognitionTokens(arrayOf("冷", "房"), floatArrayOf(0f, .4f))
        }, schedule = { work -> batches.add(work()) })
        pipeline.accept(AudioSamples(0, FloatArray(49_152) { it / 49_152f }))
        pipeline.finish()
        assertEquals(listOf("から", "冷", "房"), batches.flatMap { it.tokens }.map { it.text })
        assertEquals((1.274 * 16_000 / 49_152).toFloat(), inputStarts[1], .00001f)
        val cold = batches.flatMap { it.tokens }.first { it.text == "冷" }
        assertEquals(batches.first().through, cold.start, .00001)
    }

    @Test fun hardCutStillUsesLeadingSpeechContextWithoutDuplicatingTokens() = runBlocking {
        val batches = mutableListOf<SasayakiTranscriptionBatch>()
        val inputSizes = mutableListOf<Int>()
        val pipeline = SasayakiSpeechPipeline(0.0, 21.0, probability = { .9f }, recognize = { samples ->
            inputSizes += samples.size
            if (inputSizes.size == 1) RecognitionTokens(arrayOf("前"), floatArrayOf(19.6f))
            else RecognitionTokens(arrayOf("前", "後"), floatArrayOf(.1f, .6f))
        }, schedule = { work -> batches.add(work()) })
        pipeline.accept(AudioSamples(0, FloatArray(336_000)))
        pipeline.finish()
        assertEquals(listOf("前", "後"), batches.flatMap { it.tokens }.map { it.text })
        assertEquals(24_000, inputSizes[1])
        assertEquals(20.1, batches.flatMap { it.tokens }.last().start, .00001)
    }

    @Test fun paddedResultsUseAbsoluteTimeAndDropAlreadyCommittedTokens() {
        val tokens = projectRecognitionTokens(
            RecognitionTokens(arrayOf("前", "今", "後"), floatArrayOf(.1f, .8f, 1.2f)),
            segmentStart = 10.0, segmentEnd = 11.5, committed = 10.5, through = 11.0,
        )
        assertEquals(listOf("今"), tokens.map { it.text })
        assertEquals(10.8, tokens.single().start, .00001)
        assertEquals(11.0, tokens.single().end, 0.0)
        val next = projectRecognitionTokens(
            RecognitionTokens(arrayOf("今", "後"), floatArrayOf(.3f, .6f)),
            segmentStart = 10.5, segmentEnd = 12.0, committed = 11.0, through = 12.0,
        )
        assertEquals(listOf("後"), next.map { it.text })
        assertEquals(11.1, next.single().start, .00001)
    }

    @Test fun tokensEmittedOnSameRnnTFrameHavePositiveBoundedDurations() {
        val tokens = projectRecognitionTokens(
            RecognitionTokens(arrayOf("同", "時", "刻"), floatArrayOf(.4f, .4f, .8f)),
            segmentStart = 10.0, segmentEnd = 12.0, committed = 10.0, through = 11.0,
        )
        assertEquals(3, tokens.size)
        assertTrue(tokens.all { it.end > it.start && it.end <= 11.0 })
        assertEquals(10.8, tokens[0].end, .00001)
        assertEquals(10.8, tokens[1].end, .00001)
    }

    @Test fun pendingSpeechIsNeverCommittedBeforeItsRecognitionResult() = runBlocking {
        val batches = mutableListOf<SasayakiTranscriptionBatch>()
        var recognized = 0
        val pipeline = SasayakiSpeechPipeline(30.0, 60.0, probability = { .9f }, recognize = {
            recognized++
            assertTrue(batches.all { it.through <= 30.0 })
            RecognitionTokens(arrayOf("声"), floatArrayOf(.5f))
        }, schedule = { work -> batches.add(work()) })
        repeat(625) { index -> pipeline.accept(AudioSamples(480_000L + index * 512, FloatArray(512))) }
        assertEquals(0, recognized)
        assertTrue(batches.isEmpty())
        repeat(8) { index -> pipeline.accept(AudioSamples(800_000L + index * 512, FloatArray(512))) }
        assertEquals(1, recognized)
        assertEquals(50.0, batches.single().through, 0.0)
        assertEquals(30.5, batches.single().tokens.single().start, .00001)
    }

    @Test fun longSilenceAdvancesAndCompletesWithoutRecognition() = runBlocking {
        val batches = mutableListOf<SasayakiTranscriptionBatch>()
        val pipeline = SasayakiSpeechPipeline(100.0, 112.0, probability = { 0f }, recognize = {
            error("Silence must not enter ASR")
        }, schedule = { work -> batches.add(work()) })
        repeat(375) { index -> pipeline.accept(AudioSamples(1_600_000L + index * 512, FloatArray(512))) }
        pipeline.finish()
        assertTrue(batches.size >= 3)
        assertEquals(112.0, batches.last().through, 0.0)
        assertTrue(batches.all { it.tokens.isEmpty() })
        assertTrue(batches.zipWithNext().all { (a, b) -> b.through > a.through })
    }

    @Test fun finalWordInTrailingContextIsPreserved() = runBlocking {
        val batches = mutableListOf<SasayakiTranscriptionBatch>()
        var windows = 0
        val pipeline = SasayakiSpeechPipeline(0.0, 2.0, probability = { if (windows++ < 32) .9f else 0f }, recognize = {
            RecognitionTokens(arrayOf("終"), floatArrayOf(1.12f))
        }, schedule = { work -> batches.add(work()) })
        pipeline.accept(AudioSamples(0, FloatArray(32_000)))
        pipeline.finish()
        assertEquals("終", batches.flatMap { it.tokens }.single().text)
        assertEquals(1.274, batches.first().through, .00001)
    }

    @Test fun resumedRecognitionUsesLeadingContextWithoutReemittingOldTokens() = runBlocking {
        val batches = mutableListOf<SasayakiTranscriptionBatch>()
        val pipeline = SasayakiSpeechPipeline(30.0, 31.0, probability = { .9f }, recognize = {
            RecognitionTokens(arrayOf("旧", "新"), floatArrayOf(.2f, 1f))
        }, schedule = { work -> batches.add(work()) }, audioFrom = 29.5)
        pipeline.accept(AudioSamples(472_000, FloatArray(24_000)))
        pipeline.finish()
        assertEquals(listOf("新"), batches.flatMap { it.tokens }.map { it.text })
        assertEquals(30.5, batches.single().tokens.single().start, .00001)
        assertEquals(31.0, batches.last().through, 0.0)
    }
}
