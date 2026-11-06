package moe.antimony.hoshi.dictionary

import de.manhhao.hoshi.HoshiDicts
import de.manhhao.hoshi.LookupResult
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** Generated dictionaries and native sessions only; never touches the installed dictionary store. */
class DictionaryLanguagesNativeTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    @Test fun chineseNormalizesRadicalsAndGroupsPinyinWithoutChangingDisplayedReading() = withScratch { root ->
        val first = dictionary(root, "汉语𠀀", """[
            ["日文","Rì wén","","",0,["first"],1,""],
            ["一","yī","","",0,["one"],2,""],
            ["𠀀","hē","","",0,["supplementary"],3,""],
            ["汉语","hànyǔ","","",0,["simplified"],4,""],
            ["漢語","hànyǔ","","",0,["traditional"],5,""]
        ]""")
        val second = dictionary(root, "Second", """[["日文","rìwén","","",0,["second"],1,""]]""")
        withSession("zh", arrayOf(first, second)) { session ->
            val result = lookup(session, "日文").single { it.term.expression == "日文" }
            assertEquals("Rì wén", result.term.reading)
            assertEquals(listOf("汉语𠀀", "Second"), result.term.glossaries.map { it.dictName })
            assertEquals("一", lookup(session, "⼀").first().term.expression)
            assertEquals("𠀀", lookup(session, "𠀀").first().matched)
            assertTrue(lookup(session, "Rì wén").any { it.term.expression == "日文" })
            assertEquals("汉语", lookup(session, "汉语").first().term.expression)
            assertEquals("漢語", lookup(session, "漢語").first().term.expression)
        }
    }

    @Test fun koreanDeinflectsCompatibleVerbsAndKeepsUntaggedExactMatches() = withScratch { root ->
        val verb = dictionary(root, "한국어", """[["읽다","","","v",0,["read"],1,""]]""")
        val untagged = dictionary(root, "Untagged", """[["읽다","","","",0,["read"],1,""]]""")
        withSession("ko", arrayOf(verb)) { session ->
            val result = lookup(session, "읽었어요").first { it.term.expression == "읽다" }
            assertEquals("읽었어요", result.matched)
            assertTrue(result.traceCandidates.any { it.deinflected == "읽다" && it.trace.isNotEmpty() })
        }
        withSession("ko", arrayOf(untagged)) { session ->
            assertTrue(lookup(session, "읽었어요").none { it.term.expression == "읽다" })
            assertEquals("읽다", lookup(session, "읽다").first().term.expression)
        }
    }

    @Test fun japaneseAndEnglishSessionsStillDeinflectIndependently() = withScratch { root ->
        val terms = dictionary(root, "Regression", """[
            ["食べる","たべる","","v1",0,["eat"],1,""],
            ["read","","","v",0,["read"],2,""]
        ]""")
        withSession("ja", arrayOf(terms)) { session ->
            assertTrue(lookup(session, "食べた").any { it.term.expression == "食べる" })
        }
        withSession("en", arrayOf(terms)) { session ->
            assertTrue(lookup(session, "reading").any { it.term.expression == "read" })
        }
    }

    @Test fun japanesePosFilteringSharesTaggedConditionsOnlyWithUntaggedEntries() = withScratch { root ->
        val verb = dictionary(root, "Verb", """[["食べる","たべる","","v1",0,["verb"],1,""]]""")
        val untagged = dictionary(root, "Untagged", """[["食べる","たべる","","",0,["untagged"],1,""]]""")
        val incompatible = dictionary(root, "Adjective", """[["食べる","たべる","","adj-i",0,["adjective"],1,""]]""")
        withSession("ja", arrayOf(verb, untagged, incompatible)) { session ->
            val inflected = lookup(session, "食べた").first { it.term.expression == "食べる" }
            assertEquals(listOf("Verb", "Untagged"), inflected.term.glossaries.map { it.dictName })
            val exact = lookup(session, "食べる").first { it.term.expression == "食べる" }
            assertEquals(listOf("Verb", "Untagged", "Adjective"), exact.term.glossaries.map { it.dictName })
        }
    }

    private fun lookup(session: Long, text: String): Array<LookupResult> = HoshiDicts.lookup(session, text, 32, 32)

    private fun withSession(language: String, terms: Array<String>, block: (Long) -> Unit) {
        val session = HoshiDicts.createLookupObject(language)
        try {
            HoshiDicts.rebuildQuery(session, terms, emptyArray(), emptyArray(), emptyArray())
            block(session)
        } finally { HoshiDicts.destroyLookupObject(session) }
    }

    private fun withScratch(block: (File) -> Unit) {
        val root = temporaryFolder.newFolder("languages")
        try { block(root) } finally { root.deleteRecursively() }
    }

    private fun dictionary(root: File, title: String, terms: String): String {
        val zip = File(root, "$title.zip")
        ZipOutputStream(zip.outputStream()).use { output ->
            mapOf("index.json" to """{"title":"$title","revision":"1","format":3}""", "term_bank_1.json" to terms).forEach { (name, text) ->
                output.putNextEntry(ZipEntry(name))
                output.write(text.toByteArray())
                output.closeEntry()
            }
        }
        val result = HoshiDicts.importDictionary(zip.absolutePath, root.absolutePath)
        assertTrue(result.error, result.success)
        return File(root, title).absolutePath
    }
}
