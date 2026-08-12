package moe.antimony.hoshi.features.sasayaki

import java.io.ByteArrayOutputStream

internal data class SasayakiChapterFixture(
    val startSeconds: Double,
    val title: String,
)

internal fun minimalMp4WithChpl(
    durationSeconds: Double,
    chapters: List<SasayakiChapterFixture>,
): ByteArray =
    buildBytes {
        writeBox("ftyp") {
            writeAscii("M4A ")
            writeUInt32(0)
            writeAscii("M4A ")
        }
        writeBox("moov") {
            writeMvhd(durationSeconds)
            writeBox("udta") {
                writeChpl(chapters)
            }
        }
    }

internal fun minimalMp4WithMetadata(
    title: String? = null,
    artist: String? = null,
    albumArtist: String? = null,
    artworkData: ByteArray? = null,
): ByteArray =
    buildBytes {
        writeBox("ftyp") {
            writeAscii("M4A ")
            writeUInt32(0)
            writeAscii("M4A ")
        }
        writeBox("moov") {
            writeBox("udta") {
                writeMeta {
                    writeBox("ilst") {
                        title?.let { writeTextMetadataItem("\u00a9nam", it) }
                        artist?.let { writeTextMetadataItem("\u00a9ART", it) }
                        albumArtist?.let { writeTextMetadataItem("aART", it) }
                        artworkData?.let { writeBinaryMetadataItem("covr", dataType = 13, it) }
                    }
                }
            }
        }
    }

internal fun minimalMp4WithInfo(
    durationSeconds: Double,
    chapters: List<SasayakiChapterFixture>,
    title: String? = null,
    artist: String? = null,
    albumArtist: String? = null,
    artworkData: ByteArray? = null,
): ByteArray =
    buildBytes {
        writeBox("ftyp") {
            writeAscii("M4A ")
            writeUInt32(0)
            writeAscii("M4A ")
        }
        writeBox("moov") {
            writeMvhd(durationSeconds)
            writeBox("udta") {
                writeChpl(chapters)
                writeMeta {
                    writeBox("ilst") {
                        title?.let { writeTextMetadataItem("\u00a9nam", it) }
                        artist?.let { writeTextMetadataItem("\u00a9ART", it) }
                        albumArtist?.let { writeTextMetadataItem("aART", it) }
                        artworkData?.let { writeBinaryMetadataItem("covr", dataType = 13, it) }
                    }
                }
            }
        }
    }

private fun ByteArrayOutputStream.writeMvhd(durationSeconds: Double) {
    writeBox("mvhd") {
        writeUInt32(0)
        writeUInt32(0)
        writeUInt32(0)
        writeUInt32(1000)
        writeUInt32((durationSeconds * 1000.0).toLong())
        write(ByteArray(80))
    }
}

private fun ByteArrayOutputStream.writeChpl(chapters: List<SasayakiChapterFixture>) {
    writeBox("chpl") {
        writeUInt32(0x01000000)
        writeUInt32(0)
        write(chapters.size)
        chapters.forEach { chapter ->
            writeUInt64((chapter.startSeconds * 10_000_000.0).toLong())
            val titleBytes = chapter.title.toByteArray(Charsets.UTF_8)
            write(titleBytes.size)
            write(titleBytes)
        }
    }
}

private fun ByteArrayOutputStream.writeMeta(writeContent: ByteArrayOutputStream.() -> Unit) {
    writeBox("meta") {
        writeUInt32(0)
        writeContent()
    }
}

private fun ByteArrayOutputStream.writeTextMetadataItem(type: String, value: String) {
    writeBinaryMetadataItem(type, dataType = 1, value.toByteArray(Charsets.UTF_8))
}

private fun ByteArrayOutputStream.writeBinaryMetadataItem(
    type: String,
    dataType: Int,
    value: ByteArray,
) {
    writeBox(type) {
        writeBox("data") {
            writeUInt32(dataType.toLong())
            writeUInt32(0)
            write(value)
        }
    }
}

private fun buildBytes(writeContent: ByteArrayOutputStream.() -> Unit): ByteArray =
    ByteArrayOutputStream().apply(writeContent).toByteArray()

private fun ByteArrayOutputStream.writeBox(type: String, writeContent: ByteArrayOutputStream.() -> Unit) {
    val content = buildBytes(writeContent)
    writeUInt32(content.size + 8L)
    writeAscii(type)
    write(content)
}

private fun ByteArrayOutputStream.writeAscii(value: String) {
    write(value.toByteArray(Charsets.ISO_8859_1))
}

private fun ByteArrayOutputStream.writeUInt32(value: Long) {
    write(byteArrayOf(
        ((value ushr 24) and 0xff).toByte(),
        ((value ushr 16) and 0xff).toByte(),
        ((value ushr 8) and 0xff).toByte(),
        (value and 0xff).toByte(),
    ))
}

private fun ByteArrayOutputStream.writeUInt64(value: Long) {
    write(byteArrayOf(
        ((value ushr 56) and 0xff).toByte(),
        ((value ushr 48) and 0xff).toByte(),
        ((value ushr 40) and 0xff).toByte(),
        ((value ushr 32) and 0xff).toByte(),
        ((value ushr 24) and 0xff).toByte(),
        ((value ushr 16) and 0xff).toByte(),
        ((value ushr 8) and 0xff).toByte(),
        (value and 0xff).toByte(),
    ))
}
