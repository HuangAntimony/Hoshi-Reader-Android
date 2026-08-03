package moe.antimony.hoshi.epub

import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private val readerImageTagRegex = Regex("""<(?:img|image)\b[^>]*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val readerAttributeRegex = Regex(
    """\b([A-Za-z_:][A-Za-z0-9_:.-]*)\s*=\s*(["'])(.*?)\2""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val readerOpeningTagRegex = Regex("""<[A-Za-z][^>]*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val readerBodyOpeningTagRegex = Regex("""<body\b[^>]*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val readerGalleryExtensions = setOf("jpg", "jpeg", "png")

internal fun buildBookInfo(
    chapters: List<EpubChapter>,
    toc: List<EpubTocItem> = emptyList(),
    rootDirectory: File? = null,
): BookInfo {
    val tocFragments = toc.readerTocFragmentsByChapter()
    val images = linkedSetOf<String>()
    var total = 0
    val chapterInfo = linkedMapOf<String, BookInfo.ChapterInfo>()
    chapters.forEachIndexed { index, chapter ->
        val fragments = tocFragments[chapter.href].orEmpty()
        val filteredText = chapter.html.filteredReaderText()
        val count = filteredText.codePointCount(0, filteredText.length)
        chapterInfo[chapter.href] = BookInfo.ChapterInfo(
            spineIndex = index,
            currentTotal = total,
            chapterCount = count,
            fragmentOffsets = fragments.takeIf { it.isNotEmpty() }?.associateWith { fragment ->
                chapter.html.readerFragmentOffset(fragment)
            },
        )
        total += count
        if (rootDirectory != null) {
            images += chapter.html.readerGalleryImagePaths(
                chapterHref = chapter.href,
                rootDirectory = rootDirectory,
            )
        }
    }
    return BookInfo(
        characterCount = total,
        chapterInfo = chapterInfo,
        images = images.toList(),
    )
}

internal fun BookInfo.matchesReaderFacts(
    chapters: List<EpubChapter>,
    toc: List<EpubTocItem>,
): Boolean {
    if (!matchesChapterShells(chapters) || images == null) return false
    val tocFragments = toc.readerTocFragmentsByChapter()
    return tocFragments.all { (href, fragments) ->
        val info = chapterInfo[href] ?: return@all true
        val offsets = info.fragmentOffsets ?: return@all fragments.isEmpty()
        fragments.all(offsets::containsKey)
    }
}

private fun List<EpubTocItem>.readerTocFragmentsByChapter(): Map<String, LinkedHashSet<String>> {
    val fragmentsByChapter = linkedMapOf<String, LinkedHashSet<String>>()
    fun visit(item: EpubTocItem) {
        item.href?.let { href ->
            val fragment = href.substringAfter('#', "")
            if (fragment.isNotEmpty()) {
                fragmentsByChapter.getOrPut(href.substringBefore('#').substringBefore('?')) { linkedSetOf() } += fragment
            }
        }
        item.children.forEach(::visit)
    }
    forEach(::visit)
    return fragmentsByChapter
}

private fun String.readerFragmentOffset(fragment: String): Int {
    val decodedFragment = fragment.decodeReaderUrlComponent()
    val bodyStart = readerBodyOpeningTagRegex.find(this)?.range?.last?.plus(1) ?: 0
    val body = substring(bodyStart)
    val target = readerOpeningTagRegex.findAll(body).firstOrNull { match ->
        val id = match.value.readerAttributes()["id"]
        id == decodedFragment || id == fragment
    } ?: return 0
    val prefix = body.substring(0, target.range.first)
    val filtered = prefix.filteredReaderText()
    return filtered.codePointCount(0, filtered.length)
}

private fun String.readerGalleryImagePaths(
    chapterHref: String,
    rootDirectory: File,
): List<String> {
    val canonicalRoot = rootDirectory.canonicalFile
    val chapterParent = canonicalRoot.resolve(chapterHref).canonicalFile.parentFile ?: canonicalRoot
    return readerImageTagRegex.findAll(this).mapNotNull { match ->
        val attributes = match.value.readerAttributes()
        if (attributes["class"].orEmpty().split(Regex("""\s+""")).any { it.equals("gaiji", ignoreCase = true) }) {
            return@mapNotNull null
        }
        val source = attributes["src"] ?: attributes["xlink:href"] ?: return@mapNotNull null
        source.readerResolvedImagePath(chapterParent, canonicalRoot)
    }.toList()
}

private fun String.readerAttributes(): Map<String, String> =
    readerAttributeRegex.findAll(this).associate { match ->
        match.groupValues[1].lowercase() to match.groupValues[3].decodeReaderHtmlEntities()
    }

private fun String.readerResolvedImagePath(chapterParent: File, rootDirectory: File): String? {
    val rawPath = substringBefore('#').substringBefore('?')
    if (rawPath.isBlank() || rawPath.startsWith("//")) return null
    val uri = runCatching { URI(rawPath) }.getOrNull()
    if (uri?.isAbsolute == true) return null
    val decodedPath = rawPath.decodeReaderUrlComponent()
    val image = runCatching { chapterParent.resolve(decodedPath).canonicalFile }.getOrNull() ?: return null
    if (image.path != rootDirectory.path && !image.path.startsWith(rootDirectory.path + File.separator)) return null
    if (!image.isFile || image.extension.lowercase() !in readerGalleryExtensions) return null
    return image.relativeTo(rootDirectory).invariantSeparatorsPath
}

private fun String.decodeReaderUrlComponent(): String =
    runCatching {
        URLDecoder.decode(replace("+", "%2B"), StandardCharsets.UTF_8.name())
    }.getOrDefault(this)

private fun String.decodeReaderHtmlEntities(): String =
    replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
