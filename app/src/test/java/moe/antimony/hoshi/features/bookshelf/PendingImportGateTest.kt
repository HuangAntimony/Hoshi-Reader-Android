package moe.antimony.hoshi.features.bookshelf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingImportGateTest {
    @Test
    fun rejectsDuplicatePendingImportsUntilTheActiveImportFinishes() {
        val gate = PendingImportGate<String>()

        assertTrue(gate.tryStart("content://books/test.epub"))
        assertFalse(gate.tryStart("content://books/test.epub"))
        assertFalse(gate.tryStart("content://books/other.epub"))

        gate.finish("content://books/test.epub")

        assertTrue(gate.tryStart("content://books/test.epub"))
    }

    @Test
    fun ignoresFinishForAnOlderImportToken() {
        val gate = PendingImportGate<String>()

        assertTrue(gate.tryStart("content://books/test.epub"))
        gate.finish("content://books/other.epub")

        assertFalse(gate.tryStart("content://books/test.epub"))
    }
}
