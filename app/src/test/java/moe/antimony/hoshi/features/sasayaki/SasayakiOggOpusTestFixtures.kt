package moe.antimony.hoshi.features.sasayaki

import java.io.ByteArrayOutputStream
import java.util.Base64

internal fun minimalOggOpusWithComments(
    comments: List<String>,
    splitTagsAcrossPages: Boolean = false,
    omitContinuationFlag: Boolean = false,
    channelMappingFamily: Int = 0,
    preSkip: Int = 0,
    finalGranulePosition: Long? = null,
    durationTailPrefix: ByteArray = byteArrayOf(),
    finalPagePayloadSize: Int = 1,
): ByteArray {
    val opusHead = ByteArrayOutputStream().apply {
        write("OpusHead".toByteArray(Charsets.US_ASCII))
        write(1)
        write(2)
        writeLe16(preSkip)
        writeLe32(48_000)
        writeLe16(0)
        write(channelMappingFamily)
        if (channelMappingFamily == 1) {
            write(1)
            write(1)
            write(byteArrayOf(0, 1))
        }
    }.toByteArray()
    val opusTags = ByteArrayOutputStream().apply {
        write("OpusTags".toByteArray(Charsets.US_ASCII))
        val vendor = if (splitTagsAcrossPages) "fixture-" + "x".repeat(250) else "fixture"
        writeLe32(vendor.toByteArray().size)
        write(vendor.toByteArray())
        writeLe32(comments.size)
        comments.forEach { comment ->
            val bytes = comment.toByteArray(Charsets.UTF_8)
            writeLe32(bytes.size)
            write(bytes)
        }
    }.toByteArray()

    return ByteArrayOutputStream().apply {
        writeOggPage(
            headerType = 0x02,
            serial = 7,
            sequence = 0,
            lacingValues = byteArrayOf(opusHead.size.toByte()),
            payload = opusHead,
        )
        if (splitTagsAcrossPages) {
            writeOggPage(
                headerType = 0,
                serial = 7,
                sequence = 1,
                lacingValues = byteArrayOf(0xff.toByte()),
                payload = opusTags.copyOfRange(0, 255),
            )
            val remainder = opusTags.copyOfRange(255, opusTags.size)
            writeOggPage(
                headerType = if (omitContinuationFlag) 0 else 0x01,
                serial = 7,
                sequence = 2,
                lacingValues = packetLacingValues(remainder.size),
                payload = remainder,
            )
        } else {
            writeOggPage(
                headerType = 0,
                serial = 7,
                sequence = 1,
                lacingValues = packetLacingValues(opusTags.size),
                payload = opusTags,
            )
        }
        finalGranulePosition?.let { granulePosition ->
            write(durationTailPrefix)
            val finalPayload = ByteArray(finalPagePayloadSize)
            writeOggPage(
                headerType = 0x04,
                granulePosition = granulePosition,
                serial = 7,
                sequence = if (splitTagsAcrossPages) 3 else 2,
                lacingValues = if (finalPagePayloadSize == 255 * 255) {
                    ByteArray(255) { 0xff.toByte() }
                } else {
                    packetLacingValues(finalPagePayloadSize)
                },
                payload = finalPayload,
            )
        }
    }.toByteArray()
}

internal fun oggTailPrefixWithFalseCapturePattern(size: Int = 65_000): ByteArray =
    ByteArray(size).also { bytes ->
        val offset = 10
        "OggS".toByteArray(Charsets.US_ASCII).copyInto(bytes, destinationOffset = offset)
        bytes[offset + 4] = 0
        bytes[offset + 26] = 0xff.toByte()
        repeat(255) { index -> bytes[offset + 27 + index] = 0xff.toByte() }
    }

private fun packetLacingValues(packetSize: Int): ByteArray {
    val fullSegments = packetSize / 255
    val remainder = packetSize % 255
    return ByteArray(fullSegments + 1) { index ->
        if (index < fullSegments) 0xff.toByte() else remainder.toByte()
    }
}

internal fun flacPictureComment(
    pictureData: ByteArray,
    pictureType: Int = 3,
    mimeType: String = "image/jpeg",
): String {
    val block = ByteArrayOutputStream().apply {
        writeBe32(pictureType)
        val mimeBytes = mimeType.toByteArray(Charsets.US_ASCII)
        writeBe32(mimeBytes.size)
        write(mimeBytes)
        writeBe32(0)
        writeBe32(0)
        writeBe32(0)
        writeBe32(0)
        writeBe32(0)
        writeBe32(pictureData.size)
        write(pictureData)
    }.toByteArray()
    return "METADATA_BLOCK_PICTURE=${Base64.getEncoder().encodeToString(block)}"
}

private fun ByteArrayOutputStream.writeOggPage(
    headerType: Int,
    granulePosition: Long = 0,
    serial: Int,
    sequence: Int,
    lacingValues: ByteArray,
    payload: ByteArray,
) {
    val page = ByteArrayOutputStream().apply {
        write("OggS".toByteArray(Charsets.US_ASCII))
        write(0)
        write(headerType)
        writeLe64(granulePosition)
        writeLe32(serial)
        writeLe32(sequence)
        writeLe32(0)
        write(lacingValues.size)
        write(lacingValues)
        write(payload)
    }.toByteArray()
    val checksum = oggCrc(page)
    repeat(4) { shift -> page[22 + shift] = ((checksum ushr (shift * 8)) and 0xff).toByte() }
    write(page)
}

private fun oggCrc(bytes: ByteArray): Int {
    var crc = 0
    bytes.forEach { byte ->
        crc = crc xor ((byte.toInt() and 0xff) shl 24)
        repeat(8) {
            crc = if (crc < 0) (crc shl 1) xor 0x04c11db7 else crc shl 1
        }
    }
    return crc
}

private fun ByteArrayOutputStream.writeLe16(value: Int) {
    write(value and 0xff)
    write((value ushr 8) and 0xff)
}

private fun ByteArrayOutputStream.writeLe32(value: Int) {
    repeat(4) { shift -> write((value ushr (shift * 8)) and 0xff) }
}

private fun ByteArrayOutputStream.writeLe64(value: Long) {
    repeat(8) { shift -> write(((value ushr (shift * 8)) and 0xff).toInt()) }
}

private fun ByteArrayOutputStream.writeBe32(value: Int) {
    repeat(4) { index -> write((value ushr ((3 - index) * 8)) and 0xff) }
}
