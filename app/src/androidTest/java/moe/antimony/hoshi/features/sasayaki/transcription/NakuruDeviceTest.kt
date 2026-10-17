package moe.antimony.hoshi.features.sasayaki.transcription

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NakuruDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private fun backend() = NakuruTranscriptionBackend(
        AndroidSasayakiAudioDecoder(context, Dispatchers.IO),
        NakuruResources(context, Dispatchers.IO), SasayakiRuntimeRepository(context, Dispatchers.IO), Dispatchers.Default,
    )
    private fun source() = File(requireNotNull(arguments.getString("nakuruClip"))).toURI().toString()

    @Test fun presetsGiveIdenticalTimedTokens() = runBlocking {
        val backend = backend()
        val duration = backend.duration(source())
        val results = (1..3).map { preset ->
            val batches = mutableListOf<SasayakiTranscriptionBatch>()
            backend.transcribe(source(), 0.0, { error("Download the resources in the app first") }, {}, batches::add, preset)
            val tokens = batches.flatMap { it.tokens }
            File(context.cacheDir, "nakuru-result-$preset.json").writeText(Json.encodeToString(tokens))
            assertEquals(duration, batches.last().through, .001)
            assertTrue(tokens.isNotEmpty())
            assertTrue(tokens.all { it.start >= 0 && it.end >= it.start && it.end <= duration + .001 })
            tokens
        }
        assertEquals(results[0], results[1])
        assertEquals(results[0], results[2])
    }

    @Test fun cancellationJoinsWorkersAndResumesAtSegmentEnd() = runBlocking {
        val backend = backend()
        var checkpoint: SasayakiTranscriptionBatch? = null
        try {
            backend.transcribe(source(), 0.0, { error("Download the resources in the app first") }, {}, { batch ->
                if (batch.tokens.isNotEmpty()) {
                    checkpoint = batch
                    throw CancellationException("Pause after a final segment")
                }
            })
            fail("Expected speech before EOF")
        } catch (_: CancellationException) { }
        val saved = checkNotNull(checkpoint)
        val resumed = mutableListOf<SasayakiTranscriptionBatch>()
        backend.transcribe(source(), saved.through, { error("Download the resources in the app first") }, {}, resumed::add)
        assertEquals(backend.duration(source()), resumed.last().through, .001)
        assertTrue(resumed.flatMap { it.tokens }.isNotEmpty())
        assertTrue(resumed.flatMap { it.tokens }.all { it.start >= saved.through - .0001 })
    }
}
