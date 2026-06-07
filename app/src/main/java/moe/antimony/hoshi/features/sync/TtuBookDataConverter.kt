package moe.antimony.hoshi.features.sync

import java.io.File
import java.nio.file.Paths
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.di.FilesDir
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubBookParser
import moe.antimony.hoshi.epub.EpubChapter
import moe.antimony.hoshi.epub.EpubTocItem

@Singleton
class TtuBookDataConverter @Inject constructor(
    private val bookRepository: BookRepository,
    private val parser: EpubBookParser,
    @param:FilesDir private val filesDir: File,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(
        bookRepository: BookRepository,
        parser: EpubBookParser,
        filesDir: File,
    ) : this(bookRepository, parser, filesDir, Dispatchers.IO)

    suspend fun exportBookData(entry: BookEntry): File? =
        exportBookData(entry, filesDir.resolve("ExportTemp/ttu-bookdata"))

    suspend fun exportBookData(entry: BookEntry, outputDirectory: File): File? = withContext(ioDispatcher) {
        val metadata = bookRepository.loadMetadata(entry.root) ?: entry.metadata
        val bookInfo = bookRepository.loadBookInfo(entry.root) ?: return@withContext null
        if (bookRepository.epubFile(entry.root, metadata) == null) return@withContext null
        val book = parser.parse(entry.root, fallbackTitle = metadata.title)
        outputDirectory.mkdirs()
        val nowMillis = System.currentTimeMillis()
        val lastAccessMillis = TtuSyncRules.appleReferenceSecondsToUnixMillis(metadata.lastAccess)
        val archive = outputDirectory.resolve("bookdata_1_6_${bookInfo.characterCount}_${nowMillis}_${lastAccessMillis}.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.writeTextEntry(
                "staticdata.json",
                json.encodeToString(book.toStaticData(metadata.title ?: book.title)),
            )
            book.resources
                .filterValues { it.mediaType.startsWith("image/") }
                .toSortedMap()
                .forEach { (href, resource) ->
                    val bytes = resource.readBytes() ?: return@forEach
                    zip.writeBytesEntry("blobs/$href", bytes)
                }
            bookRepository.coverFile(entry)?.takeIf(File::isFile)?.let { cover ->
                zip.writeBytesEntry("cover.${cover.extension.ifBlank { "jpg" }}", cover.readBytes())
            }
        }
        archive
    }

    suspend fun importBookData(bookData: File): BookEntry = withContext(ioDispatcher) {
        val tempRoot = filesDir.resolve("ImportTemp/ttu-bookdata-${UUID.randomUUID()}").canonicalFile
        val stageRoot = filesDir.resolve("ImportTemp/ttu-stage-${UUID.randomUUID()}").canonicalFile
        tempRoot.deleteRecursively()
        stageRoot.deleteRecursively()
        tempRoot.mkdirs()
        try {
            unzipInto(bookData, tempRoot)
            val staticData = json.decodeFromString(TtuStaticData.serializer(), tempRoot.resolve("staticdata.json").readText())
            val folderName = staticData.title.sanitizeImportedBookFolderName()
            require(folderName.isNotBlank()) { "TTU book title is empty." }
            val root = bookRepository.createBookDirectoryForImportedTitle(staticData.title)
            val existingMetadata = bookRepository.loadMetadata(root)
            if (root.listFiles()?.isNotEmpty() == true) {
                return@withContext BookEntry(
                    root = root,
                    metadata = existingMetadata ?: BookMetadata(
                        id = UUID.randomUUID().toString(),
                        title = staticData.title,
                        cover = null,
                        folder = root.name,
                        lastAccess = bookRepository.currentAppleReferenceDateSeconds(),
                        epub = "${root.name}.epub",
                    ),
                )
            }
            root.deleteRecursively()
            val stagedBookRoot = stageRoot.resolve(folderName).canonicalFile
            stagedBookRoot.mkdirs()
            val epubName = "$folderName.epub"
            val coverFile = tempRoot.listFiles()
                .orEmpty()
                .firstOrNull { it.isFile && it.name.startsWith("cover.") }
            coverFile?.copyTo(stagedBookRoot.resolve(coverFile.name.sanitizeImportedBookFolderName()), overwrite = true)
            val xhtmlFiles = splitElementHtml(
                html = normalizeTagsToXhtml(normalizeImages(staticData.elementHtml)),
                sections = staticData.sections,
            )
            require(xhtmlFiles.isNotEmpty()) { "TTU bookdata contains no readable sections." }
            val imagePaths = collectImageFiles(tempRoot.resolve("blobs"))
            ZipOutputStream(stagedBookRoot.resolve(epubName).outputStream()).use { zip ->
                zip.writeStoredTextEntry("mimetype", "application/epub+zip")
                zip.writeTextEntry("item/stylesheet.css", staticData.styleSheet)
                zip.writeTextEntry("META-INF/container.xml", containerXml)
                imagePaths.forEach { image ->
                    zip.writeBytesEntry("item/${image.relativePath}", image.file.readBytes())
                }
                xhtmlFiles.forEach { file ->
                    zip.writeTextEntry("item/xhtml/${file.fileName}", generateXhtml(file, staticData.title))
                }
                zip.writeTextEntry("item/navigation-documents.xhtml", generateNavigationDocuments(xhtmlFiles))
                zip.writeTextEntry("item/standard.opf", generateOpf(imagePaths.map { it.relativePath }, xhtmlFiles, staticData.title))
            }
            val metadata = BookMetadata(
                id = UUID.randomUUID().toString(),
                title = staticData.title,
                cover = coverFile?.let { "Books/$folderName/${stagedBookRoot.resolve(it.name.sanitizeImportedBookFolderName()).name}" },
                folder = folderName,
                lastAccess = bookRepository.currentAppleReferenceDateSeconds(),
                epub = epubName,
            )
            bookRepository.saveMetadata(stagedBookRoot, metadata)
            val parsed = parser.parse(stagedBookRoot, fallbackTitle = staticData.title)
            bookRepository.saveBookInfo(stagedBookRoot, parsed.bookInfo)

            if (!stagedBookRoot.renameTo(root)) {
                stagedBookRoot.copyRecursively(root, overwrite = true)
                stagedBookRoot.deleteRecursively()
            }
            BookEntry(root, metadata)
        } finally {
            tempRoot.deleteRecursively()
            stageRoot.deleteRecursively()
        }
    }

    private fun EpubBook.toStaticData(title: String): TtuStaticData {
        val elementParts = mutableListOf<String>()
        val sections = mutableListOf<TtuSection>()
        var currentParent: String? = null
        chapters.forEach { chapter ->
            val chapterInfo = bookInfo.chapterInfo[chapter.href]
            val characters = chapterInfo?.chapterCount ?: 0
            val referenceId = chapter.id.ifBlank { chapter.href.substringAfterLast('/').substringBeforeLast('.') }
            val reference = "ttu-$referenceId"
            val ttuNoText = if (characters == 0) " ttu-no-text" else ""
            val htmlClass = classList("ttu-book-html-wrapper", chapter.html.extractTagClass("html"), ttuNoText)
            val bodyClass = classList("ttu-book-body-wrapper", chapter.html.extractTagClass("body"), ttuNoText)
            val bodyHtml = normalizeTagsToHtml(rewriteImages(chapter.bodyHtml(), chapter.href))
            elementParts += "<div id=\"$reference\"><div class=\"$htmlClass\"><div class=\"$bodyClass\">$bodyHtml</div></div></div>"

            val label = tocLabel(chapter)
            if (label != null) {
                currentParent = reference
                sections += TtuSection(
                    reference = reference,
                    charactersWeight = characters.coerceAtLeast(1),
                    label = label,
                    startCharacter = chapterInfo?.currentTotal ?: 0,
                    characters = 0,
                    parentChapter = null,
                )
            } else if (currentParent != null) {
                sections += TtuSection(
                    reference = reference,
                    charactersWeight = characters.coerceAtLeast(1),
                    label = null,
                    startCharacter = null,
                    characters = null,
                    parentChapter = currentParent,
                )
            } else {
                currentParent = reference
                sections += TtuSection(
                    reference = reference,
                    charactersWeight = characters.coerceAtLeast(1),
                    label = "Preface",
                    startCharacter = chapterInfo?.currentTotal ?: 0,
                    characters = 0,
                    parentChapter = null,
                )
            }
        }
        sections.indices.filter { sections[it].label != null }.forEach { index ->
            val next = sections.drop(index + 1).firstOrNull { it.label != null }
            val nextStart = next?.startCharacter ?: bookInfo.characterCount
            val start = sections[index].startCharacter ?: 0
            sections[index] = sections[index].copy(characters = nextStart - start)
        }
        val stylesheet = resources.values
            .filter { it.mediaType.equals("text/css", ignoreCase = true) }
            .mapNotNull { it.readBytes()?.decodeToString() }
            .joinToString(separator = "")
        return TtuStaticData(
            title = title,
            styleSheet = stylesheet,
            elementHtml = elementParts.joinToString(separator = ""),
            sections = sections,
        )
    }

    private fun EpubBook.tocLabel(chapter: EpubChapter): String? {
        return ttuTocLabel(chapter.href, toc)
    }
}

