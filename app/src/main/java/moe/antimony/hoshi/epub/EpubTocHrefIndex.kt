package moe.antimony.hoshi.epub

internal class EpubTocHrefIndex(chapters: List<EpubChapter>) {
    private val firstChapterByPath = HashMap<String, Int>()
    private val firstChapterBySuffix = HashMap<String, Int>()

    init {
        chapters.forEachIndexed { index, chapter ->
            val path = chapter.href.readerHrefBase()
            firstChapterByPath.putIfAbsent(path, index)
            firstChapterBySuffix.putIfAbsent(path, index)
            var slash = path.indexOf('/')
            while (slash >= 0) {
                firstChapterBySuffix.putIfAbsent(path.substring(slash + 1), index)
                slash = path.indexOf('/', slash + 1)
            }
        }
    }

    fun chapterIndex(href: String): Int? {
        val path = href.readerHrefBase()
        if (path.isBlank()) return null
        // A chapter can end with the requested path, or the requested path can
        // end with a chapter path. Keep the first spine match in either direction.
        var first = firstChapterBySuffix[path]
        var slash = path.indexOf('/')
        while (slash >= 0) {
            firstChapterByPath[path.substring(slash + 1)]?.let { candidate ->
                first = minOf(first ?: candidate, candidate)
            }
            slash = path.indexOf('/', slash + 1)
        }
        return first
    }
}
