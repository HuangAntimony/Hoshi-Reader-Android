package moe.antimony.hoshi.importing

data class ImportFileType(
    val description: String,
    val extensions: List<String>,
    val mimeTypes: Array<String>,
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
        return UnsupportedImportFileTypeException("Select an $extensionList $description file.")
    }

    companion object {
        val Epub = ImportFileType(
            description = "EPUB book",
            extensions = listOf("epub"),
            mimeTypes = arrayOf("application/epub+zip", "application/octet-stream"),
        )

        val SasayakiSubtitle = ImportFileType(
            description = "subtitle",
            extensions = listOf("srt"),
            mimeTypes = arrayOf("application/x-subrip", "application/srt", "text/srt", "text/plain", "application/octet-stream"),
        )

        val SasayakiAudiobook = ImportFileType(
            description = "audiobook",
            extensions = listOf("mp3", "m4b"),
            mimeTypes = arrayOf("audio/mpeg", "audio/mp4", "audio/x-m4b", "application/octet-stream"),
        )

        val LocalAudioDatabase = ImportFileType(
            description = "audio database",
            extensions = listOf("db"),
            mimeTypes = arrayOf("application/vnd.sqlite3", "application/x-sqlite3", "application/octet-stream"),
        )

        val DictionaryArchive = ImportFileType(
            description = "dictionary archive",
            extensions = listOf("zip"),
            mimeTypes = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"),
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
        )
    }
}

class UnsupportedImportFileTypeException(message: String) : IllegalArgumentException(message)