internal fun ttuTocLabel(chapterHref: String, toc: List<EpubTocItem>): String? =
    toc.firstNotNullOfOrNull { item ->
        val href = item.href?.substringBefore('#')
        if (!href.isNullOrBlank() && (href == chapterHref || href.endsWith(chapterHref) || chapterHref.endsWith(href))) {
            item.label
        } else {
            ttuTocLabel(chapterHref, item.children)
        }
    }

@Serializable
private data class TtuStaticData(
    val title: String,
    val styleSheet: String,
    val elementHtml: String,
    val sections: List<TtuSection>,
)

@Serializable
private data class TtuSection(
    val reference: String,
    val charactersWeight: Int,
    val label: String? = null,
    val startCharacter: Int? = null,
    val characters: Int? = null,
    val parentChapter: String? = null,
)

private data class TtuXhtmlFile(
    val fileName: String,
    val label: String?,
    val html: String,
)

private data class TtuImageFile(
    val relativePath: String,
    val file: File,
)

private fun unzipInto(archiveFile: File, destinationRoot: File) {
    val root = destinationRoot.canonicalFile
    ZipFile(archiveFile).use { zip ->
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val output = root.resolve(entry.name).canonicalFile
            require(output.path == root.path || output.path.startsWith(root.path + File.separator)) {
                "Unsafe TTU bookdata entry: ${entry.name}"
            }
            if (entry.isDirectory) {
                output.mkdirs()
            } else {
                output.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    output.outputStream().use { outputStream -> input.copyTo(outputStream) }
                }
            }
        }
    }
}

