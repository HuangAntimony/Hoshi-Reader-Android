package moe.antimony.hoshi.features.sasayaki.transcription

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.features.sasayaki.SasayakiToken
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

    @Test fun presetsMatchDesktopTokensAndTimestamps() = runBlocking {
        val expected = Json.decodeFromString<List<SasayakiToken>>(File(requireNotNull(arguments.getString("nakuruReference"))).readText())
        val backend = backend()
        for (preset in 1..3) {
            val batches = mutableListOf<SasayakiTranscriptionBatch>()
            backend.transcribe(source(), 0.0, { error("Download the resources in the app first") }, {}, batches::add, preset)
            val actual = batches.flatMap { it.tokens }
            File(context.cacheDir, "nakuru-result-$preset.json").writeText(Json.encodeToString(actual))
            assertEquals(backend.duration(source()), batches.last().through, .001)
            assertEquals("preset=$preset", expected.size, actual.size)
            expected.zip(actual).forEach { (reference, token) ->
                assertEquals(reference.text, token.text)
                assertEquals(reference.start, token.start, .000001)
                assertEquals(reference.end, token.end, .000001)
            }
        }
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
