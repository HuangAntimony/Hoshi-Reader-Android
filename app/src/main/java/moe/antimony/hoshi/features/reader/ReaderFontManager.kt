package moe.antimony.hoshi.features.reader

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import moe.antimony.hoshi.di.FilesDir

data class ReaderFontInfo(
    val name: String,
    val fileName: String,
    val file: File,
)

@Singleton
class ReaderFontManager @Inject constructor(
    @param:FilesDir private val filesDir: File,
) {
    private val fontsDirectory = File(filesDir, "Fonts")

    fun importFont(source: File): ReaderFontInfo {
        require(source.name.isSupportedFontFileName()) { "Unsupported font file." }
        fontsDirectory.mkdirs()
        val destination = File(fontsDirectory, source.name)
        source.inputStream().use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        return destination.toFontInfo()
    }

    fun importFont(contentResolver: ContentResolver, uri: Uri): ReaderFontInfo {
        fontsDirectory.mkdirs()
        val fileName = contentResolver.displayName(uri)
        require(fileName.isSupportedFontFileName()) { "Unsupported font file." }
        val destination = File(fontsDirectory, fileName)
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open font file." }
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        return destination.toFontInfo()
    }

    fun storedFonts(): List<ReaderFontInfo> {
        fontsDirectory.mkdirs()
        return fontsDirectory.listFiles()
            ?.filter { it.isFile && !it.isHidden }
            ?.sortedBy { it.nameWithoutExtension }
            ?.map { it.toFontInfo() }
            .orEmpty()
    }

    fun storedFont(name: String): ReaderFontInfo? =
        storedFonts().firstOrNull { it.name == name }

    fun deleteFont(name: String) {
        storedFont(name)?.file?.delete()
    }

    fun isDefaultFont(name: String): Boolean =
        normalizeDefaultFont(name) in defaultFonts

    fun webViewFontUrl(name: String): String? =
        storedFont(name)?.let { "https://appassets.androidplatform.net/fonts/${it.fileName.pathSegmentEncoded()}" }

    fun allFontNames(): List<String> =
        (defaultFonts + storedFonts().map { it.name }).distinct()

    fun cssFontName(name: String): String =
        normalizeDefaultFont(name)

    fun popupFontFaceCss(): String =
        storedFonts().joinToString(separator = "\n") { font ->
            val family = cssFontName(font.name).cssString()
            val source = requireNotNull(webViewFontUrl(font.name)).cssString()
            """
                @font-face {
                    font-family: $family;
                    src: url($source);
                    font-display: swap;
                }
            """.trimIndent()
        }

    fun fontFileForRequest(fileName: String): File? {
        val requested = File(fontsDirectory, fileName)
        val fontsRoot = fontsDirectory.canonicalFile
        val canonical = requested.canonicalFile
        if (canonical.parentFile != fontsRoot || !canonical.isFile) return null
        return canonical
    }

    companion object {
        const val defaultMinchoFont = "Noto Serif CJK JP"
        const val defaultGothicFont = "Noto Sans CJK JP"
        val defaultFonts = listOf(defaultMinchoFont, defaultGothicFont)

        fun normalizeDefaultFont(name: String): String = when (name) {
            "Hiragino Mincho ProN" -> defaultMinchoFont
            "Hiragino Kaku Gothic ProN" -> defaultGothicFont
            else -> name
        }
    }
}

private fun File.toFontInfo(): ReaderFontInfo =
    ReaderFontInfo(
        name = nameWithoutExtension,
        fileName = name,
        file = this,
    )

private fun ContentResolver.displayName(uri: Uri): String {
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null).use { cursor: Cursor? ->
        if (cursor != null && cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) {
                val name = cursor.getString(index)
                if (!name.isNullOrBlank()) return name
            }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "ImportedFont.ttf"
}

private fun String.isSupportedFontFileName(): Boolean =
    substringAfterLast('.', missingDelimiterValue = "")
        .lowercase() in setOf("ttf", "otf", "woff", "woff2")

private fun String.pathSegmentEncoded(): String =
    URLEncoder.encode(this, Charsets.UTF_8.name())
        .replace("+", "%20")

private fun String.cssString(): String =
    buildString(length + 2) {
        append('"')
        this@cssString.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\a ")
                '\r' -> Unit
                else -> append(ch)
            }
        }
        append('"')
    }
