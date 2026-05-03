package moe.antimony.hoshi.features.sasayaki

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import moe.antimony.hoshi.importing.ImportFileType
import moe.antimony.hoshi.importing.validateImportFile
import java.io.File

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

private fun ContentResolver.displayName(uri: Uri): String =
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getString(0)
        } else {
            null
        }
    } ?: uri.lastPathSegment.orEmpty()
