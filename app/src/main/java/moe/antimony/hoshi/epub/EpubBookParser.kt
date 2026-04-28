package moe.antimony.hoshi.epub

import uniffi.hoshiepub.EpubBook as NativeEpubBook
import uniffi.hoshiepub.parseExtractedEpub
import java.io.File

data class EpubBook(
    val title: String,
    val chapters: List<EpubChapter>,
    val coverHref: String? = null,
    private val resources: Map<String, EpubResource> = emptyMap(),
    private val rootDirectory: File? = null,
) {
    fun readResource(path: String): ByteArray? {
        val normalized = path.normalizeResourceHref()
        return resources[normalized]?.readBytes()
            ?: rootDirectory
                ?.resolve(normalized)
                ?.takeIf { it.isFile }
                ?.readBytes()
    }

    fun mediaType(path: String): String =
        resources[path.normalizeResourceHref()]?.mediaType ?: path.fallbackMimeType()
}

data class EpubChapter(
    val id: String,
    val href: String,
    val mediaType: String,
    val html: String,
)

data class EpubResource(
    val mediaType: String,
    private val bytes: ByteArray? = null,
    private val file: File? = null,
) {
    fun readBytes(): ByteArray? = bytes ?: file?.takeIf { it.isFile }?.readBytes()

    companion object {
        fun file(mediaType: String, file: File): EpubResource =
            EpubResource(mediaType = mediaType, file = file)
    }
}

class EpubBookParser {
    fun parse(root: File): EpubBook {
        require(root.isDirectory) { "Extracted EPUB directory does not exist: ${root.absolutePath}" }

        val nativeBook = parseExtractedEpub(root.absolutePath)
        return try {
            nativeBook.toReaderBook(root)
        } finally {
            nativeBook.destroy()
        }
    }

    private fun NativeEpubBook.toReaderBook(root: File): EpubBook {
        val manifest = manifest().associateBy { it.id }
        val contentDirectory = File(contentDir())
        val chapters = spine().mapIndexedNotNull { index, spineItem ->
            val manifestItem = manifest[spineItem.idref] ?: return@mapIndexedNotNull null
            if (!manifestItem.mediaType.isHtmlMediaType()) return@mapIndexedNotNull null
            val href = chapterAbsolutePath(index.toUInt())
                ?.let(::File)
                ?.relativeHref(root)
                ?: return@mapIndexedNotNull null
            EpubChapter(
                id = manifestItem.id,
                href = href,
                mediaType = manifestItem.mediaType,
                html = readSpineItemText(index.toUInt()),
            )
        }

        require(chapters.isNotEmpty()) { "EPUB spine contains no readable chapters" }

        val resources = manifest.values.mapNotNull { manifestItem ->
            val file = contentDirectory.resolve(manifestItem.href)
            val href = file.relativeHref(root) ?: return@mapNotNull null
            href to EpubResource.file(manifestItem.mediaType, file)
        }.toMap()

        return EpubBook(
            title = title()?.ifBlank { null } ?: root.nameWithoutExtension,
            coverHref = coverHref()
                ?.let { contentDirectory.resolve(it).relativeHref(root) }
                ?: resources.entries.firstOrNull { (_, resource) -> resource.mediaType.startsWith("image/") }?.key,
            chapters = chapters,
            resources = resources,
            rootDirectory = root,
        )
    }
}

private fun File.relativeHref(root: File): String? {
    val normalizedRoot = root.normalize()
    val normalizedFile = normalize()
    return runCatching {
        normalizedFile.relativeTo(normalizedRoot).invariantSeparatorsPath.normalizeResourceHref()
    }.getOrNull()
}

private fun String.normalizeResourceHref(): String =
    trim()
        .replace('\\', '/')
        .removePrefix("/")
        .substringBefore('#')
        .substringBefore('?')

private fun String.isHtmlMediaType(): Boolean =
    equals("application/xhtml+xml", ignoreCase = true) ||
        equals("text/html", ignoreCase = true) ||
        endsWith("+html", ignoreCase = true)

private fun String.fallbackMimeType(): String = when (substringAfterLast('.', "").lowercase()) {
    "css" -> "text/css"
    "js" -> "application/javascript"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "svg" -> "image/svg+xml"
    "xhtml", "html" -> "application/xhtml+xml"
    else -> "application/octet-stream"
}
