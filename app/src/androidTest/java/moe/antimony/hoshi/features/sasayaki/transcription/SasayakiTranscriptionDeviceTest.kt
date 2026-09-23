package moe.antimony.hoshi.features.sasayaki.transcription

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.antimony.hoshi.features.sasayaki.SasayakiToken
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Invoke directly with adb am instrument; no connected task, clear or uninstall.
 * Optional realClip is a scratch local audio path. Models must already be seeded
 * or installed in no_backup; this test never downloads models on its own. */
@RunWith(AndroidJUnit4::class)
class SasayakiTranscriptionDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @org.junit.Before fun prepareCachedRuntime() = kotlinx.coroutines.runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = SasayakiRuntimeRepository(context, kotlinx.coroutines.Dispatchers.IO)
        val directory = runtime.store().ensure({ error("Seed verified runtime files before native tests") }) {}
        runtime.load(directory)
    }

    @Test fun decoderResamplesStereoAndTrimsSeekPreroll() = runBlocking {
        val file = File.createTempFile("asr-decoder-", ".wav", context.cacheDir)
        try {
            writeWave(file)
            val decoder = AndroidSasayakiAudioDecoder(context, Dispatchers.IO)
            val source = file.toURI().toString()
            assertEquals(1.0, decoder.duration(source), .001)
            val full = mutableListOf<AudioSamples>()
            decoder.decode(source, 0.0, full::add)
            assertEquals(0L, full.first().startSample)
            assertEquals(16_000, full.sumOf { it.samples.size })
            val resumed = mutableListOf<AudioSamples>()
            decoder.decode(source, .625, resumed::add)
            assertEquals(10_000L, resumed.first().startSample)
            assertEquals(6_000, resumed.sumOf { it.samples.size })
            val whole = full.flatMap { it.samples.toList() }.toFloatArray()
            val tail = resumed.flatMap { it.samples.toList() }.toFloatArray()
            // Decoder may not retain filter history at the seek point. Ignore
            // only that sub-5ms boundary; the original audio clock must agree.
            assertArrayEquals(whole.copyOfRange(10_080, 15_920), tail.copyOfRange(80, 5_920), .003f)
        } finally { file.delete() }
    }

    @Test fun realClipTranscribesAndResumesAfterCancellation() = runBlocking<Unit> {
        val clip = InstrumentationRegistry.getArguments().getString("realClip")
        assumeTrue("Supply a scratch Japanese realClip to exercise native ASR", clip != null)
        val directory = File(context.noBackupFilesDir, "SasayakiModels/reazonspeech-k2-v2-int8-v1")
        assumeTrue("Seed verified model files first", ReazonSpeechModelCatalog.files.all { File(directory, it.name).length() == it.bytes })
        val source = File(clip!!).toURI().toString()
        val backend = AndroidSasayakiTranscriptionBackend(
            AndroidSasayakiAudioDecoder(context, Dispatchers.IO),
            SasayakiModelRepository(context, Dispatchers.IO),
            SasayakiRuntimeRepository(context, Dispatchers.IO), Dispatchers.Default,
        )
        withTimeout(120_000) {
            val duration = backend.duration(source)
            var checkpoint: SasayakiTranscriptionBatch? = null
            try {
                backend.transcribe(source, 0.0, { error("Device tests must never download models") }, {}, { batch ->
                    if (batch.tokens.isNotEmpty()) {
                        checkpoint = batch
                        throw CancellationException("Exercise native resource cleanup and resume")
                    }
                }, parallelism = 3)
                fail("The Japanese clip must contain recognized speech")
            } catch (_: CancellationException) { }
            val saved = checkNotNull(checkpoint)
            assertTrue(saved.through > 0 && saved.through < duration)
            val resumed = mutableListOf<SasayakiTranscriptionBatch>()
            backend.transcribe(source, saved.through, { error("Device tests must never download models") }, {}, resumed::add, parallelism = 1)
            assertEquals(duration, resumed.last().through, .001)
            val tokens = resumed.flatMap { it.tokens }
            assertTrue(tokens.size > 10)
            assertTrue(tokens.all { it.start >= saved.through && it.end >= it.start && it.end <= duration })
            assertTrue(resumed.zipWithNext().all { (a, b) -> a.through < b.through })
            Log.i("HoshiAsrSmoke", "duration=$duration checkpoint=${saved.through} tokens=${tokens.size}")
        }
    }

    @Test fun realClipPresetsProduceIdenticalTokensAndTimestamps() = runBlocking<Unit> {
        val clip = InstrumentationRegistry.getArguments().getString("realClip")
        assumeTrue("Supply a scratch Japanese realClip to exercise native ASR", clip != null)
        val directory = File(context.noBackupFilesDir, "SasayakiModels/reazonspeech-k2-v2-int8-v1")
        assumeTrue("Seed verified model files first", ReazonSpeechModelCatalog.files.all { File(directory, it.name).length() == it.bytes })
        val source = File(clip!!).toURI().toString()
        val backend = AndroidSasayakiTranscriptionBackend(
            AndroidSasayakiAudioDecoder(context, Dispatchers.IO),
            SasayakiModelRepository(context, Dispatchers.IO),
            SasayakiRuntimeRepository(context, Dispatchers.IO), Dispatchers.Default,
        )
        withTimeout(120_000) {
            var reference: List<SasayakiToken>? = null
            val duration = backend.duration(source)
            for (parallelism in 1..3) {
                val batches = mutableListOf<SasayakiTranscriptionBatch>()
                val start = System.nanoTime()
                backend.transcribe(source, 0.0, { error("Device tests must never download models") }, {}, batches::add, parallelism)
                val tokens = batches.flatMap { it.tokens }
                assertTrue(tokens.size > 10)
                assertEquals(duration, batches.last().through, .001)
                assertTrue(batches.zipWithNext().all { (a, b) -> a.through < b.through })
                if (reference == null) reference = tokens else assertEquals(reference, tokens)
                Log.i("HoshiAsrSmoke", "parallelism=$parallelism seconds=${(System.nanoTime() - start) / 1e9} tokens=${tokens.size}")
            }
        }
    }

    private fun writeWave(file: File) {
        val frames = 44_100
        val pcmBytes = frames * 4
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcmBytes).put("WAVEfmt ".toByteArray())
        header.putInt(16).putShort(1).putShort(2).putInt(44_100).putInt(176_400).putShort(4).putShort(16)
        header.put("data".toByteArray()).putInt(pcmBytes)
        file.outputStream().use { output ->
            output.write(header.array())
            val samples = ByteBuffer.allocate(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
            repeat(frames) { index ->
                val amplitude = (12_000 * sin(2 * PI * 440 * index / 44_100)).toInt().toShort()
                samples.putShort(amplitude).putShort(amplitude)
            }
            output.write(samples.array())
        }
    }
}
