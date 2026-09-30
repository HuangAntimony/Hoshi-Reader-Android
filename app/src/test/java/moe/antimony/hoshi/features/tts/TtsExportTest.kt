package moe.antimony.hoshi.features.tts

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class TtsExportTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun cancellationAtReturnBoundaryDeletesCompletedExport() = runTest {
        val target = File(folder.root, "cancelled.wav")
        val owner = Job()
        val task = async(owner) {
            withTtsExportFile(target, StandardTestDispatcher(testScheduler)) {
                it.writeText("completed")
                owner.cancel()
            }
        }
        runCurrent()
        assertTrue(task.isCancelled)
        assertFalse(target.exists())
    }

    @Test fun completedExportRemainsOwnedByCaller() = runTest {
        val target = File(folder.root, "complete.wav")
        val result = withTtsExportFile(target, UnconfinedTestDispatcher(testScheduler)) { it.writeText("complete") }
        assertEquals(target, result)
        assertEquals("complete", result.readText())
    }

    @Test fun concatenationPreservesPcmFormatAndAllSentenceChunks() {
        fun wav(name: String, pcm: ByteArray): File {
            val bytes = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
            bytes.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray()).putInt(16)
            bytes.putShort(1).putShort(1).putInt(24000).putInt(48000).putShort(2).putShort(16)
            bytes.put("data".toByteArray()).putInt(pcm.size).put(pcm)
            return File(folder.root, name).apply { writeBytes(bytes.array()) }
        }
        val first = wav("first.wav", byteArrayOf(1, 2, 3, 4))
        val second = wav("second.wav", byteArrayOf(5, 6))
        val result = TtsWav.concatenate(listOf(first, second), File(folder.root, "all.wav")).readBytes()
        assertEquals(50, result.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), result.copyOfRange(44, 50))
        assertEquals(42, ByteBuffer.wrap(result, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int)
    }
}
