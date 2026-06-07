package moe.antimony.hoshi.importing

import androidx.annotation.StringRes
import moe.antimony.hoshi.R

data class ImportFileType(
    val description: String,
    val extensions: List<String>,
    val mimeTypes: Array<String>,
    @StringRes val unsupportedMessageRes: Int,
) {
    fun matchesDisplayName(displayName: String): Boolean {
        val extension = displayName
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
        return extension in extensions
    }

    fun unsupportedFileError(displayName: String?): UnsupportedImportFileTypeException {
        val extensionList = extensions.joinToString(" or ") { ".$it" }
        return UnsupportedImportFileTypeException("Select an $extensionList $description file.", unsupportedMessageRes)
    }

    companion object {
        val Epub = ImportFileType(
            description = "EPUB book",
            extensions = listOf("epub"),
            mimeTypes = arrayOf("application/epub+zip", "application/octet-stream"),
            unsupportedMessageRes = R.string.import_select_epub_book,
        )

        val SasayakiSubtitle = ImportFileType(
            description = "subtitle",
            extensions = listOf("srt"),
            mimeTypes = arrayOf("application/x-subrip", "application/srt", "text/srt", "text/plain", "application/octet-stream"),
            unsupportedMessageRes = R.string.import_select_srt_subtitle,
        )

        val SasayakiAudiobook = ImportFileType(
            description = "audiobook",
            extensions = listOf("mp3", "m4b"),
            mimeTypes = arrayOf("audio/mpeg", "audio/mp4", "audio/x-m4b", "application/octet-stream"),
            unsupportedMessageRes = R.string.import_select_audiobook,
        )

        val LocalAudioDatabase = ImportFileType(
            description = "audio database",
            extensions = listOf("db"),
            mimeTypes = arrayOf("*/*"),
            unsupportedMessageRes = R.string.import_select_audio_database,
        )

        val DictionaryArchive = ImportFileType(
            description = "dictionary archive",
            extensions = listOf("zip"),
            mimeTypes = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"),
            unsupportedMessageRes = R.string.import_select_dictionary_archive,
        )

        val HoshiBackup = ImportFileType(
            description = "Hoshi backup",
            extensions = listOf("hoshi"),
            mimeTypes = arrayOf("application/octet-stream", "application/zip", "*/*"),
            unsupportedMessageRes = R.string.import_select_hoshi_backup,
        )

        val TtuBookDataBackup = ImportFileType(
            description = "TTU bookdata backup",
            extensions = listOf("zip"),
            mimeTypes = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"),
            unsupportedMessageRes = R.string.import_select_ttu_bookdata_backup,
        )

        val ReaderFont = ImportFileType(
            description = "font",
            extensions = listOf("ttf", "otf", "woff", "woff2"),
            mimeTypes = arrayOf(
                "font/ttf",
                "font/otf",
                "font/woff",
                "font/woff2",
                "application/font-woff",
                "application/x-font-ttf",
                "application/x-font-otf",
                "application/vnd.ms-opentype",
                "application/octet-stream",
            ),
            unsupportedMessageRes = R.string.import_select_font,
        )
    }
}

class UnsupportedImportFileTypeException(
    message: String,
    @StringRes val messageRes: Int,
) : IllegalArgumentException(message)
