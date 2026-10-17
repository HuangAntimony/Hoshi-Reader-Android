package moe.antimony.hoshi.features.sasayaki.transcription

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranscriptionBenchmarkDeviceTest {
    @Test fun benchmarkExcerpt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val source = File(requireNotNull(arguments.getString("clip"))).toURI().toString()
        val preset = requireNotNull(arguments.getString("preset", "3")).toInt()
        val decoder = AndroidSasayakiAudioDecoder(context, Dispatchers.IO)
        val runtime = SasayakiRuntimeRepository(context, Dispatchers.IO)
        val backend = NakuruTranscriptionBackend(decoder, NakuruResources(context, Dispatchers.IO), runtime, Dispatchers.Default)
        val duration = backend.duration(source)
        require(duration in 1.0..300.0)
        val batches = mutableListOf<SasayakiTranscriptionBatch>()
        val started = SystemClock.elapsedRealtime()
        backend.transcribe(source, 0.0, { error("Download the resources in the app first") }, {}, batches::add, preset)
        val elapsed = SystemClock.elapsedRealtime() - started
        val tokens = batches.flatMap { it.tokens }
        File(context.cacheDir, "transcription-nakuru-$preset.json").writeText(Json.encodeToString(tokens))
        val metrics = "{\"preset\":$preset,\"durationSeconds\":$duration,\"elapsedMillis\":$elapsed,\"tokens\":${tokens.size}}"
        File(context.cacheDir, "transcription-nakuru-$preset-timing.json").writeText(metrics)
        Log.i("TranscriptionBenchmark", "nakuru $metrics")
        assertTrue(tokens.isNotEmpty())
        assertEquals(duration, batches.last().through, .001)
        assertTrue(tokens.all { it.start.isFinite() && it.end.isFinite() && it.start >= 0 && it.end >= it.start && it.end <= duration + .001 })
    }
}
