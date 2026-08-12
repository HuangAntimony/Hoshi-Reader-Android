package moe.antimony.hoshi.features.sasayaki

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files

internal data class SasayakiAudiobookMp4Info(
    val metadata: SasayakiAudiobookMetadata = SasayakiAudiobookMetadata.Empty,
    val chapters: List<SasayakiAudiobookChapter> = emptyList(),
    val durationSeconds: Double? = null,
)

internal object SasayakiAudiobookMp4 {
    fun parse(file: File): SasayakiAudiobookMp4Info? =
        try {
            if (!file.isFile) return null
            Files.newByteChannel(file.toPath()).use(::parse)
        } catch (_: Exception) {
            null
        }

    fun parse(channel: SeekableByteChannel): SasayakiAudiobookMp4Info? =
        try {
            Mp4AudiobookReader(channel).read()
        } catch (_: Exception) {
            null
        }
}

private class Mp4AudiobookReader(
    private val input: SeekableByteChannel,
) {
    fun read(): SasayakiAudiobookMp4Info? {
        input.position(0)
        val moov = childBox(start = 0L, end = input.size(), type = "moov") ?: return null
        val durationSeconds = childBox(moov, "mvhd")?.let(::readMovieDurationSeconds)
        val udta = childBox(moov, "udta")
        val metadata = readMetadata(
            udta?.let { childBox(it, "meta") } ?: childBox(moov, "meta"),
        )
        val rawChapters = udta?.let { childBox(it, "chpl") }?.let(::readChpl).orEmpty()
        val chapters = rawChapters.mapIndexed { index, chapter ->
            SasayakiAudiobookChapter(
                index = index,
                title = chapter.title,
                startSeconds = chapter.startSeconds,
                endSeconds = rawChapters.getOrNull(index + 1)?.startSeconds
                    ?: durationSeconds?.takeIf { it >= chapter.startSeconds },
            )
        }
        return SasayakiAudiobookMp4Info(
            metadata = metadata,
            chapters = chapters,
            durationSeconds = durationSeconds,
        )
    }

    private fun readMetadata(meta: Mp4Box?): SasayakiAudiobookMetadata {
        meta ?: return SasayakiAudiobookMetadata.Empty
        if (meta.contentStart + FullBoxHeaderSize > meta.end) return SasayakiAudiobookMetadata.Empty
        val ilst = childBox(
            start = meta.contentStart + FullBoxHeaderSize,
            end = meta.end,
            type = "ilst",
        ) ?: return SasayakiAudiobookMetadata.Empty

        var title: String? = null
        var artist: String? = null
        var albumArtist: String? = null
        var artworkData: ByteArray? = null
        childBoxes(start = ilst.contentStart, end = ilst.end).forEach { item ->
            val data = readMetadataData(item) ?: return@forEach
            when (item.type) {
                Mp4TitleItem -> title = title ?: data.utf8Text()
                Mp4ArtistItem -> artist = artist ?: data.utf8Text()
                Mp4AlbumArtistItem -> albumArtist = albumArtist ?: data.utf8Text()
                Mp4CoverItem -> artworkData = artworkData ?: data.bytes.takeIf { it.isNotEmpty() }
            }
        }
        return SasayakiAudiobookMetadata(
            title = title,
            artist = artist,
            albumArtist = albumArtist,
            artworkData = artworkData,
        )
    }

    private fun readMetadataData(item: Mp4Box): Mp4MetadataData? {
        val data = childBox(item, "data") ?: return null
        if (data.contentStart + MetadataDataHeaderSize > data.end) return null
        input.position(data.contentStart + MetadataDataHeaderSize)
        val byteCount = data.end - input.position()
        if (byteCount <= 0L || byteCount > MaxMetadataDataBytes) return null
        return Mp4MetadataData(ByteArray(byteCount.toInt()).also { input.readFully(it) })
    }

    private fun readMovieDurationSeconds(box: Mp4Box): Double? {
        input.position(box.contentStart)
        val version = input.readUnsignedByte()
        input.skip(FullBoxFlagsSize)
        return when (version) {
            0 -> {
                input.skip(IntSize + IntSize)
                val timescale = input.readUInt32()
                val duration = input.readUInt32()
                durationSeconds(duration = duration, timescale = timescale)
            }
            1 -> {
                input.skip(LongSize + LongSize)
                val timescale = input.readUInt32()
                val duration = input.readUInt64()
                durationSeconds(duration = duration, timescale = timescale)
            }
            else -> null
        }
    }

    private fun durationSeconds(duration: Long, timescale: Long): Double? =
        timescale.takeIf { it > 0L }
            ?.let { duration.toDouble() / it.toDouble() }
            ?.takeIf { it.isFinite() && it > 0.0 }

    private fun readChpl(box: Mp4Box): List<RawChapter> {
        if (box.contentStart + ChplHeaderSize > box.end) return emptyList()
        input.position(box.contentStart + FullBoxHeaderSize + IntSize)
        val count = input.readUnsignedByte()
        val chapters = mutableListOf<RawChapter>()
        repeat(count) {
            if (input.position() + LongSize + ByteSize > box.end) return@repeat
            val startSeconds = input.readUInt64().toDouble() / ChplTimeUnitsPerSecond
            val titleLength = input.readUnsignedByte()
            if (input.position() + titleLength > box.end) return@repeat
            val title = ByteArray(titleLength).also { input.readFully(it) }
                .toString(Charsets.UTF_8)
                .trim()
                .takeIf { it.isNotEmpty() }
                ?: return@repeat
            chapters += RawChapter(startSeconds = startSeconds, title = title)
        }
        return chapters.sortedBy { it.startSeconds }
    }

    private fun childBox(parent: Mp4Box, type: String): Mp4Box? =
        childBox(start = parent.contentStart, end = parent.end, type = type)

    private fun childBox(start: Long, end: Long, type: String): Mp4Box? =
        childBoxes(start = start, end = end).firstOrNull { it.type == type }

    private fun childBoxes(start: Long, end: Long): List<Mp4Box> {
        val boxes = mutableListOf<Mp4Box>()
        var position = start
        while (position + BoxHeaderSize <= end) {
            val box = readBox(position, end) ?: break
            boxes += box
            position = box.end
        }
        return boxes
    }

    private fun readBox(position: Long, parentEnd: Long): Mp4Box? {
        input.position(position)
        val shortSize = input.readUInt32()
        val type = input.readAscii(IntSize)
        val headerSize: Long
        val size: Long
        if (shortSize == 1L) {
            headerSize = ExtendedBoxHeaderSize
            size = input.readUInt64()
        } else {
            headerSize = BoxHeaderSize
            size = if (shortSize == 0L) parentEnd - position else shortSize
        }
        if (size < headerSize || position + size > parentEnd) return null
        return Mp4Box(type = type, start = position, headerSize = headerSize, size = size)
    }

    private fun SeekableByteChannel.skip(bytes: Int) {
        position(position() + bytes)
    }

    private fun SeekableByteChannel.readAscii(length: Int): String =
        ByteArray(length).also { readFully(it) }.toString(Charsets.ISO_8859_1)

    private fun SeekableByteChannel.readUInt32(): Long {
        var value = 0L
        repeat(IntSize) { value = (value shl 8) or readUnsignedByte().toLong() }
        return value
    }

    private fun SeekableByteChannel.readUInt64(): Long {
        var value = 0L
        repeat(LongSize) { value = (value shl 8) or readUnsignedByte().toLong() }
        return value
    }

    private fun SeekableByteChannel.readUnsignedByte(): Int {
        val buffer = ByteBuffer.allocate(ByteSize)
        if (read(buffer) != ByteSize) throw IllegalArgumentException("Unexpected end of MP4 box")
        buffer.flip()
        return buffer.get().toInt() and 0xff
    }

    private fun SeekableByteChannel.readFully(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            if (read(buffer) < 0) throw IllegalArgumentException("Unexpected end of MP4 box")
        }
    }
}

private data class Mp4MetadataData(val bytes: ByteArray) {
    fun utf8Text(): String? = bytes.toString(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
}

private data class Mp4Box(
    val type: String,
    val start: Long,
    val headerSize: Long,
    val size: Long,
) {
    val contentStart: Long = start + headerSize
    val end: Long = start + size
}

private data class RawChapter(
    val startSeconds: Double,
    val title: String,
)

private const val Mp4TitleItem = "\u00a9nam"
private const val Mp4ArtistItem = "\u00a9ART"
private const val Mp4AlbumArtistItem = "aART"
private const val Mp4CoverItem = "covr"
private const val BoxHeaderSize = 8L
private const val ExtendedBoxHeaderSize = 16L
private const val FullBoxHeaderSize = 4L
private const val FullBoxFlagsSize = 3
private const val MetadataDataHeaderSize = 8L
private const val ChplHeaderSize = 9L
private const val IntSize = 4
private const val LongSize = 8
private const val ByteSize = 1
private const val ChplTimeUnitsPerSecond = 10_000_000.0
private const val MaxMetadataDataBytes = 20L * 1024L * 1024L
