package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubTocItem

internal object ReaderChapterLabels {
    fun labels(book: EpubBook): Map<Int, String> {
        val pathToSpine = book.chapters.mapIndexed { fallbackIndex, chapter ->
            chapter.href to (chapter.spineIndex ?: fallbackIndex)
        }
            .toMap()
        val labels = linkedMapOf<Int, String>()

        fun walk(items: List<EpubTocItem>, topLabel: String?) {
            items.forEach { item ->
                val label = topLabel ?: item.label
                val path = item.href?.substringBefore("#")
                val index = path?.let(pathToSpine::get)
                if (index != null && labels[index] == null) {
                    labels[index] = label
                }
                walk(item.children, label)
            }
        }

        walk(book.toc, null)
        return labels
    }

    fun sectionLabelForIndex(book: EpubBook, chapterIndex: Int): String {
        val labels = labels(book)
        var index = chapterIndex
        while (index > 0 && labels[index] == null) {
            index -= 1
        }
        return labels[index].orEmpty()
    }
}
