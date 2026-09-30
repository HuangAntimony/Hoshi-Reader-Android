package moe.antimony.hoshi.features.tts

import java.io.File

/** Join MPEG Layer III frames losslessly, dropping each chunk's ID3 and duration/seek header. */
internal object TtsMp3 {
    private data class Frame(val offset: Int, val size: Int, val rate: Int, val channels: Int, val metadata: Boolean)

    fun validate(bytes: ByteArray) { frames(bytes) }

    fun concatenate(parts: List<File>, target: File): File {
        if (parts.isEmpty()) invalid()
        if (parts.size == 1) { parts.single().copyTo(target, overwrite = true); return target }
        var format: Pair<Int, Int>? = null
        target.outputStream().use { output ->
            parts.forEach { file ->
                val bytes = file.readBytes()
                frames(bytes).filterNot { it.metadata }.forEach { frame ->
                    val current = frame.rate to frame.channels
                    if (format != null && format != current) invalid()
                    format = current
                    output.write(bytes, frame.offset, frame.size)
                }
            }
        }
        return target
    }

    private fun frames(bytes: ByteArray): List<Frame> {
        fun tag(offset: Int, length: Int) = if (offset >= 0 && offset + length <= bytes.size)
            bytes.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII) else ""
        var cursor = 0
        if (tag(0, 3) == "ID3") {
            if (bytes.size < 10) invalid()
            var length = 0
            for (index in 6..9) {
                val value = bytes[index].toInt() and 255
                if (value >= 128) invalid()
                length = (length shl 7) or value
            }
            cursor = 10 + length + if (bytes[5].toInt() and 16 != 0) 10 else 0
        }
        val end = if (tag(bytes.size - 128, 3) == "TAG") bytes.size - 128 else bytes.size
        val result = mutableListOf<Frame>()
        while (cursor < end) {
            if (cursor + 4 > end) invalid()
            val b0 = bytes[cursor].toInt() and 255
            val b1 = bytes[cursor + 1].toInt() and 255
            val b2 = bytes[cursor + 2].toInt() and 255
            val b3 = bytes[cursor + 3].toInt() and 255
            val version = (b1 shr 3) and 3
            if (b0 != 255 || b1 and 224 != 224 || version == 1 || (b1 shr 1) and 3 != 1) invalid()
            val bitrates = if (version == 3) intArrayOf(0,32,40,48,56,64,80,96,112,128,160,192,224,256,320)
                else intArrayOf(0,8,16,24,32,40,48,56,64,80,96,112,128,144,160)
            val bitrate = bitrates.getOrNull(b2 shr 4)?.takeIf { it > 0 } ?: invalid()
            val baseRate = intArrayOf(44100,48000,32000).getOrNull((b2 shr 2) and 3) ?: invalid()
            val rate = baseRate / when(version) { 3 -> 1; 2 -> 2; else -> 4 }
            val size = (if (version == 3) 144 else 72) * bitrate * 1000 / rate + ((b2 shr 1) and 1)
            if (cursor + size > end) invalid()
            val channels = if (b3 shr 6 == 3) 1 else 2
            val side = if (version == 3) { if (channels == 1) 17 else 32 } else { if (channels == 1) 9 else 17 }
            val info = tag(cursor + 4 + (if (b1 and 1 == 0) 2 else 0) + side, 4)
            val metadata = result.isEmpty() && (info == "Xing" || info == "Info" || tag(cursor + 36, 4) == "VBRI")
            result += Frame(cursor, size, rate, channels, metadata)
            cursor += size
        }
        if (result.none { !it.metadata }) invalid()
        return result
    }

    private fun invalid(): Nothing = throw TtsException(TtsFailure.InvalidResponse)
}
