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
import java.nio.file.Files

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

    internal fun inspectAudiobook(
        playback: SasayakiPlaybackData,
        context: Context,
    ): SasayakiAudiobookInfo =
        inspectAudiobook(
            playback = playback,
            openExternalAudio = { uriString ->
                context.contentResolver.openSeekableAudioChannel(Uri.parse(uriString))
            },
            readPlatformInfo = { source -> AndroidSasayakiAudiobookMetadataReader.readInfo(context, source) },
        )

    internal fun inspectAudiobook(
        playback: SasayakiPlaybackData,
        openExternalAudio: (String) -> SeekableByteChannel? = { null },
        readPlatformInfo: (SasayakiPlaybackSource) -> SasayakiAudiobookPlatformInfo = {
            SasayakiAudiobookPlatformInfo.Empty
        },
    ): SasayakiAudiobookInfo {
        val privateFile = audioFile(playback)
        val externalUriString = playback.audioUri
        if (privateFile == null && externalUriString == null) return SasayakiAudiobookInfo.Empty
        val parsedInfo = runCatching {
            when {
                externalUriString != null -> openExternalAudio(externalUriString)?.use {
                    inspectSeekableAudiobook(it)
                }
                privateFile != null -> Files.newByteChannel(privateFile.toPath()).use {
                    inspectSeekableAudiobook(it)
                }
                else -> null
            }
        }.getOrNull() ?: SasayakiAudiobookInfo(format = playback.formatHint())
        val nativeInfo = if (parsedInfo.format == SasayakiAudiobookFormat.Unknown) {
            parsedInfo.copy(format = playback.formatHint())
        } else {
            parsedInfo
        }

        fun platformInfo(): SasayakiAudiobookPlatformInfo = runCatching {
            playbackSource(playback)?.let(readPlatformInfo) ?: SasayakiAudiobookPlatformInfo.Empty
        }.getOrDefault(SasayakiAudiobookPlatformInfo.Empty)

        return when (nativeInfo.format) {
            SasayakiAudiobookFormat.Opus -> nativeInfo.copy(
                metadata = nativeInfo.metadata.normalized(),
                durationSeconds = nativeInfo.durationSeconds.validDurationSeconds(),
            )
            SasayakiAudiobookFormat.M4b -> {
                val nativeMetadata = nativeInfo.metadata.normalizedFields()
                if (nativeMetadata.hasAudiobookMetadata()) {
                    nativeInfo.copy(
                        metadata = nativeMetadata.normalized(),
                        durationSeconds = nativeInfo.durationSeconds.validDurationSeconds(),
                    )
                } else {
                    val platformInfo = platformInfo()
                    nativeInfo.copy(
                        metadata = nativeMetadata
                            .mergedWith(platformInfo.metadata.normalizedFields())
                            .normalized(),
                        durationSeconds = nativeInfo.durationSeconds.validDurationSeconds()
                            ?: platformInfo.durationSeconds.validDurationSeconds(),
                    )
                }
            }
            SasayakiAudiobookFormat.Mp3 -> {
                val platformInfo = platformInfo()
                SasayakiAudiobookInfo(
                    format = SasayakiAudiobookFormat.Mp3,
                    metadata = platformInfo.metadata.normalized(),
                    durationSeconds = platformInfo.durationSeconds.validDurationSeconds(),
                )
            }
            SasayakiAudiobookFormat.Unknown -> SasayakiAudiobookInfo.Empty
        }
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
            else -> "Select a .mp3, .m4b, or .opus audiobook"
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
            .takeIf { it in ImportFileType.SasayakiAudiobook.extensions }
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

private fun SasayakiAudiobookMetadata.mergedWith(
    fallback: SasayakiAudiobookMetadata,
): SasayakiAudiobookMetadata =
    SasayakiAudiobookMetadata(
        title = title ?: fallback.title,
        artist = artist ?: fallback.artist,
        albumArtist = albumArtist ?: fallback.albumArtist,
        author = author ?: fallback.author,
        artworkData = artworkData ?: fallback.artworkData,
    )

private fun SasayakiAudiobookMetadata.normalizedFields(): SasayakiAudiobookMetadata =
    SasayakiAudiobookMetadata(
        title = title.normalizedMetadataText(),
        artist = artist.normalizedMetadataText(),
        albumArtist = albumArtist.normalizedMetadataText(),
        author = author.normalizedMetadataText(),
        artworkData = artworkData,
    )

private fun inspectSeekableAudiobook(channel: SeekableByteChannel): SasayakiAudiobookInfo {
    SasayakiAudiobookOpusMetadata.parse(channel)?.let { opus ->
        return SasayakiAudiobookInfo(
            format = SasayakiAudiobookFormat.Opus,
            metadata = opus.metadata,
            chapters = opus.chapters,
            durationSeconds = opus.durationSeconds,
        )
    }
    channel.position(0)
    SasayakiAudiobookMp4.parse(channel)?.let { mp4 ->
        return SasayakiAudiobookInfo(
            format = SasayakiAudiobookFormat.M4b,
            metadata = mp4.metadata,
            chapters = mp4.chapters,
            durationSeconds = mp4.durationSeconds,
        )
    }
    channel.position(0)
    return if (channel.hasMp3Signature()) {
        SasayakiAudiobookInfo(format = SasayakiAudiobookFormat.Mp3)
    } else {
        SasayakiAudiobookInfo.Empty
    }
}

private object AndroidSasayakiAudiobookMetadataReader {
    fun readInfo(context: Context, source: SasayakiPlaybackSource): SasayakiAudiobookPlatformInfo {
        val retriever = MediaMetadataRetriever()
        try {
            when (source) {
                is SasayakiPlaybackSource.ExternalUri -> retriever.setDataSource(context, source.uri)
                is SasayakiPlaybackSource.PrivateFile -> retriever.setDataSource(source.file.absolutePath)
            }
            return SasayakiAudiobookPlatformInfo(
                metadata = SasayakiAudiobookMetadata(
                    title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                    author = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR),
                    artworkData = retriever.embeddedPicture,
                ),
                durationSeconds = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toDoubleOrNull()
                    ?.div(MillisecondsPerSecond),
            )
        } finally {
            runCatching { retriever.release() }
        }
    }
}

