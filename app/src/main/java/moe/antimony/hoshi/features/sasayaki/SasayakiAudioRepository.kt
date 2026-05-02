package moe.antimony.hoshi.features.sasayaki

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import moe.antimony.hoshi.importing.ImportFileType
import moe.antimony.hoshi.importing.validateImportFile
import java.io.File

class SasayakiAudioRepository(private val bookRoot: File) {
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
