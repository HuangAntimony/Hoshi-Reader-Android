package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.features.anki.AnkiMiningContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MineWithOptionsContextTest {
    private val base = AnkiMiningContext(
        sentence = "in-book sentence",
        documentTitle = "Book",
        sentenceOffset = 3,
    )

    @Test
    fun pickedSentenceOverridesSentenceAndClearsOffset() {
        val result = augmentedMiningContext(base, picked = "例文です。")
        assertEquals("例文です。", result.sentence)
        assertNull(result.sentenceOffset)
        assertEquals("Book", result.documentTitle)
    }

    @Test
    fun skippedExampleKeepsBaseContextUnchanged() {
        val result = augmentedMiningContext(base, picked = null)
        assertSame(base, result)
    }
}
