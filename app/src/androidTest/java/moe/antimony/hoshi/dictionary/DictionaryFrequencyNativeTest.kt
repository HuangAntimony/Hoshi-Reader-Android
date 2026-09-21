package moe.antimony.hoshi.dictionary

import androidx.test.platform.app.InstrumentationRegistry
import de.manhhao.hoshi.HoshiDicts
import de.manhhao.hoshi.LookupFrequencyOrder
import de.manhhao.hoshi.LookupOptions
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses only generated dictionaries under a unique cache directory; never touches installed dictionaries. */
class DictionaryFrequencyNativeTest {
    @Test
    fun nativeFrequencyOptionsSortSelectedDictionaryAndIgnoreUnavailableOnes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "frequency-test-${UUID.randomUUID()}").apply { mkdirs() }
        val session = HoshiDicts.createLookupObject("ja")
        try {
            val terms = import(root, "Terms", "term_bank_1.json", """[
                ["橋","はし","","",0,["bridge"],1,""],
                ["箸","はし","","",0,["chopsticks"],2,""],
                ["端","はし","","",0,["edge"],3,""]
            ]""")
            val rank = import(root, "順位𠮟", "term_meta_bank_1.json", """[
                ["橋","freq",10],["箸","freq",100]
            ]""")
            val reversed = import(root, "Other", "term_meta_bank_1.json", """[
                ["橋","freq",100],["箸","freq",10]
            ]""")
            fun rebuild(frequencies: Array<String>) = HoshiDicts.rebuildQuery(
                session, arrayOf(terms), frequencies, emptyArray(), emptyArray(),
            )
            fun lookup(order: LookupFrequencyOrder, title: String? = null) =
                HoshiDicts.lookup(session, "はし", 16, 16, LookupOptions(order, title)).map { it.term.expression }
            rebuild(arrayOf(rank, reversed))
            val ascending = lookup(LookupFrequencyOrder.Ascending, "順位𠮟")
            assertEquals(setOf("橋", "箸", "端"), ascending.toSet())
            assertTrue(ascending.indexOf("橋") < ascending.indexOf("箸"))
            assertTrue(ascending.indexOf("箸") < ascending.indexOf("端"))
            val descending = lookup(LookupFrequencyOrder.Descending, "順位𠮟")
            assertTrue(descending.indexOf("箸") < descending.indexOf("橋"))
            val other = lookup(LookupFrequencyOrder.Ascending, "Other")
            assertTrue(other.indexOf("箸") < other.indexOf("橋"))
            assertEquals(ascending, lookup(LookupFrequencyOrder.Auto))
            assertEquals(lookup(LookupFrequencyOrder.Disabled), lookup(LookupFrequencyOrder.Ascending, "missing"))
            rebuild(arrayOf(reversed, rank))
            assertEquals(ascending, lookup(LookupFrequencyOrder.Ascending, "順位𠮟"))
            rebuild(arrayOf(reversed))
            assertEquals(lookup(LookupFrequencyOrder.Disabled), lookup(LookupFrequencyOrder.Ascending, "順位𠮟"))
            val equal = import(root, "Equal", "term_meta_bank_1.json", """[["橋","freq",10],["箸","freq",10]]""")
            rebuild(arrayOf(equal))
            assertEquals(lookup(LookupFrequencyOrder.Ascending, "Equal"), lookup(LookupFrequencyOrder.Descending, "Equal"))
        } finally {
            HoshiDicts.destroyLookupObject(session)
            root.deleteRecursively()
        }
    }

    private fun import(root: File, title: String, bank: String, contents: String): String {
        val zip = File(root, "$title.zip")
        ZipOutputStream(zip.outputStream()).use { output ->
            mapOf("index.json" to """{"title":"$title","revision":"1","format":3} """, bank to contents).forEach { (name, text) ->
                output.putNextEntry(ZipEntry(name))
                output.write(text.toByteArray())
                output.closeEntry()
            }
        }
        val result = HoshiDicts.importDictionary(zip.absolutePath, root.absolutePath)
        assertTrue("Import $title", result.success)
        return File(root, title).absolutePath
    }
}
