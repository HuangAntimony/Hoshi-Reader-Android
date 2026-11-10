package moe.antimony.hoshi.dictionary

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import de.manhhao.hoshi.HoshiDicts
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryImportNativeTest {
    @Test
    fun manualReimportReplacesAnInstalledV3DirectoryWithQueryableV5() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "reimport-test-${UUID.randomUUID()}").apply { mkdirs() }
        val storage = DictionaryStorageDataSource(root)
        val bridge = HoshiDictionaryNativeBridge()
        val repository = DictionaryRepository(root, storage, DictionaryImportDataSource(bridge), DictionaryLookupQueryService(bridge))
        try {
            val index = """{"title":"再インポート","revision":"1","format":3}"""
            val installed = storage.typeDirectory(DictionaryType.Term).resolve("再インポート").apply { mkdirs() }
            installed.resolve("index.json").writeText(index)
            installed.resolve(".hoshidicts_3").writeText("")
            installed.resolve("hash.table").writeText("old index")
            val archive = root.resolve("reimport.zip")
            ZipOutputStream(archive.outputStream()).use { output ->
                output.putNextEntry(ZipEntry("index.json"))
                output.write(index.toByteArray())
                output.closeEntry()
                output.putNextEntry(ZipEntry("term_bank_1.json"))
                output.write("""[["食べる","たべる","","v1",0,["eat"],1,""]]""".toByteArray())
                output.closeEntry()
            }

            assertEquals(1, repository.importDictionary(context.contentResolver, Uri.fromFile(archive)))

            assertTrue(installed.resolve(".hoshidicts_5").isFile)
            assertTrue(installed.resolve("phf.table").isFile)
            assertFalse(installed.resolve(".hoshidicts_3").exists())
            assertFalse(installed.resolve("hash.table").exists())
            assertEquals("食べる", repository.lookup("食べる").first().term.expression)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun nativeFailuresExposeDetailsIncludingUnicodePaths() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "import-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val invalid = File(root, "壊れた𠮟.zip").apply { writeText("invalid zip") }
            val broken = HoshiDicts.importDictionary(invalid.absolutePath, root.absolutePath)
            assertFalse(broken.success)
            assertEquals("failed to open zip", broken.error)
            val valid = File(root, "valid.zip")
            ZipOutputStream(valid.outputStream()).use { output ->
                output.putNextEntry(ZipEntry("index.json"))
                output.write("""{"title":"辞典𠮟","revision":"1","format":3}""".toByteArray())
                output.closeEntry()
            }
            val blocker = File(root, "保存先𠮟").apply { writeText("keep") }
            val failed = HoshiDicts.importDictionary(valid.absolutePath, blocker.absolutePath)
            assertFalse(failed.success)
            assertTrue(failed.error.contains("保存先𠮟"))
            assertEquals("keep", blocker.readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
