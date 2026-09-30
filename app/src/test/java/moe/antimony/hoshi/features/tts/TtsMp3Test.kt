package moe.antimony.hoshi.features.tts

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TtsMp3Test {
    @get:Rule val folder = TemporaryFolder()
    private fun frame(value: Byte) = byteArrayOf(0xff.toByte(), 0xf3.toByte(), 0x64, 0xc0.toByte()) + ByteArray(140) { value }

    @Test fun joinsEveryPartAndRemovesPerFileMetadataWithoutTranscoding() {
        val firstFrame = frame(1)
        val secondFrame = frame(2)
        val metadata = frame(0).apply { "Info".toByteArray().copyInto(this, 13) }
        val id3 = "ID3".toByteArray() + byteArrayOf(4, 0, 0, 0, 0, 0, 0)
        val first = File(folder.root, "a.mp3").apply { writeBytes(id3 + metadata + firstFrame) }
        val second = File(folder.root, "b.mp3").apply { writeBytes(metadata + secondFrame) }
        val target = File(folder.root, "all.mp3")
        TtsMp3.concatenate(listOf(first, second), target)
        assertArrayEquals(firstFrame + secondFrame, target.readBytes())
    }

    @Test fun keepsOriginalSingleFileAndRejectsHtmlOrPartialFrames() {
        val bytes = frame(1)
        val first = File(folder.root, "a.mp3").apply { writeBytes(bytes) }
        val target = File(folder.root, "all.mp3")
        TtsMp3.concatenate(listOf(first), target)
        assertArrayEquals(bytes, target.readBytes())
        assertThrows(TtsException::class.java) { TtsMp3.validate("<html>bad</html>".toByteArray()) }
        assertThrows(TtsException::class.java) { TtsMp3.validate(bytes.copyOf(50)) }
    }
}
