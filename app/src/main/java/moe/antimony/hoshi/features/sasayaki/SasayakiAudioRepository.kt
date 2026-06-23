package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiPlaybackData

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import moe.antimony.hoshi.importing.ImportFileType
import moe.antimony.hoshi.importing.validateImportFile
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel

class SasayakiAudioRepository(private val bookRoot: File) {
    fun importedPlayback(
        playback: SasayakiPlaybackData,
        audioUri: Uri,
        copiedAudioFileName: String? = null,
    ): SasayakiPlaybackData =
        playback.copy(
            audioUri = if (copiedAudioFileName == null) audioUri.toString() else null,
            audioFileName = copiedAudioFileName,
        )

    fun playbackSource(playback: SasayakiPlaybackData): SasayakiPlaybackSource? {
        playback.audioUri?.let { return SasayakiPlaybackSource.ExternalUri(Uri.parse(it)) }
        return audioFile(playback)?.let { SasayakiPlaybackSource.PrivateFile(it) }
    }

    internal fun audiobookChapters(
        playback: SasayakiPlaybackData,
        contentResolver: ContentResolver,
    ): List<SasayakiAudiobookChapter> =
        audiobookChapters(
            playback = playback,
            openExternalAudio = { uriString -> contentResolver.openSeekableAudioChannel(Uri.parse(uriString)) },
        )

    internal fun audiobookChapters(
        playback: SasayakiPlaybackData,
        openExternalAudio: (String) -> SeekableByteChannel? = { null },
    ): List<SasayakiAudiobookChapter> =
        try {
            playback.audioUri?.let { uriString ->
                openExternalAudio(uriString)?.use { channel ->
                    return SasayakiAudiobookChapters.parse(channel)
                }
            }
            audioFile(playback)?.let(SasayakiAudiobookChapters::parse).orEmpty()
        } catch (_: Exception) {
            emptyList()
        }

    internal fun audiobookMetadata(
        playback: SasayakiPlaybackData,
        context: Context,
    ): SasayakiAudiobookMetadata =
        audiobookMetadata(playback) { source ->
            AndroidSasayakiAudiobookMetadataReader.read(context, source)
        }

    internal fun audiobookMetadata(
        playback: SasayakiPlaybackData,
        readMetadata: (SasayakiPlaybackSource) -> SasayakiAudiobookMetadata,
    ): SasayakiAudiobookMetadata {
        val source = runCatching { playbackSource(playback) }.getOrNull()
            ?: return SasayakiAudiobookMetadata.Empty
        return runCatching {
            readMetadata(source).normalized()
        }.getOrDefault(SasayakiAudiobookMetadata.Empty)
    }

    fun clearAudioSource(playback: SasayakiPlaybackData, contentResolver: ContentResolver) {
        deleteAudio(playback)
        playback.audioUri?.let { uriString ->
            runCatching {
                contentResolver.releasePersistableUriPermission(
                    Uri.parse(uriString),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }

    fun storageSummary(playback: SasayakiPlaybackData): String =
        when {
            playback.audioFileName != null -> "Copied to app storage. The original audiobook file can be deleted."
            playback.audioUri != null -> "Linked to the external audiobook file. Keep the original file available."
            else -> "Select an .mp3 or .m4b audiobook"
        }

    fun audioFile(playback: SasayakiPlaybackData): File? {
        val fileName = playback.audioFileName ?: return null
        val audioRoot = audioDirectory().canonicalFile
        val file = audioRoot.resolve(fileName).canonicalFile
        if (file.path != audioRoot.path && !file.path.startsWith(audioRoot.path + File.separator)) return null
        return file.takeIf { it.isFile }
    }

    fun deleteAudio(playback: SasayakiPlaybackData): Boolean =
        audioFile(playback)?.delete() == true

    fun importAudio(contentResolver: ContentResolver, uri: Uri): String {
        contentResolver.validateImportFile(uri, ImportFileType.SasayakiAudiobook)
        val displayName = contentResolver.displayName(uri)
        val extension = displayName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
            .takeIf { it == "mp3" || it == "m4b" }
            ?: "m4b"
        val targetName = "sasayaki_audio.$extension"
        val target = audioDirectory().resolve(targetName)
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open selected audio file." }
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return targetName
    }

    private fun audioDirectory(): File =
        bookRoot.resolve("Sasayaki").also { it.mkdirs() }
}

internal data class SasayakiAudiobookMetadata(
    val title: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val author: String? = null,
    val artworkData: ByteArray? = null,
) {
    fun normalized(): SasayakiAudiobookMetadata =
        SasayakiAudiobookMetadata(
            title = title.normalizedMetadataText(),
            artist = firstNonBlankMetadataText(artist, albumArtist, author),
            artworkData = artworkData,
        )

    companion object {
        val Empty = SasayakiAudiobookMetadata()
    }
}

private object AndroidSasayakiAudiobookMetadataReader {
    fun read(context: Context, source: SasayakiPlaybackSource): SasayakiAudiobookMetadata {
        val retriever = MediaMetadataRetriever()
        try {
            when (source) {
                is SasayakiPlaybackSource.ExternalUri -> retriever.setDataSource(context, source.uri)
                is SasayakiPlaybackSource.PrivateFile -> retriever.setDataSource(source.file.absolutePath)
            }
            return SasayakiAudiobookMetadata(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                author = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR),
                artworkData = retriever.embeddedPicture,
            )
        } finally {
            runCatching { retriever.release() }
        }
    }
}

private fun firstNonBlankMetadataText(vararg values: String?): String? =
    values.firstNotNullOfOrNull(String?::normalizedMetadataText)

private fun String?.normalizedMetadataText(): String? =
    this?.trim()?.takeIf { it.isNotBlank() }

private fun ContentResolver.displayName(uri: Uri): String =
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getString(0)
        } else {
            null
        }
    } ?: uri.lastPathSegment.orEmpty()

private fun ContentResolver.openSeekableAudioChannel(uri: Uri): SeekableByteChannel? {
    val descriptor = openFileDescriptor(uri, "r") ?: return null
    return ParcelFileDescriptorSeekableByteChannel(descriptor)
}

private class ParcelFileDescriptorSeekableByteChannel(
    private val descriptor: ParcelFileDescriptor,
) : SeekableByteChannel {
    private val channel = FileInputStream(descriptor.fileDescriptor).channel

    override fun read(dst: ByteBuffer): Int = channel.read(dst)

    override fun write(src: ByteBuffer): Int {
        throw NonWritableChannelException()
    }

    override fun position(): Long = channel.position()

    override fun position(newPosition: Long): SeekableByteChannel {
        channel.position(newPosition)
        return this
    }

    override fun size(): Long = channel.size()

    override fun truncate(size: Long): SeekableByteChannel {
        throw NonWritableChannelException()
    }

    override fun isOpen(): Boolean = channel.isOpen

    override fun close() {
        runCatching { channel.close() }
        descriptor.close()
    }
}
