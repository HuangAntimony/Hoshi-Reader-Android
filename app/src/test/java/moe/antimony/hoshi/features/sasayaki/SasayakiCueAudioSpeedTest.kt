package moe.antimony.hoshi.features.sasayaki

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(UnstableApi::class)
class SasayakiCueAudioSpeedTest {
    @Test
    fun changesClipDurationWithoutChangingPitch() {
        val source = ShortArray(132_300) { index ->
            (10_000 * sin(2 * PI * 440 * index / SampleRate)).toInt().toShort()
        }
        for ((rate, expectedDuration) in listOf(0.5f to 6.0, 1f to 3.0, 1.5f to 2.0, 3f to 1.0)) {
            val output = process(source, rate)

            assertEquals("Duration at ${rate}x", expectedDuration, output.size.toDouble() / SampleRate, 0.03)
            assertEquals("Pitch at ${rate}x", 440.0, frequency(output), 5.0)
        }
    }

    private fun process(source: ShortArray, rate: Float): ShortArray {
        val processor = sasayakiCueAudioProcessor(rate)
        processor.configure(AudioProcessor.AudioFormat(SampleRate, 1, C.ENCODING_PCM_16BIT))
        if (!processor.isActive) return source
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val input = ByteBuffer.allocateDirect(source.size * 2).order(ByteOrder.nativeOrder())
        source.forEach(input::putShort)
        input.flip()
        val bytes = ByteArrayOutputStream()
        fun drain() {
            val output = processor.output
            val chunk = ByteArray(output.remaining())
            output.get(chunk)
            bytes.write(chunk)
        }
        while (input.hasRemaining()) {
            processor.queueInput(input)
            drain()
        }
        processor.queueEndOfStream()
        while (!processor.isEnded) drain()
        processor.reset()
        val samples = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.nativeOrder()).asShortBuffer()
        return ShortArray(samples.remaining()).also(samples::get)
    }

    private fun frequency(samples: ShortArray): Double {
        val start = SampleRate / 10
        val end = samples.size - start
        val crossings = (start + 1 until end).count { index ->
            samples[index - 1] <= 0 && samples[index] > 0
        }
        return crossings.toDouble() * SampleRate / (end - start)
    }

    private companion object {
        const val SampleRate = 44_100
    }
}