private fun SasayakiPlaybackData.formatHint(): SasayakiAudiobookFormat {
    val name = (audioFileName ?: audioUri.orEmpty())
        .substringBefore('#')
        .substringBefore('?')
    return when (name.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
        "opus" -> SasayakiAudiobookFormat.Opus
        "m4b" -> SasayakiAudiobookFormat.M4b
        "mp3" -> SasayakiAudiobookFormat.Mp3
        else -> SasayakiAudiobookFormat.Unknown
    }
}

private fun SeekableByteChannel.hasMp3Signature(): Boolean {
    if (size() < Mp3MinimumSignatureBytes) return false
    val signature = ByteArray(Mp3MinimumSignatureBytes)
    val buffer = ByteBuffer.wrap(signature)
    while (buffer.hasRemaining()) {
        if (read(buffer) < 0) return false
    }
    if (signature.copyOfRange(0, Mp3Id3Magic.size).contentEquals(Mp3Id3Magic)) return true
    val first = signature[0].toInt() and 0xff
    val second = signature[1].toInt() and 0xff
    return first == 0xff && second and Mp3FrameSyncMask == Mp3FrameSyncMask
}

private fun SasayakiAudiobookMetadata.hasAudiobookMetadata(): Boolean =
    title != null || artist != null || albumArtist != null || author != null || artworkData != null

private fun Double?.validDurationSeconds(): Double? =
    this?.takeIf { it.isFinite() && it > 0.0 }

private fun firstNonBlankMetadataText(vararg values: String?): String? =
    values.firstNotNullOfOrNull(String?::normalizedMetadataText)

private fun String?.normalizedMetadataText(): String? =
    this?.trim()?.takeIf { it.isNotBlank() }

private val Mp3Id3Magic = "ID3".toByteArray(Charsets.US_ASCII)
private const val Mp3MinimumSignatureBytes = 3
private const val Mp3FrameSyncMask = 0xe0
private const val MillisecondsPerSecond = 1000.0

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
