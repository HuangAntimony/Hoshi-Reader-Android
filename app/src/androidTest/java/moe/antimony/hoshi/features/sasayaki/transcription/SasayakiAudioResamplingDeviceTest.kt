package moe.antimony.hoshi.features.sasayaki.transcription

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only cached, verified audio decoding resources. */
@RunWith(AndroidJUnit4::class)
class SasayakiAudioResamplingDeviceTest {
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
