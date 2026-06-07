package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.epub.EpubBook
import java.net.URI

internal data class ReaderInternalLinkTarget(
    val position: ReaderChapterPosition,
    val fragment: String?,
)

internal fun EpubBook.resolveInternalReaderLink(url: String): ReaderInternalLinkTarget? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if ((scheme != "http" && scheme != "https") || uri.host != "appassets.androidplatform.net") return null

    val path = uri.path.orEmpty()
    if (!path.startsWith("/epub/")) return null

    val href = path.removePrefix("/epub/").readerHrefBase()
    if (href.isBlank()) return null
    val spineIndex = chapters.indexOfFirst { chapter ->
        val chapterPath = chapter.href.readerHrefBase()
        href == chapterPath ||
            href.endsWith("/$chapterPath") ||
            chapterPath.endsWith("/$href")
    }.takeIf { it >= 0 } ?: return null

    return ReaderInternalLinkTarget(
        position = ReaderChapterPosition(index = spineIndex, progress = 0.0),
        fragment = uri.fragment?.ifBlank { null },
    )
}

private fun String.readerHrefBase(): String =
    trim()
        .replace('\\', '/')
        .removePrefix("/")
        .substringBefore('#')
        .substringBefore('?')
