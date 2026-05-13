package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertEquals
import org.junit.Test

internal data class ReaderProgressNodeLayout(
    val characterCount: Int,
    val beforeViewport: Boolean,
)

internal fun readerProgressFromVisibleNodeLayouts(nodes: List<ReaderProgressNodeLayout>): Double {
    val total = nodes.sumOf { it.characterCount }
    if (total <= 0) return 0.0
    val explored = nodes
        .filter { it.characterCount > 0 && it.beforeViewport }
        .sumOf { it.characterCount }
    return explored.toDouble() / total.toDouble()
}

class ReaderProgressCalculationTest {
    @Test
    fun progressCountsEveryTextNodeBeforeViewportInsteadOfLastSortedPrefix() {
        val progress = readerProgressFromVisibleNodeLayouts(
            listOf(
                ReaderProgressNodeLayout(characterCount = 120, beforeViewport = true),
                ReaderProgressNodeLayout(characterCount = 1, beforeViewport = true),
                ReaderProgressNodeLayout(characterCount = 134, beforeViewport = false),
                ReaderProgressNodeLayout(characterCount = 26, beforeViewport = true),
                ReaderProgressNodeLayout(characterCount = 66, beforeViewport = true),
            ),
        )

        assertEquals(213.0 / 347.0, progress, 0.0)
    }

    @Test
    fun progressIsZeroWhenChapterHasNoCountableText() {
        assertEquals(
            0.0,
            readerProgressFromVisibleNodeLayouts(
                listOf(
                    ReaderProgressNodeLayout(characterCount = 0, beforeViewport = true),
                    ReaderProgressNodeLayout(characterCount = 0, beforeViewport = false),
                ),
            ),
            0.0,
        )
    }
}
