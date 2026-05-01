package moe.antimony.hoshi.epub

import uniffi.hoshiepub.EpubBook as NativeEpubBook
import uniffi.hoshiepub.TocNode as NativeTocNode
import uniffi.hoshiepub.parseExtractedEpub
import java.io.File

data class EpubBook(
    val title: String,
    val chapters: List<EpubChapter>,
    val toc: List<EpubTocItem> = emptyList(),
    val coverHref: String? = null,
    private val resources: Map<String, EpubResource> = emptyMap(),
    private val rootDirectory: File? = null,
) {
    val bookInfo: BookInfo = chapters.toBookInfo()

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

    fun characterCountAt(chapterIndex: Int, progress: Double): Int {
        val chapter = chapters.getOrNull(chapterIndex) ?: return 0
        val info = bookInfo.chapterInfo[chapter.href] ?: return 0
        val chapterOffset = (info.chapterCount.toDouble() * progress.coerceIn(0.0, 1.0)).toInt()
        return (info.currentTotal + chapterOffset).coerceIn(0, bookInfo.characterCount)
    }
}

data class EpubChapter(
    val id: String,
    val href: String,
    val mediaType: String,
    val html: String,
)

data class EpubTocItem(
    val label: String,
    val href: String?,
    val children: List<EpubTocItem> = emptyList(),
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
            toc = toc().children.map { it.toReaderTocItem(root, contentDirectory) },
            chapters = chapters,
            resources = resources,
            rootDirectory = root,
        )
    }
}

private fun NativeTocNode.toReaderTocItem(root: File, contentDirectory: File): EpubTocItem =
    EpubTocItem(
        label = label,
        href = href?.normalizeTocHref(root, contentDirectory),
        children = children.map { it.toReaderTocItem(root, contentDirectory) },
    )

private fun String.normalizeTocHref(root: File, contentDirectory: File): String {
    val raw = trim().replace('\\', '/').removePrefix("/")
    if (raw.isBlank()) return raw
    val fragment = raw.substringAfter('#', "")
    val base = raw.substringBefore('#').substringBefore('?')
    val href = contentDirectory.resolve(base).relativeHref(root)
        ?: base.normalizeResourceHref()
    return if (fragment.isBlank()) href else "$href#$fragment"
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

private fun List<EpubChapter>.toBookInfo(): BookInfo {
    var total = 0
    val chapterInfo = linkedMapOf<String, BookInfo.ChapterInfo>()
    forEachIndexed { index, chapter ->
        val count = chapter.html.filteredReaderText().codePointCount()
        chapterInfo[chapter.href] = BookInfo.ChapterInfo(
            spineIndex = index,
            currentTotal = total,
            chapterCount = count,
        )
        total += count
    }
    return BookInfo(characterCount = total, chapterInfo = chapterInfo)
}

private fun String.codePointCount(): Int =
    codePointCount(0, length)
