package moe.antimony.hoshi.dictionary

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DictionaryImportDataSourceSourceTest {
    @Test
    fun importDataSourceKeepsSafValidationTempCopyNativeDispatchAndCleanup() {
        val source = File("src/main/java/moe/antimony/hoshi/dictionary/DictionaryImportDataSource.kt").readText()

        assertTrue(source.contains("contentResolver.validateImportFile(uri, ImportFileType.DictionaryArchive)"))
        assertTrue(source.contains("File.createTempFile(\"hoshi-dictionary-\", \".zip\", cacheDir)"))
        assertTrue(source.contains("contentResolver.openInputStream(uri).use { input ->"))
        assertTrue(source.contains("input.copyTo(output)"))
        assertTrue(source.contains("nativeBridge.importDictionary(tempZip.absolutePath, typeDirectory.absolutePath)"))
        assertTrue(source.contains("require(imported) { \"Failed to import dictionary.\" }"))
        assertTrue(source.contains("finally"))
        assertTrue(source.contains("tempZip.delete()"))
    }
}
