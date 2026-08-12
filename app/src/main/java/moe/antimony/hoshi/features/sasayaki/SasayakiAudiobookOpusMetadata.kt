package moe.antimony.hoshi.features.sasayaki

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.util.Base64
import java.util.Locale

internal data class SasayakiAudiobookOpusInfo(
    val metadata: SasayakiAudiobookMetadata = SasayakiAudiobookMetadata.Empty,
    val chapters: List<SasayakiAudiobookChapter> = emptyList(),
)

internal object SasayakiAudiobookOpusMetadata {
    fun parse(file: File): SasayakiAudiobookOpusInfo? =
        try {
            if (!file.isFile) return null
            Files.newByteChannel(file.toPath()).use(::parse)
        } catch (_: Exception) {
            null
        }

    fun parse(channel: SeekableByteChannel): SasayakiAudiobookOpusInfo? =
        try {
            OggOpusReader(channel).read()
        } catch (_: Exception) {
            null
        }
}

private class OggOpusReader(
    private val input: SeekableByteChannel,
) {
    fun read(): SasayakiAudiobookOpusInfo? {
        input.position(0)
        var opusSerial: Int? = null
        var nextSequence: Int? = null
        val packetBuffer = ByteArrayOutputStream()
        while (input.position() + OggFixedHeaderSize <= input.size()) {
            val header = input.readBytes(OggFixedHeaderSize)
            if (!header.copyOfRange(0, OggCapturePattern.size).contentEquals(OggCapturePattern)) return null
            if (header[4].toInt() != 0) return null
            val serial = ByteBuffer.wrap(header, 14, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val headerType = header[5].toInt() and 0xff
            val sequence = ByteBuffer.wrap(header, 18, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val expectedChecksum = ByteBuffer.wrap(header, 22, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val segmentCount = header[26].toInt() and 0xff
            val lacingValues = input.readBytes(segmentCount)
            val payloadSize = lacingValues.sumOf { it.toInt() and 0xff }
            if (payloadSize > MaxOggPagePayloadBytes) return null
            val payload = input.readBytes(payloadSize)
            if (!validOggChecksum(header, lacingValues, payload, expectedChecksum)) {
                return opusSerial?.let { SasayakiAudiobookOpusInfo() }
            }

            if (opusSerial == null) {
                if (headerType and OggBosFlag == 0 || headerType and OggContinuedFlag != 0) continue
                val firstPacketSize = firstCompletedPacketSize(lacingValues) ?: continue
                if (firstPacketSize < MinimumOpusHeadPacketSize || firstPacketSize != payload.size) continue
                val firstPacket = payload.copyOfRange(0, firstPacketSize)
                if (!firstPacket.startsWith(OpusHeadMagic)) continue
                opusSerial = serial
                nextSequence = sequence + 1
                continue
            }
            if (serial != opusSerial) continue
            if (sequence != nextSequence) return SasayakiAudiobookOpusInfo()
            nextSequence = sequence + 1
            val isContinued = headerType and OggContinuedFlag != 0
            if (isContinued != (packetBuffer.size() > 0)) return SasayakiAudiobookOpusInfo()

            var payloadOffset = 0
            for (lacingValue in lacingValues) {
                val segmentSize = lacingValue.toInt() and 0xff
                if (packetBuffer.size() + segmentSize > MaxOpusPacketBytes) return null
                packetBuffer.write(payload, payloadOffset, segmentSize)
                payloadOffset += segmentSize
                if (segmentSize < MaxOggSegmentBytes) {
                    val packet = packetBuffer.toByteArray()
                    packetBuffer.reset()
                    when {
                        packet.startsWith(OpusTagsMagic) -> return parseTags(packet)
                        else -> return SasayakiAudiobookOpusInfo()
                    }
                }
            }
        }
        return opusSerial?.let { SasayakiAudiobookOpusInfo() }
    }

    private fun parseTags(packet: ByteArray): SasayakiAudiobookOpusInfo {
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(OpusTagsMagic.size)
        val vendorLength = buffer.readBoundedLength(MaxOpusPacketBytes) ?: return SasayakiAudiobookOpusInfo()
        if (buffer.remaining() < vendorLength + IntBytes) return SasayakiAudiobookOpusInfo()
        buffer.position(buffer.position() + vendorLength)
        val commentCount = buffer.int
        if (commentCount !in 0..MaxVorbisCommentCount) return SasayakiAudiobookOpusInfo()

        val comments = mutableListOf<Pair<String, String>>()
        repeat(commentCount) {
            val length = buffer.readBoundedLength(MaxOpusPacketBytes) ?: return SasayakiAudiobookOpusInfo()
            if (buffer.remaining() < length) return SasayakiAudiobookOpusInfo()
            val text = ByteArray(length).also(buffer::get).toString(Charsets.UTF_8)
            val separator = text.indexOf('=')
            if (separator > 0) {
                comments += text.substring(0, separator).uppercase(Locale.ROOT) to text.substring(separator + 1)
            }
        }
        return SasayakiAudiobookOpusInfo(
            metadata = readMetadata(comments),
            chapters = readChapters(comments),
        )
    }

    private fun readMetadata(comments: List<Pair<String, String>>): SasayakiAudiobookMetadata {
        fun firstValue(key: String): String? = comments
            .firstOrNull { (name, value) -> name == key && value.isNotBlank() }
            ?.second
        val pictures = comments.mapNotNull { (name, value) ->
            when (name) {
                "METADATA_BLOCK_PICTURE" -> parseFlacPicture(value)
                "COVERART" -> decodeBase64(value)
                    ?.takeIf { it.isNotEmpty() && it.size <= MaxArtworkBytes }
                    ?.let { OpusPicture(type = 0, data = it) }
                else -> null
            }
        }
        return SasayakiAudiobookMetadata(
            title = firstValue("TITLE"),
            artist = firstValue("ARTIST"),
            albumArtist = firstValue("ALBUMARTIST"),
            author = firstValue("AUTHOR"),
            artworkData = pictures.firstOrNull { it.type == FrontCoverPictureType }?.data
                ?: pictures.firstOrNull()?.data,
        )
    }

    private fun readChapters(comments: List<Pair<String, String>>): List<SasayakiAudiobookChapter> {
        val values = comments.associate { it.first to it.second }
        val rawChapters = values.mapNotNull { (key, value) ->
            val match = ChapterTimeKey.matchEntire(key) ?: return@mapNotNull null
            val chapterId = match.groupValues[1]
            val startSeconds = parseChapterTime(value) ?: return@mapNotNull null
            val title = values["CHAPTER${chapterId}NAME"]?.trim().orEmpty()
            RawOpusChapter(chapterId.toIntOrNull() ?: return@mapNotNull null, title, startSeconds)
        }.sortedWith(compareBy<RawOpusChapter> { it.startSeconds }.thenBy { it.sourceIndex })

        return rawChapters.mapIndexed { index, chapter ->
            SasayakiAudiobookChapter(
                index = index,
                title = chapter.title,
                startSeconds = chapter.startSeconds,
                endSeconds = rawChapters.getOrNull(index + 1)?.startSeconds,
            )
        }
    }

    private fun parseChapterTime(value: String): Double? {
        val match = ChapterTime.matchEntire(value.trim()) ?: return null
        val hours = match.groupValues[1].toLongOrNull() ?: return null
        val minutes = match.groupValues[2].toIntOrNull()?.takeIf { it < 60 } ?: return null
        val seconds = match.groupValues[3].toIntOrNull()?.takeIf { it < 60 } ?: return null
        val milliseconds = match.groupValues[4].padEnd(3, '0').take(3).toIntOrNull() ?: 0
        return hours * 3600.0 + minutes * 60.0 + seconds + milliseconds / 1000.0
    }

    private fun parseFlacPicture(encoded: String): OpusPicture? {
        val decoded = decodeBase64(encoded) ?: return null
        if (decoded.size > MaxArtworkBytes) return null
        val buffer = ByteBuffer.wrap(decoded).order(ByteOrder.BIG_ENDIAN)
        if (buffer.remaining() < FlacPictureFixedFieldsBytes) return null
        val type = buffer.int
        val mimeLength = buffer.readBoundedLength(MaxPictureTextBytes) ?: return null
        if (buffer.remaining() < mimeLength + IntBytes) return null
        buffer.position(buffer.position() + mimeLength)
        val descriptionLength = buffer.readBoundedLength(MaxPictureTextBytes) ?: return null
        if (buffer.remaining() < descriptionLength + FlacPictureDimensionsBytes + IntBytes) return null
        buffer.position(buffer.position() + descriptionLength + FlacPictureDimensionsBytes)
        val dataLength = buffer.readBoundedLength(MaxArtworkBytes) ?: return null
        if (dataLength <= 0 || buffer.remaining() < dataLength) return null
        return OpusPicture(type = type, data = ByteArray(dataLength).also(buffer::get))
    }

    private fun decodeBase64(value: String): ByteArray? =
        runCatching { Base64.getDecoder().decode(value.trim()) }.getOrNull()
}

private fun ByteBuffer.readBoundedLength(maximum: Int): Int? {
    if (remaining() < IntBytes) return null
    return int.takeIf { it in 0..maximum }
}

private fun SeekableByteChannel.readBytes(count: Int): ByteArray {
    val bytes = ByteArray(count)
    val buffer = ByteBuffer.wrap(bytes)
    while (buffer.hasRemaining()) {
        if (read(buffer) < 0) throw IllegalArgumentException("Unexpected end of Ogg stream")
    }
    return bytes
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && copyOfRange(0, prefix.size).contentEquals(prefix)

private fun firstCompletedPacketSize(lacingValues: ByteArray): Int? {
    var packetSize = 0
    lacingValues.forEach { lacingValue ->
        val segmentSize = lacingValue.toInt() and 0xff
        packetSize += segmentSize
        if (segmentSize < MaxOggSegmentBytes) return packetSize
    }
    return null
}

private fun validOggChecksum(
    header: ByteArray,
    lacingValues: ByteArray,
    payload: ByteArray,
    expected: Int,
): Boolean {
    var crc = 0
    fun update(byte: Int) {
        crc = crc xor ((byte and 0xff) shl 24)
        repeat(8) { crc = if (crc < 0) (crc shl 1) xor OggCrcPolynomial else crc shl 1 }
    }
    header.forEachIndexed { index, byte -> update(if (index in 22..25) 0 else byte.toInt()) }
    lacingValues.forEach { update(it.toInt()) }
    payload.forEach { update(it.toInt()) }
    return crc == expected
}

private data class OpusPicture(val type: Int, val data: ByteArray)

private data class RawOpusChapter(
    val sourceIndex: Int,
    val title: String,
    val startSeconds: Double,
)

private val OggCapturePattern = "OggS".toByteArray(Charsets.US_ASCII)
private val OpusHeadMagic = "OpusHead".toByteArray(Charsets.US_ASCII)
private val OpusTagsMagic = "OpusTags".toByteArray(Charsets.US_ASCII)
private val ChapterTimeKey = Regex("CHAPTER(\\d{3})")
private val ChapterTime = Regex("(\\d+):(\\d{2}):(\\d{2})(?:\\.(\\d{1,3}))?")
private const val OggFixedHeaderSize = 27
private const val OggContinuedFlag = 0x01
private const val OggBosFlag = 0x02
private const val OggCrcPolynomial = 0x04c11db7
private const val MinimumOpusHeadPacketSize = 19
private const val MaxOggSegmentBytes = 255
private const val MaxOggPagePayloadBytes = 255 * 255
private const val MaxOpusPacketBytes = 32 * 1024 * 1024
private const val MaxVorbisCommentCount = 4096
private const val MaxArtworkBytes = 20 * 1024 * 1024
private const val MaxPictureTextBytes = 1024 * 1024
private const val FrontCoverPictureType = 3
private const val FlacPictureFixedFieldsBytes = 8
private const val FlacPictureDimensionsBytes = 16
private const val IntBytes = 4
