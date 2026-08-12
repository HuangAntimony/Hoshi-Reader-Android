package moe.antimony.hoshi.features.sasayaki

import java.io.File
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
            SasayakiAudiobookOpusMetadata.parse(channel)?.let { return it.chapters }
            channel.position(0)
            SasayakiAudiobookMp4.parse(channel)?.chapters.orEmpty()
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
