package moe.antimony.hoshi.features.sasayaki.transcription

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SasayakiParallelTranscriptionTest {
    @Test fun presetsBoundConcurrencyAndPublishOutOfOrderCompletionsInAudioOrder() = runTest {
        for (parallelism in 1..3) {
            var active = 0
            var peak = 0
            var started = 0
            val finished = mutableListOf<Int>()
            val batches = mutableListOf<SasayakiTranscriptionBatch>()
            transcribeSpeechAudio(0.0, 65.0, 0.0, parallelism, decode = ::audio, probability = { .9f }, recognize = {
                val index = started++
                active++
                peak = maxOf(peak, active)
                try {
                    delay(if (index == 0) 100 else 10)
                    finished += index
                    RecognitionTokens(arrayOf("$index"), floatArrayOf(1f))
                } finally { active-- }
            }, onBatch = batches::add)
            assertEquals(parallelism, peak)
            assertEquals(0, active)
            if (parallelism > 1) assertEquals(1, finished.first())
            assertEquals(listOf("0", "1", "2", "3"), batches.flatMap { it.tokens }.map { it.text })
            assertEquals(listOf(20.0, 40.0, 60.0, 65.0), batches.map { it.through })
            assertEquals(listOf(1.0, 20.5, 40.5, 60.5), batches.flatMap { it.tokens }.map { it.start })
        }
    }

    @Test fun slowPublicationBoundsQueuedWorkAndCancellationJoinsEveryRecognizer() = runTest {
        var active = 0
        var started = 0
        var decodedChunks = 0
        val first = CompletableDeferred<Unit>()
        val batches = mutableListOf<SasayakiTranscriptionBatch>()
        val task = launch {
            transcribeSpeechAudio(0.0, 600.0, 0.0, 3, decode = { send ->
                repeat(600) { decodedChunks++; send(AudioSamples(it * 16_000L, FloatArray(16_000))) }
            }, probability = { .9f }, recognize = {
                val index = started++
                active++
                try {
                    if (index == 0) RecognitionTokens(arrayOf("先"), floatArrayOf(1f))
                    else { first.await(); awaitCancellation() }
                } finally { active-- }
            }, onBatch = { batches += it; first.complete(Unit); awaitCancellation() })
        }
        runCurrent()
        assertEquals(3, active)
        assertTrue(started <= 7)
        assertTrue("Slow publication must backpressure decoding instead of buffering the whole book", decodedChunks < 600)
        assertEquals(listOf(20.0), batches.map { it.through })
        task.cancelAndJoin()
        assertEquals(0, active)
        assertEquals(listOf("先"), batches.flatMap { it.tokens }.map { it.text })
    }

    @Test fun failureCancelsEarlierPendingWorkWithoutPublishingLaterProgress() = runTest {
        var active = 0
        var started = 0
        val batches = mutableListOf<SasayakiTranscriptionBatch>()
        try {
            transcribeSpeechAudio(0.0, 65.0, 0.0, 3, decode = ::audio, probability = { .9f }, recognize = {
                val index = started++
                active++
                try {
                    if (index == 1) throw IOException("recognizer failed")
                    awaitCancellation()
                } finally { active-- }
            }, onBatch = batches::add)
            fail("Recognition failure must propagate")
        } catch (expected: IOException) {
            assertEquals("recognizer failed", expected.message)
        }
        assertEquals(0, active)
        assertTrue(batches.isEmpty())
    }

    @Test fun resumeKeepsLeadingContextWithoutReemittingOldTokens() = runTest {
        val batches = mutableListOf<SasayakiTranscriptionBatch>()
        transcribeSpeechAudio(30.0, 42.0, 29.5, 3, decode = { send ->
            send(AudioSamples(472_000, FloatArray(200_000)))
        }, probability = { .9f }, recognize = {
            delay(20)
            RecognitionTokens(arrayOf("旧", "新"), floatArrayOf(.2f, 1f))
        }, onBatch = batches::add)
        assertEquals(listOf("新"), batches.flatMap { it.tokens }.map { it.text })
        assertEquals(30.5, batches.first().tokens.single().start, .00001)
        assertEquals(42.0, batches.last().through, 0.0)
    }

    @Test fun silenceAndLaterSpeechCannotOvertakeFirstUnfinishedSegment() = runTest {
        val gate = CompletableDeferred<Unit>()
        val batches = mutableListOf<SasayakiTranscriptionBatch>()
        var frames = 0
        var calls = 0
        val task = launch {
            transcribeSpeechAudio(0.0, 12.0, 0.0, 3, decode = { send ->
                repeat(12) { send(AudioSamples(it * 16_000L, FloatArray(16_000))) }
            }, probability = {
                val frame = frames++
                if (frame in 0..31 || frame in 250..281) .9f else 0f
            }, recognize = {
                val index = calls++
                if (index == 0) gate.await()
                RecognitionTokens(arrayOf(if (index == 0) "先" else "後"), floatArrayOf(.5f))
            }, onBatch = batches::add)
        }
        runCurrent()
        assertEquals(2, calls)
        assertTrue(batches.isEmpty())
        gate.complete(Unit)
        task.join()
        assertEquals(listOf("先", "後"), batches.flatMap { it.tokens }.map { it.text })
        assertEquals(12.0, batches.last().through, 0.0)
        assertTrue(batches.zipWithNext().all { (a, b) -> a.through < b.through })
    }

    private suspend fun audio(send: suspend (AudioSamples) -> Unit) {
        repeat(65) { send(AudioSamples(it * 16_000L, FloatArray(16_000))) }
    }
}
