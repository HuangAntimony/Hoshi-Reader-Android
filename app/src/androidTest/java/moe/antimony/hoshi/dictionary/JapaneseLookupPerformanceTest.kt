package moe.antimony.hoshi.dictionary

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import de.manhhao.hoshi.DictionaryStyle
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.system.measureNanoTime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Opt-in generated-dictionary benchmark; never reads the installed dictionary store. */
class JapaneseLookupPerformanceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun compareForcedRebuildWithSessionReuse() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("lookupPerformance") == "true")
        val root = temporary.newFolder()
        val native = HoshiDictionaryNativeBridge()
        val openSessions = mutableSetOf<Long>()
        var creates = 0
        var styleReads = 0
        val bridge = object : DictionaryNativeBridge by native {
            override fun createLookupObject(languageId: String): Long = native.createLookupObject(languageId).also {
                creates++
                openSessions += it
            }
            override fun destroyLookupObject(session: Long) {
                native.destroyLookupObject(session)
                openSessions -= session
            }
            override fun getStyles(session: Long): List<DictionaryStyle> {
                styleReads++
                return native.getStyles(session)
            }
        }
        try {
            val storage = DictionaryStorageDataSource(root)
            val dictionaryDirectory = storage.typeDirectory(DictionaryType.Term)
            val archive = File(root, "fixture.zip")
            val terms = JSONArray().apply {
                listOf("食べる" to "v1", "読む" to "v5", "𠮟る" to "v5", "猫" to "", "犬" to "").forEachIndexed { i, (term, rule) ->
                    put(JSONArray(listOf(term, term, "", rule, 0, listOf("definition-$i"), i, "")))
                }
                repeat(10_000) { i -> put(JSONArray(listOf("項目$i", "こうもく", "", "", 0, listOf("entry-$i"), i + 5, ""))) }
            }
            ZipOutputStream(archive.outputStream()).use { zip ->
                mapOf("index.json" to """{"title":"PerformanceFixture","revision":"1","format":3}""",
                    "term_bank_1.json" to terms.toString(), "styles.css" to ".fixture { color: inherit; }").forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
            }
            val imported = native.importDictionary(archive.absolutePath, dictionaryDirectory.absolutePath, false)
            assertTrue(imported.error, imported.success)
            storage.saveConfigFromStorage()
            val repository = DictionaryRepository(root, storage, DictionaryImportDataSource(bridge), DictionaryLookupQueryService(bridge))
            val cases = listOf("食べました" to "食べる", "読んだ" to "読む", "𠮟られた" to "𠮟る", "猫" to "猫", "犬" to "犬")
            fun runBlock(rebuild: Boolean): JSONObject {
                val beforeCreates = creates
                val beforeStyles = styleReads
                var elapsed = 0L
                repeat(20) { index ->
                    val (query, expected) = cases[index % cases.size]
                    elapsed += measureNanoTime {
                        if (rebuild) repository.rebuildLookupQuery() else repository.ensureLookupQueryReady()
                        assertTrue(repository.dictionaryStyles().containsKey("PerformanceFixture"))
                        val results = repository.lookup(query)
                        assertTrue("$query -> ${results.map { it.term.expression }}", results.any { it.term.expression == expected })
                    }
                }
                assertEquals(if (rebuild) 20 else 0, creates - beforeCreates)
                assertEquals(if (rebuild) 20 else 0, styleReads - beforeStyles)
                return JSONObject().put("rebuild", rebuild).put("nsPerLookup", elapsed / 20.0)
                    .put("sessionCreates", creates - beforeCreates).put("styleReads", styleReads - beforeStyles)
            }
            repository.ensureLookupQueryReady()
            repository.dictionaryStyles()
            repeat(2) { runBlock(true); runBlock(false) }
            val samples = JSONArray()
            repeat(10) { pair ->
                (if (pair % 2 == 0) listOf(true, false) else listOf(false, true)).forEach { rebuild ->
                    samples.put(runBlock(rebuild).put("pair", pair))
                }
            }
            val result = JSONObject().put("entries", 10_005).put("samples", samples)
            val output = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "lookup-performance-native.json")
            output.writeText(result.toString())
            Log.i("HoshiLookupPerformance", "native=$result")
        } finally {
            openSessions.toList().forEach(native::destroyLookupObject)
        }
    }
}
