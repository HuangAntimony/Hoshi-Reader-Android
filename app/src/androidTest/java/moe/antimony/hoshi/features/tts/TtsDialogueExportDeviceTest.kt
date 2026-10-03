package moe.antimony.hoshi.features.tts

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Local PCM fixtures exercise real Transformer codecs without network, engine preferences or app data. */
@RunWith(AndroidJUnit4::class)
class TtsDialogueExportDeviceTest {
    @Test fun mixedRatesAndChannelsProduceOneCompleteAacSentence() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "tts-dialogue-export-fixture").apply { mkdirs() }
        fun wav(name: String, rate: Int, channels: Int): File {
            val frames = rate / 2
            val dataSize = frames * channels * 2
            val bytes = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
            bytes.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(channels.toShort()).putInt(rate).putInt(rate * channels * 2)
                .putShort((channels * 2).toShort()).putShort(16).put("data".toByteArray()).putInt(dataSize)
            repeat(frames) { frame -> repeat(channels) { bytes.putShort((kotlin.math.sin(frame * 2 * Math.PI * 440 / rate) * 4000).toInt().toShort()) } }
            return File(root, name).apply { writeBytes(bytes.array()) }
        }
        try {
            val parts = listOf(wav("n.wav", 24000, 1), wav("d.wav", 48000, 2), wav("n2.wav", 16000, 1))
            val output = joinTtsAudio(parts, File(root, "sentence.wav"), AndroidTtsExportFallback(context))
            assertEquals("m4a", output.extension)
            assertFalse(File(root, "sentence.wav").exists())
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.absolutePath)
                val format = extractor.getTrackFormat(0)
                assertEquals("audio/mp4a-latm", format.getString(MediaFormat.KEY_MIME))
                assertEquals(24000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                assertEquals(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                assertTrue(format.getLong(MediaFormat.KEY_DURATION) in 1_400_000L..1_650_000L)
            } finally { extractor.release() }
        } finally { withContext(Dispatchers.IO) { root.deleteRecursively() } }
    }
}
