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

    private fun wav(name: String, rate: Int) = File(folder.root, name).apply {
        writeBytes(ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(40).put("WAVEfmt ".toByteArray()).putInt(16)
            putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            put("data".toByteArray()).putInt(4).putInt(0)
        }.array())
    }

    @Test fun incompatibleVoicesUseM4aFallbackAndDeleteTheFailedDirectExport() = runTest {
        val parts = listOf(wav("first.wav", 24000), wav("second.wav", 48000))
        val target = File(folder.root, "sentence.wav")
        val output = joinTtsAudio(parts, target, TtsExportFallback { inputs, result ->
            assertEquals(parts, inputs)
            assertTrue(inputs.all { it.isFile })
            result.writeText("normalized")
        }, UnconfinedTestDispatcher(testScheduler))
        assertEquals("m4a", output.extension)
        assertEquals("normalized", output.readText())
        assertFalse(target.exists())
    }

    @Test fun cancelledFallbackDeletesPartialOutputAndKeepsItsInputFiles() = runTest {
        val target = File(folder.root, "cancel.wav")
        val parts = listOf(wav("first.wav", 24000), wav("second.wav", 48000))
        val pending = CompletableDeferred<Unit>()
        val task = launch {
            joinTtsAudio(parts, target, TtsExportFallback { _, output -> output.writeText("partial"); pending.await() }, UnconfinedTestDispatcher(testScheduler))
        }
        runCurrent()
        task.cancelAndJoin()
        assertFalse(target.exists())
        assertFalse(File(folder.root, "cancel.m4a").exists())
        assertTrue(parts.all { it.isFile })
    }

    @Test fun corruptAudioIsNotTreatedAsAFormatMismatch() = runTest {
        val parts = listOf(wav("first.wav", 24000), File(folder.root, "bad.wav").apply { writeText("bad") })
        val target = File(folder.root, "bad-export.wav")
        try {
            joinTtsAudio(parts, target, TtsExportFallback { _, _ -> fail("Must not transcode corrupt input") }, UnconfinedTestDispatcher(testScheduler))
            fail("Corrupt audio must fail")
        } catch (_: IllegalArgumentException) { assertFalse(target.exists()) }
    }

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
