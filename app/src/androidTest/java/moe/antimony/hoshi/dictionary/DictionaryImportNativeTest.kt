package moe.antimony.hoshi.dictionary

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