private fun collectImageFiles(blobs: File): List<TtuImageFile> {
    if (!blobs.isDirectory) return emptyList()
    return blobs.walkTopDown()
        .filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "gif", "svg") }
        .map { file -> TtuImageFile(file.relativeTo(blobs).invariantSeparatorsPath, file) }
        .sortedBy { it.relativePath }
        .toList()
}

private fun splitElementHtml(html: String, sections: List<TtuSection>): List<TtuXhtmlFile> {
    val split = sections.mapNotNull { section ->
        val index = html.indexOf("<div id=\"${section.reference}\"")
        if (index < 0) null else section to index
    }.sortedBy { it.second }
    return split.mapIndexed { index, item ->
        val end = split.getOrNull(index + 1)?.second ?: html.length
        val fileStem = item.first.reference.toSafeTtuFileStem()
        TtuXhtmlFile(
            fileName = "$fileStem.xhtml",
            label = item.first.label,
            html = html.substring(item.second, end),
        )
    }
}

private fun String.toSafeTtuFileStem(): String {
    require(startsWith("ttu-")) { "Unsafe TTU section reference: $this" }
    val stem = removePrefix("ttu-")
    require(stem.isNotBlank() && stem != "." && stem != "..") { "Unsafe TTU section reference: $this" }
    require(!stem.contains('/') && !stem.contains('\\')) {
        "Unsafe TTU section reference: $this"
    }
    return stem
}

