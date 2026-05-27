package moe.antimony.hoshi.epub

import uniffi.hoshiepub.EpubBook as NativeEpubBook
import uniffi.hoshiepub.TocNode as NativeTocNode
import uniffi.hoshiepub.parseExtractedEpub
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

data class EpubBook(
    val title: String,
    val chapters: List<EpubChapter>,
    val toc: List<EpubTocItem> = emptyList(),
    val coverHref: String? = null,
    private val resources: Map<String, EpubResource> = emptyMap(),
    private val rootDirectory: File? = null,
    val bookInfo: BookInfo = chapters.toBookInfo(),
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
    val spineIndex: Int? = null,
    val linear: Boolean = true,
    val properties: String? = null,
    val isGuideToc: Boolean = false,
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
    fun parse(root: File, fallbackTitle: String? = null, cachedBookInfo: BookInfo? = null): EpubBook {
        require(root.isDirectory) { "Extracted EPUB directory does not exist: ${root.absolutePath}" }

        val nativeBook = parseExtractedEpub(root.absolutePath)
        return try {
            nativeBook.toReaderBook(root, fallbackTitle, cachedBookInfo)
        } finally {
            nativeBook.destroy()
        }
    }

    private fun NativeEpubBook.toReaderBook(root: File, fallbackTitle: String?, cachedBookInfo: BookInfo?): EpubBook {
        val manifest = manifest().associateBy { it.id }
        val contentDirectory = File(contentDir())
        val contentDirectoryPrefix = contentDirectory.relativeDirectoryHref(root)
        val guideTocHrefs = root.readGuideTocHrefs()
        val chapterShells = spine().mapIndexedNotNull { index, spineItem ->
            val manifestItem = manifest[spineItem.idref] ?: return@mapIndexedNotNull null
            if (!manifestItem.mediaType.isHtmlMediaType()) return@mapIndexedNotNull null
            val href = contentDirectoryPrefix.resolveManifestHref(manifestItem.href)
            EpubChapter(
                id = manifestItem.id,
                href = href,
                mediaType = manifestItem.mediaType,
                html = "",
                spineIndex = index,
                linear = spineItem.linear,
                properties = manifestItem.properties,
                isGuideToc = guideTocHrefs.contains(manifestItem.href.normalizeResourceHref()) ||
                    guideTocHrefs.contains(href.normalizeResourceHref()),
            )
        }

        require(chapterShells.isNotEmpty()) { "EPUB spine contains no readable chapters" }
        val reusableBookInfo = cachedBookInfo?.takeIf { it.matchesChapterShells(chapterShells) }
        val chapters = if (reusableBookInfo != null) {
            chapterShells
        } else {
            chapterShells.map { chapter ->
                val spineIndex = chapter.spineIndex ?: return@map chapter
                chapter.copy(html = readSpineItemText(spineIndex.toUInt()))
            }
        }

        val resources = manifest.values.mapNotNull { manifestItem ->
            val href = contentDirectoryPrefix.resolveManifestHref(manifestItem.href)
            val file = root.resolve(href)
            href to EpubResource.file(manifestItem.mediaType, file)
        }.toMap()

        return EpubBook(
            title = title()?.ifBlank { null } ?: fallbackTitle?.takeIf { it.isNotBlank() } ?: root.nameWithoutExtension,
            coverHref = coverHref()
                ?.let { contentDirectoryPrefix.resolveManifestHref(it) }
                ?: resources.entries.firstOrNull { (_, resource) -> resource.mediaType.startsWith("image/") }?.key,
            toc = toc().children.map { it.toReaderTocItem(root, contentDirectory) },
            chapters = chapters,
            resources = resources,
            rootDirectory = root,
            bookInfo = reusableBookInfo ?: chapters.toBookInfo(),
        )
    }
}

internal fun BookInfo.matchesChapterShells(chapters: List<EpubChapter>): Boolean {
    if (characterCount < 0) return false
    var total = 0
    for ((chapterIndex, chapter) in chapters.withIndex()) {
        val info = chapterInfo[chapter.href] ?: return false
        if (info.spineIndex != chapterIndex || info.chapterCount < 0 || info.currentTotal != total) {
            return false
        }
        total += info.chapterCount
    }
    return total == characterCount
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

private fun File.relativeDirectoryHref(root: File): String =
    relativeHref(root)
        ?.takeIf { it.isNotBlank() }
        ?.trimEnd('/')
        .orEmpty()

private fun String.resolveManifestHref(href: String): String {
    val normalizedHref = href.normalizeResourceHref()
    val joined = if (isBlank()) {
        normalizedHref
    } else {
        "$this/$normalizedHref"
    }
    return File(joined).normalize().invariantSeparatorsPath.normalizeResourceHref()
}

private fun File.readGuideTocHrefs(): Set<String> =
    runCatching {
        val documentBuilder = DocumentBuilderFactory.newInstance()
            .apply {
                isNamespaceAware = true
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            .newDocumentBuilder()
        val container = documentBuilder.parse(resolve("META-INF/container.xml"))
        val rootfiles = container.getElementsByTagNameNS("*", "rootfile")
        val packagePath = (0 until rootfiles.length)
            .asSequence()
            .mapNotNull { index ->
                rootfiles.item(index).attributes?.getNamedItem("full-path")?.nodeValue
            }
            .firstOrNull()
            ?: return@runCatching emptySet()
        val packageFile = resolve(packagePath)
        val packageDir = packageFile.parentFile ?: this
        val packageDocument = documentBuilder.parse(packageFile)
        val references = packageDocument.getElementsByTagNameNS("*", "reference")
        buildSet {
            for (index in 0 until references.length) {
                val attributes = references.item(index).attributes ?: continue
                val type = attributes.getNamedItem("type")?.nodeValue.orEmpty()
                if (!type.equals("toc", ignoreCase = true)) continue
                val href = attributes.getNamedItem("href")?.nodeValue?.normalizeResourceHref() ?: continue
                add(href)
                packageDir.resolve(href).relativeHref(this@readGuideTocHrefs)?.let(::add)
            }
        }
    }.getOrDefault(emptySet())

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
