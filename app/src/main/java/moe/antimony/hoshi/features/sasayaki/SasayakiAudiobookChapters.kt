package moe.antimony.hoshi.features.sasayaki

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files

internal data class SasayakiAudiobookChapter(
    val index: Int,
    val title: String,
    val startSeconds: Double,
    val endSeconds: Double?,
)

internal object SasayakiAudiobookChapters {
    fun parse(file: File): List<SasayakiAudiobookChapter> {
        try {
            if (!file.isFile) return emptyList()
            return Files.newByteChannel(file.toPath()).use { channel ->
                parse(channel)
            }
        } catch (_: Exception) {
            return emptyList()
        }
    }

    fun parse(channel: SeekableByteChannel): List<SasayakiAudiobookChapter> =
        try {
            Mp4ChapterReader(channel).read()
        } catch (_: Exception) {
            emptyList()
        }

    fun currentChapterAt(
        chapters: List<SasayakiAudiobookChapter>,
        positionSeconds: Double,
    ): SasayakiAudiobookChapter? {
        if (positionSeconds < 0.0) return null
        val lastChapter = chapters.lastOrNull()
        return chapters.lastOrNull { chapter ->
            val endSeconds = chapter.endSeconds
            positionSeconds >= chapter.startSeconds &&
                when {
                    endSeconds == null -> true
                    chapter == lastChapter -> positionSeconds <= endSeconds
                    else -> positionSeconds < endSeconds
                }
        }
    }
}

private class Mp4ChapterReader(
    private val input: SeekableByteChannel,
) {
    fun read(): List<SasayakiAudiobookChapter> {
        val moov = childBox(start = 0L, end = input.size(), type = "moov") ?: return emptyList()
        val movieDurationSeconds = childBox(moov, "mvhd")?.let(::readMovieDurationSeconds)
        val chpl = childBox(moov, "udta")?.let { udta -> childBox(udta, "chpl") } ?: return emptyList()
        val rawChapters = readChpl(chpl)
        return rawChapters.mapIndexed { index, chapter ->
            SasayakiAudiobookChapter(
                index = index,
                title = chapter.title,
                startSeconds = chapter.startSeconds,
                endSeconds = rawChapters.getOrNull(index + 1)?.startSeconds
                    ?: movieDurationSeconds?.takeIf { it >= chapter.startSeconds },
            )
        }
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
        timescale.takeIf { it > 0L }?.let { duration.toDouble() / it.toDouble() }

    private fun readChpl(box: Mp4Box): List<RawChapter> {
        if (box.contentStart + ChplHeaderSize > box.end) return emptyList()
        input.position(box.contentStart)
        input.skip(FullBoxHeaderSize)
        input.skip(IntSize)
        val count = input.readUnsignedByte()
        val chapters = mutableListOf<RawChapter>()
        repeat(count) {
            if (input.position() + LongSize + ByteSize > box.end) return@repeat
            val startSeconds = input.readUInt64().toDouble() / ChplTimeUnitsPerSecond
            val titleLength = input.readUnsignedByte()
            if (input.position() + titleLength > box.end) return@repeat
            val titleBytes = ByteArray(titleLength)
            input.readFully(titleBytes)
            val title = titleBytes.toString(Charsets.UTF_8).ifBlank { return@repeat }
            chapters += RawChapter(
                startSeconds = startSeconds,
                title = title,
            )
        }
        return chapters.sortedBy { it.startSeconds }
    }

    private fun childBox(parent: Mp4Box, type: String): Mp4Box? =
        childBox(start = parent.contentStart, end = parent.end, type = type)

    private fun childBox(start: Long, end: Long, type: String): Mp4Box? {
        var position = start
        while (position + BoxHeaderSize <= end) {
            val box = readBox(position, end) ?: return null
            if (box.type == type) return box
            position = box.end
        }
        return null
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
        return Mp4Box(
            type = type,
            start = position,
            headerSize = headerSize,
            size = size,
        )
    }

    private fun SeekableByteChannel.skip(bytes: Int) {
        position(position() + bytes)
    }

    private fun SeekableByteChannel.readAscii(length: Int): String {
        val bytes = ByteArray(length)
        readFully(bytes)
        return bytes.toString(Charsets.ISO_8859_1)
    }

    private fun SeekableByteChannel.readUInt32(): Long {
        var value = 0L
        repeat(IntSize) {
            value = (value shl 8) or (readUnsignedByte().toLong() and 0xff)
        }
        return value
    }

    private fun SeekableByteChannel.readUInt64(): Long {
        var value = 0L
        repeat(LongSize) {
            value = (value shl 8) or (readUnsignedByte().toLong() and 0xff)
        }
        return value
    }

    private fun SeekableByteChannel.readUnsignedByte(): Int {
        val buffer = ByteBuffer.allocate(ByteSize)
        if (read(buffer) != ByteSize) return 0
        buffer.flip()
        return buffer.get().toInt() and 0xff
    }

    private fun SeekableByteChannel.readFully(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            if (read(buffer) < 0) return
        }
    }
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

private const val BoxHeaderSize = 8L
private const val ExtendedBoxHeaderSize = 16L
private const val FullBoxHeaderSize = 4
private const val FullBoxFlagsSize = 3
private const val ChplHeaderSize = 9
private const val IntSize = 4
private const val LongSize = 8
private const val ByteSize = 1
private const val ChplTimeUnitsPerSecond = 10_000_000.0