private fun generateXhtml(file: TtuXhtmlFile, title: String): String {
    var content = file.html.removeOuterTtuDiv()
    val hasWrappers = content.contains("ttu-book-html-wrapper") && content.contains("ttu-book-body-wrapper")
    val htmlClass = if (hasWrappers) {
        content.wrapperClass("ttu-book-html-wrapper")
    } else {
        ""
    }
    val bodyClass = if (hasWrappers) {
        content.wrapperClass("ttu-book-body-wrapper")
    } else {
        ""
    }
    if (hasWrappers) {
        content = content
            .replaceFirst(Regex("""<div\s+class="ttu-book-html-wrapper[^"]*"[^>]*>"""), "")
            .replaceFirst(Regex("""<div\s+class="ttu-book-body-wrapper[^"]*"[^>]*>"""), "")
            .removeSuffix("</div></div>")
    }
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE html>
        <html
         xmlns="http://www.w3.org/1999/xhtml"
         xmlns:epub="http://www.idpf.org/2007/ops"
         xml:lang="ja"
         class="${escapeXml(htmlClass)}"
        >
        <head>
        <meta charset="UTF-8"/>
        <title>${escapeXml(title)}</title>
        <link rel="stylesheet" type="text/css" href="../stylesheet.css"/>
        </head>
        <body class="${escapeXml(bodyClass)}">
        $content
        </body>
        </html>
    """.trimIndent().trimStart()
}

private fun generateNavigationDocuments(xhtmlFiles: List<TtuXhtmlFile>): String {
    val navItems = xhtmlFiles.filter { it.label != null }.joinToString(separator = "\n") {
        """<li><a href="xhtml/${it.fileName}">${escapeXml(it.label.orEmpty())}</a></li>"""
    }
    val tocItem = xhtmlFiles.firstOrNull { it.fileName.contains("toc") }?.let {
        """<li><a epub:type="toc" href="xhtml/${it.fileName}">${escapeXml(it.label ?: "toc")}</a></li>"""
    }.orEmpty()
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE html>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="ja">
        <head><meta charset="UTF-8"/><title>Navigation</title></head>
        <body>
        <nav epub:type="toc" id="toc"><h1>Navigation</h1><ol>$navItems</ol></nav>
        <nav epub:type="landmarks" id="guide"><h1>Guide</h1><ol>$tocItem</ol></nav>
        </body>
        </html>
    """.trimIndent()
}

private fun generateOpf(imagePaths: List<String>, xhtmlFiles: List<TtuXhtmlFile>, title: String): String {
    val coverImageIndex = imagePaths.indexOfFirst { path -> File(path).nameWithoutExtension == "cover" }
    val imageManifest = imagePaths.mapIndexed { index, path ->
        val id = if (index == coverImageIndex) "cover" else "image-$index"
        val properties = if (index == coverImageIndex) """ properties="cover-image"""" else ""
        """<item media-type="${imageMediaType(path)}" id="$id" href="${escapeXml(path)}"$properties/>"""
    }.joinToString(separator = "\n")
    val xhtmlManifest = xhtmlFiles.mapIndexed { index, file ->
        val properties = if (file.html.contains("<svg")) """ properties="svg"""" else ""
        """<item media-type="application/xhtml+xml" id="xhtml-$index" href="xhtml/${escapeXml(file.fileName)}"$properties/>"""
    }.joinToString(separator = "\n")
    val spine = xhtmlFiles.mapIndexed { index, _ ->
        """<itemref linear="yes" idref="xhtml-$index"/>"""
    }.joinToString(separator = "\n")
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0" xml:lang="ja" unique-identifier="book-uuid">
        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
        <dc:title id="title">${escapeXml(title)}</dc:title>
        <dc:language>ja</dc:language>
        <dc:identifier id="book-uuid">${UUID.randomUUID()}</dc:identifier>
        </metadata>
        <manifest>
        <item media-type="application/xhtml+xml" id="nav" href="navigation-documents.xhtml" properties="nav"/>
        <item media-type="text/css" id="stylesheet" href="stylesheet.css"/>
        $imageManifest
        $xhtmlManifest
        </manifest>
        <spine page-progression-direction="rtl">
        $spine
        </spine>
        </package>
    """.trimIndent()
}

private fun String.removeOuterTtuDiv(): String {
    val openEnd = indexOf('>')
    if (openEnd < 0) return this
    val inner = substring(openEnd + 1)
    return inner.removeSuffix("</div>")
}

private fun String.wrapperClass(wrapperName: String): String {
    val match = Regex("""$wrapperName\s*([^"]*)"""").find(this) ?: return ""
    return match.groupValues[1]
        .replace("ttu-no-text", "")
        .trim()
}

private fun String.extractTagClass(tag: String): String =
    Regex("""<$tag\b[^>]*\bclass="([^"]*)"""", RegexOption.IGNORE_CASE)
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
        .orEmpty()

private fun EpubChapter.bodyHtml(): String =
    Regex("""<body\b[^>]*>([\s\S]*)</body>""", RegexOption.IGNORE_CASE)
        .find(html)
        ?.groupValues
        ?.getOrNull(1)
        ?: html

private fun normalizeTagsToXhtml(html: String): String =
    html
        .replace(Regex("""<br\b([^>/]*)>""", RegexOption.IGNORE_CASE), "<br$1/>")
        .replace(Regex("""<hr\b([^>/]*)>""", RegexOption.IGNORE_CASE), "<hr$1/>")
        .replace(Regex("""<img\b([^>]*)>""", RegexOption.IGNORE_CASE)) { match ->
            "<img${match.groupValues[1].trimEnd('/', ' ')}/>"
        }
        .replace("&nbsp;", "&#160;")

private fun normalizeTagsToHtml(html: String): String =
    html
        .replace(Regex("""<br\b([^>]*)/>""", RegexOption.IGNORE_CASE), "<br$1>")
        .replace(Regex("""<hr\b([^>]*)/>""", RegexOption.IGNORE_CASE), "<hr$1>")
        .replace(Regex("""<img\b([^>]*)/>""", RegexOption.IGNORE_CASE), "<img$1>")

private fun normalizeImages(html: String): String =
    html
        .replace(Regex("""data:image/[^;"]+;ttu:([^;"]+);base64,[^"]*""")) { "../${it.groupValues[1]}" }
        .replace(Regex("""ttu:([^"']+)""")) { "../${it.groupValues[1]}" }

