package moe.antimony.hoshi.features.sasayaki.transcription

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SasayakiAudioFeedTest {
    @Test fun decodesAheadWithinABoundWhileRecognitionIsBusyAndPreservesOrder() = runTest {
        val recognitionReady = CompletableDeferred<Unit>()
        var produced = 0
        val consumed = mutableListOf<Long>()
        val job = launch {
            feedTranscriptionAudio(decode = { send ->
                repeat(1_000) { index ->
                    send(AudioSamples(index.toLong(), floatArrayOf(index.toFloat())))
                    produced++
                }
            }, consume = { chunk ->
                recognitionReady.await()
                consumed += chunk.startSample
            })
        }
        try {
            runCurrent()
            assertTrue("Decode must progress while ASR is busy", produced > 1)
            assertTrue("Read-ahead must stay bounded", produced <= 257)
            assertFalse(job.isCompleted)
            recognitionReady.complete(Unit)
            job.join()
            assertEquals((0L until 1_000L).toList(), consumed)
        } finally { job.cancelAndJoin() }
    }

    @Test fun cancellingRecognitionJoinsDecoderAndReleasesItsResources() = runTest {
        var decoderClosed = false
        val job = launch {
            feedTranscriptionAudio(decode = { send ->
                try { while (true) send(AudioSamples(0, floatArrayOf(0f))) }
                finally { decoderClosed = true }
            }, consume = { awaitCancellation() })
        }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(decoderClosed)
    }

    @Test fun decoderFailureReachesCallerEvenWhileRecognitionIsSuspended() = runTest {
        val failure = IOException("decode failed")
        var caught: Throwable? = null
        val job = launch {
            try {
                feedTranscriptionAudio(decode = { send ->
                    send(AudioSamples(0, floatArrayOf(0f)))
                    throw failure
                }, consume = { awaitCancellation() })
            } catch (error: IOException) { caught = error }
        }
        try {
            runCurrent()
            assertTrue("Producer failures must unblock recognition", job.isCompleted)
            assertEquals(failure.javaClass, caught?.javaClass)
            assertEquals(failure.message, caught?.message)
        } finally { job.cancelAndJoin() }
    }

    @Test fun recognitionFailureJoinsDecoderAndRetainsOriginalError() = runTest {
        val failure = IOException("recognition failed")
        var decoderClosed = false
        try {
            feedTranscriptionAudio(decode = { send ->
                try { while (true) send(AudioSamples(0, floatArrayOf(0f))) }
                finally { decoderClosed = true }
            }, consume = { throw failure })
            fail("Expected failure")
        } catch (error: IOException) {
            assertEquals(failure.javaClass, error.javaClass)
            assertEquals(failure.message, error.message)
        }
        assertTrue(decoderClosed)
    }
}
