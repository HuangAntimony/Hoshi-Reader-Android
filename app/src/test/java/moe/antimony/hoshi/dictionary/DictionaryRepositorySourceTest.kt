package moe.antimony.hoshi.dictionary

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DictionaryRepositorySourceTest {
    @Test
    fun repositoryDelegatesStorageImportAndLookupQueryResponsibilities() {
        val source = File("src/main/java/moe/antimony/hoshi/dictionary/DictionaryRepository.kt").readText()

        assertTrue(source.contains("private val storage: DictionaryStorageDataSource"))
        assertTrue(source.contains("private val importDataSource: DictionaryImportDataSource"))
        assertTrue(source.contains("private val lookupQueryService: DictionaryLookupQueryService"))
        assertTrue(source.contains("storage.loadDictionaries(type)"))
        assertTrue(source.contains("importDataSource.importDictionary(contentResolver, uri, storage.typeDirectory(type))"))
        assertTrue(source.contains("lookupQueryService.rebuild("))

        assertFalse(source.contains("HoshiDicts."))
        assertFalse(source.contains("File.createTempFile("))
        assertFalse(source.contains("openInputStream(uri)"))
        assertFalse(source.contains("json.decodeFromString"))
        assertFalse(source.contains("configFile"))
    }
}
