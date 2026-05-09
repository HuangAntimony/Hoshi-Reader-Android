package moe.antimony.hoshi.features.sasayaki

import java.io.ByteArrayOutputStream
import java.io.File

internal object AacAdtsCueAudioRewriter {
    fun rewrite(input: File, output: File): Boolean = runCatching {
        val bytes = input.readBytes()
        val moov = boxes(bytes, 0, bytes.size).firstOrNull { it.type == "moov" }
            ?: return@runCatching false
        val stbl = findAudioSampleTable(bytes, moov) ?: return@runCatching false
        val config = readAacConfig(bytes, stbl) ?: return@runCatching false
        val sampleSizes = readSampleSizes(bytes, stbl)
        val chunkOffsets = readChunkOffsets(bytes, stbl)
        val chunkSamples = readChunkSamples(bytes, stbl, chunkOffsets.size)
        if (sampleSizes.isEmpty() || chunkOffsets.isEmpty() || chunkSamples.isEmpty()) {
            return@runCatching false
        }

        ByteArrayOutputStream(bytes.size).use { out ->
            var sampleIndex = 0
            chunkOffsets.forEachIndexed { chunkIndex, chunkOffset ->
                var sampleOffset = chunkOffset
                repeat(chunkSamples[chunkIndex]) {
                    if (sampleIndex >= sampleSizes.size) return@runCatching false
                    val sampleSize = sampleSizes[sampleIndex++]
                    if (sampleSize <= 0 || sampleOffset < 0 || sampleOffset + sampleSize > bytes.size) {
                        return@runCatching false
                    }
                    out.write(adtsHeader(config, sampleSize + AdtsHeaderSize))
                    out.write(bytes, sampleOffset, sampleSize)
                    sampleOffset += sampleSize
                }
            }
            if (sampleIndex != sampleSizes.size) return@runCatching false
            output.parentFile?.mkdirs()
            output.writeBytes(out.toByteArray())
        }
        output.isFile && output.length() > 0L
    }.getOrDefault(false)

    private fun findAudioSampleTable(bytes: ByteArray, moov: Box): Box? {
        boxes(bytes, moov.contentStart, moov.end).filter { it.type == "trak" }.forEach { trak ->
            val mdia = boxes(bytes, trak.contentStart, trak.end).firstOrNull { it.type == "mdia" } ?: return@forEach
            val handler = boxes(bytes, mdia.contentStart, mdia.end).firstOrNull { it.type == "hdlr" }
            if (handler?.handlerType(bytes) != "soun") return@forEach
            val minf = boxes(bytes, mdia.contentStart, mdia.end).firstOrNull { it.type == "minf" } ?: return@forEach
            return boxes(bytes, minf.contentStart, minf.end).firstOrNull { it.type == "stbl" }
        }
        return null
    }

    private fun readAacConfig(bytes: ByteArray, stbl: Box): AacConfig? {
        val stsd = child(bytes, stbl, "stsd") ?: return null
        val entryStart = stsd.contentStart + FullBoxHeaderSize + IntSize
        if (entryStart + BoxHeaderSize > stsd.end || type(bytes, entryStart + IntSize) != "mp4a") return null
        val mp4aSize = uint32(bytes, entryStart).toInt()
        val mp4aEnd = (entryStart + mp4aSize).coerceAtMost(stsd.end)
        val esdsStart = entryStart + BoxHeaderSize + AudioSampleEntryPayloadSize
        val esds = boxes(bytes, esdsStart, mp4aEnd).firstOrNull { it.type == "esds" } ?: return null
        val asc = findAudioSpecificConfig(bytes, esds.contentStart + FullBoxHeaderSize, esds.end) ?: return null
        return aacConfig(asc)
    }

    private fun findAudioSpecificConfig(bytes: ByteArray, start: Int, end: Int): ByteArray? {
        var pos = start
        while (pos < end - 2) {
            if (bytes[pos].toInt() and 0xff == AudioSpecificConfigTag) {
                val length = descriptorLength(bytes, pos + 1, end) ?: run {
                    pos++
                    continue
                }
                val payloadStart = length.first
                val payloadLength = length.second
                if (payloadLength >= 2 && payloadStart + payloadLength <= end) {
                    return bytes.copyOfRange(payloadStart, payloadStart + payloadLength)
                }
            }
            pos++
        }
        return null
    }

    private fun readSampleSizes(bytes: ByteArray, stbl: Box): IntArray {
        val stsz = child(bytes, stbl, "stsz") ?: return IntArray(0)
        val sampleSize = uint32(bytes, stsz.contentStart + FullBoxHeaderSize).toInt()
        val sampleCount = uint32(bytes, stsz.contentStart + FullBoxHeaderSize + IntSize).toInt()
        if (sampleCount <= 0) return IntArray(0)
        if (sampleSize > 0) return IntArray(sampleCount) { sampleSize }
        val start = stsz.contentStart + FullBoxHeaderSize + IntSize + IntSize
        if (start + sampleCount * IntSize > stsz.end) return IntArray(0)
        return IntArray(sampleCount) { index -> uint32(bytes, start + index * IntSize).toInt() }
    }