private fun rewriteImages(html: String, chapterHref: String): String {
    fun rewrite(src: String): String {
        val base = File(chapterHref).parent?.takeIf { it.isNotBlank() }
        val normalized = (base?.let { Paths.get(it).resolve(src) } ?: Paths.get(src))
            .normalize()
            .toString()
            .replace('\\', '/')
            .removePrefix("../")
        return "data:image/gif;ttu:$normalized;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw=="
    }
    return html
        .replace(Regex("""(<img\b[^>]*\bsrc=")([^"]+)(")""", RegexOption.IGNORE_CASE)) {
            "${it.groupValues[1]}${rewrite(it.groupValues[2])}${it.groupValues[3]}"
        }
        .replace(Regex("""(<image\b[^>]*\sxlink:href=")([^"]+)(")""", RegexOption.IGNORE_CASE)) {
            "${it.groupValues[1]}${rewrite(it.groupValues[2])}${it.groupValues[3]}"
        }
        .replace(Regex("""(<image\b[^>]*\shref=")([^"]+)(")""", RegexOption.IGNORE_CASE)) {
            "${it.groupValues[1]}${rewrite(it.groupValues[2])}${it.groupValues[3]}"
        }
}

private fun classList(vararg values: String): String =
    values.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(separator = " ")

private fun String.sanitizeImportedBookFolderName(): String =
    split(Regex("[\\\\/:*?\"<>|\\n\\r\\u0000-\\u001F]"))
        .joinToString("_")
        .trim()

private fun imageMediaType(path: String): String = when (File(path).extension.lowercase()) {
    "png" -> "image/png"
    "gif" -> "image/gif"
    "svg" -> "image/svg+xml"
    else -> "image/jpeg"
}

private fun escapeXml(text: String): String =
    text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

private fun ZipOutputStream.writeTextEntry(path: String, value: String) {
    writeBytesEntry(path, value.toByteArray())
}

private fun ZipOutputStream.writeBytesEntry(path: String, bytes: ByteArray) {
    putNextEntry(ZipEntry(path))
    write(bytes)
    closeEntry()
}

private fun ZipOutputStream.writeStoredTextEntry(path: String, value: String) {
    val bytes = value.toByteArray()
    val crc = java.util.zip.CRC32().apply { update(bytes) }
    val entry = ZipEntry(path).apply {
        method = ZipEntry.STORED
        size = bytes.size.toLong()
        compressedSize = bytes.size.toLong()
        this.crc = crc.value
    }
    putNextEntry(entry)
    write(bytes)
    closeEntry()
}

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private val containerXml = """
    <?xml version="1.0"?>
    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
    <rootfiles>
    <rootfile full-path="item/standard.opf" media-type="application/oebps-package+xml"/>
    </rootfiles>
    </container>
""".trimIndent()
