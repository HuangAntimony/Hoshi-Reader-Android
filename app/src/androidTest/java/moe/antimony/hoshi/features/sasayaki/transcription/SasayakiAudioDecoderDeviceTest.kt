package moe.antimony.hoshi.features.sasayaki.transcription

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SasayakiAudioDecoderDeviceTest {
    private val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    @org.junit.Before fun prepareCachedRuntime() = kotlinx.coroutines.runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = SasayakiRuntimeRepository(context, kotlinx.coroutines.Dispatchers.IO)
        val directory = runtime.store().ensure({ error("Seed verified runtime files before native tests") }) {}
        runtime.load(directory)
    }

    @Test fun stereoDownmixFiltersOutUltrasoundAndKeepsSeekClock() {
        withWave { file ->
            val full = decode(file)
            assertEquals(48_000, full.size)
            // Left/right average preserves the 400 Hz tone and cancels the 1 kHz tone.
            // The 12 kHz component must be filtered before downsampling to 16 kHz.
            val expected = FloatArray(full.size) { (.25 * sin(2 * PI * 400 * it / 16_000)).toFloat() }
            assertArrayEquals(expected.copyOfRange(160, 47_840), full.copyOfRange(160, 47_840), .005f)
            for (from in listOf(.625, 1.12345, 2.4321)) {
                val start = ceil(from * 16_000 - 1e-8).toInt()
                val tail = decode(file, from)
                assertEquals(full.size - start, tail.size)
                assertArrayEquals(full.copyOfRange(start + 160, 47_840), tail.copyOfRange(160, tail.size - 160), .005f)
            }
        }
    }

    @Test fun descriptorOffsetAndLengthExcludeSurroundingBytes() {
        withWave { file ->
            val wrapped = File.createTempFile("audio-window-", ".bin", cache)
            try {
                wrapped.outputStream().use { out ->
                    out.write(ByteArray(1037) { 0x55 }); file.inputStream().use { it.copyTo(out) }
                    out.write(ByteArray(1999) { 0x7f })
                }
                assertArrayEquals(decode(file), decode(wrapped, offset = 1037, length = file.length()), 0f)
            } finally { wrapped.delete() }
        }
    }

    @Test fun malformedInputDoesNotLeakDescriptors() {
        val invalid = File.createTempFile("audio-invalid-", ".bin", cache)
        try {
            invalid.writeBytes(ByteArray(512) { 0x31 })
            val before = File("/proc/self/fd").list()!!.size
            repeat(20) {
                assertThrows(IOException::class.java) { decode(invalid) }
            }
            assertTrue(File("/proc/self/fd").list()!!.size <= before + 2)
        } finally { invalid.delete() }
    }

    @Test fun compressedAudioKeepsAbsoluteSeekClock() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        for (name in listOf("tone.m4a", "tone.mp3", "tone.opus")) {
            val file = File.createTempFile("audio-seek-", name, cache)
            try {
                assets.open("transcription/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
                val full = decode(file)
                if (!name.endsWith("m4a")) {
                    assertEquals("$name gapless duration", 48_000, full.size)
                    // Skipping encoder priming must also advance frame PTS. A
                    // correct total length can hide dropped/shifted first samples.
                    val beginning = FloatArray(160) { (.125 * sin(2 * PI * 700 * it / 16_000)).toFloat() }
                    assertArrayEquals("$name audible beginning", beginning, full.copyOfRange(0, 160), .02f)
                }
                for (from in listOf(1.23456, 2.4567)) {
                    val start = ceil(from * 16_000 - 1e-8).toInt()
                    val tail = decode(file, from)
                    assertEquals("$name seek=$from length", full.size - start, tail.size)
                    var error = 0.0; var power = 0.0
                    for (i in 160 until tail.size - 160) {
                        val delta = (tail[i] - full[start + i]).toDouble()
                        error += delta * delta; power += full[start + i].toDouble().pow(2)
                    }
                    // AAC perceptual noise substitution can regenerate slightly
                    // different noise after seeking. PCM phase is checked strictly
                    // with the synthetic WAV above; lossy audio uses signal error.
                    assertTrue("$name seek=$from relative RMS error ${sqrt(error / power)}", sqrt(error / power) < .01)
                }
            } finally { file.delete() }
        }
    }

    @Test fun decodingAndDurationUseTheSameFirstAudioTrack() = kotlinx.coroutines.runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("audio-tracks-", ".m4a", cache)
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("transcription/two-tracks.m4a").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            val decoder = AndroidSasayakiAudioDecoder(context, kotlinx.coroutines.Dispatchers.IO)
            assertEquals(3.0, decoder.duration(file.toURI().toString()), .03)
            // The second track is six seconds long and marked default. Selecting
            // it here would disagree with duration/checkpoint bounds above.
            val samples = decode(file)
            assertTrue("Decoded ${samples.size} samples", samples.size in 48_000..48_512)
            // The first compressed track was copied unchanged from tone.m4a.
            // Compare decoded tracks, not an ideal sine at the AAC window edge.
            val reference = File.createTempFile("audio-first-track-", ".m4a", cache)
            try {
                InstrumentationRegistry.getInstrumentation().context.assets.open("transcription/tone.m4a").use { input ->
                    reference.outputStream().use { input.copyTo(it) }
                }
                assertArrayEquals(decode(reference), samples, 0f)
            } finally { reference.delete() }
        } finally { file.delete() }
    }

    private fun decode(file: File, from: Double = 0.0, offset: Long = 0, length: Long = -1): FloatArray =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            val handle = NativeSasayakiAudio.open(fd.fd, offset, length, from)
            try {
                val chunks = mutableListOf<FloatArray>()
                while (true) {
                    val chunk = NativeSasayakiAudio.read(handle) ?: break
                    assertTrue(chunk.size in 1..4096)
                    assertTrue(chunk.all { it.isFinite() && it in -1f..1f })
                    chunks.add(chunk)
                }
                val samples = FloatArray(chunks.sumOf { it.size }); var start = 0
                for (chunk in chunks) { chunk.copyInto(samples, start); start += chunk.size }
                samples
            } finally { NativeSasayakiAudio.close(handle) }
        }

    private fun withWave(block: (File) -> Unit) {
        val file = File.createTempFile("audio-filter-", ".wav", cache)
        try {
            val frames = 44_100 * 3
            val data = ByteBuffer.allocate(44 + frames * 4).order(ByteOrder.LITTLE_ENDIAN)
            data.put("RIFF".toByteArray()).putInt(36 + frames * 4).put("WAVEfmt ".toByteArray())
            data.putInt(16).putShort(1).putShort(2).putInt(44_100).putInt(176_400).putShort(4).putShort(16)
            data.put("data".toByteArray()).putInt(frames * 4)
            repeat(frames) { i ->
                val common = .25 * sin(2 * PI * 400 * i / 44_100) + .25 * sin(2 * PI * 12_000 * i / 44_100)
                val side = .15 * sin(2 * PI * 1000 * i / 44_100)
                data.putShort(((common + side) * 32767).roundToInt().toShort())
                data.putShort(((common - side) * 32767).roundToInt().toShort())
            }
            file.writeBytes(data.array()); block(file)
        } finally { file.delete() }
    }
}