    private fun readChunkOffsets(bytes: ByteArray, stbl: Box): IntArray {
        child(bytes, stbl, "stco")?.let { stco ->
            val count = uint32(bytes, stco.contentStart + FullBoxHeaderSize).toInt()
            val start = stco.contentStart + FullBoxHeaderSize + IntSize
            if (count <= 0 || start + count * IntSize > stco.end) return IntArray(0)
            return IntArray(count) { index -> uint32(bytes, start + index * IntSize).toInt() }
        }
        child(bytes, stbl, "co64")?.let { co64 ->
            val count = uint32(bytes, co64.contentStart + FullBoxHeaderSize).toInt()
            val start = co64.contentStart + FullBoxHeaderSize + IntSize
            if (count <= 0 || start + count * LongSize > co64.end) return IntArray(0)
            return IntArray(count) { index -> uint64(bytes, start + index * LongSize).toInt() }
        }
        return IntArray(0)
    }

    private fun readChunkSamples(bytes: ByteArray, stbl: Box, chunkCount: Int): IntArray {
        val stsc = child(bytes, stbl, "stsc") ?: return IntArray(0)
        val entryCount = uint32(bytes, stsc.contentStart + FullBoxHeaderSize).toInt()
        val start = stsc.contentStart + FullBoxHeaderSize + IntSize
        if (entryCount <= 0 || start + entryCount * StscEntrySize > stsc.end) return IntArray(0)
        val entries = List(entryCount) { index ->
            val offset = start + index * StscEntrySize
            StscEntry(
                firstChunk = uint32(bytes, offset).toInt(),
                samplesPerChunk = uint32(bytes, offset + IntSize).toInt(),
            )
        }
        return IntArray(chunkCount) { chunk ->
            val oneBasedChunk = chunk + 1
            entries.lastOrNull { it.firstChunk <= oneBasedChunk }?.samplesPerChunk ?: 0
        }
    }

    private fun aacConfig(asc: ByteArray): AacConfig? {
        if (asc.size < 2) return null
        val bits = asc.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(2).padStart(8, '0')
        }
        val objectType = bits.substring(0, 5).toInt(2)
        val sampleRateIndex = bits.substring(5, 9).toInt(2)
        val channelConfig = bits.substring(9, 13).toInt(2)
        if (objectType <= 0 || sampleRateIndex == 15 || channelConfig <= 0) return null
        return AacConfig(profile = objectType - 1, sampleRateIndex = sampleRateIndex, channelConfig = channelConfig)
    }

    private fun adtsHeader(config: AacConfig, frameLength: Int): ByteArray =
        byteArrayOf(
            0xff.toByte(),
            0xf1.toByte(),
            (((config.profile and 0x03) shl 6) or ((config.sampleRateIndex and 0x0f) shl 2) or ((config.channelConfig shr 2) and 0x01)).toByte(),
            (((config.channelConfig and 0x03) shl 6) or ((frameLength shr 11) and 0x03)).toByte(),
            ((frameLength shr 3) and 0xff).toByte(),
            (((frameLength and 0x07) shl 5) or 0x1f).toByte(),
            0xfc.toByte(),
        )

    private fun child(bytes: ByteArray, parent: Box, type: String): Box? =
        boxes(bytes, parent.contentStart, parent.end).firstOrNull { it.type == type }

    private fun boxes(bytes: ByteArray, start: Int, end: Int): List<Box> {
        val result = mutableListOf<Box>()
        var pos = start
        while (pos + BoxHeaderSize <= end) {
            val smallSize = uint32(bytes, pos)
            val boxType = type(bytes, pos + IntSize)
            val headerSize: Int
            val size: Long
            if (smallSize == 1L) {
                if (pos + ExtendedBoxHeaderSize > end) break
                headerSize = ExtendedBoxHeaderSize
                size = uint64(bytes, pos + BoxHeaderSize)
            } else {
                headerSize = BoxHeaderSize
                size = smallSize
            }
            if (size < headerSize || pos + size > end) break
            result += Box(type = boxType, start = pos, headerSize = headerSize, size = size.toInt())
            pos += size.toInt()
        }
        return result
    }

    private fun Box.handlerType(bytes: ByteArray): String? {
        val offset = contentStart + FullBoxHeaderSize + IntSize
        return if (offset + IntSize <= end) type(bytes, offset) else null
    }

    private fun descriptorLength(bytes: ByteArray, start: Int, end: Int): Pair<Int, Int>? {
        var pos = start
        var length = 0
        repeat(4) {
            if (pos >= end) return null
            val value = bytes[pos++].toInt() and 0xff
            length = (length shl 7) or (value and 0x7f)
            if (value and 0x80 == 0) return pos to length
        }
        return null
    }

    private fun type(bytes: ByteArray, offset: Int): String =
        String(bytes, offset, IntSize, Charsets.ISO_8859_1)

    private fun uint32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)

    private fun uint64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        repeat(LongSize) { index ->
            value = (value shl 8) or (bytes[offset + index].toLong() and 0xff)
        }
        return value
    }

    private data class Box(
        val type: String,
        val start: Int,
        val headerSize: Int,
        val size: Int,
    ) {
        val contentStart: Int = start + headerSize
        val end: Int = start + size
    }

    private data class AacConfig(
        val profile: Int,
        val sampleRateIndex: Int,
        val channelConfig: Int,
    )

    private data class StscEntry(
        val firstChunk: Int,
        val samplesPerChunk: Int,
    )

    private const val BoxHeaderSize = 8
    private const val ExtendedBoxHeaderSize = 16
    private const val FullBoxHeaderSize = 4
    private const val IntSize = 4
    private const val LongSize = 8
    private const val AudioSampleEntryPayloadSize = 28
    private const val StscEntrySize = 12
    private const val AdtsHeaderSize = 7
    private const val AudioSpecificConfigTag = 0x05
}
