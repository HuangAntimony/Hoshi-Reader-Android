package moe.antimony.hoshi.dictionary

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DictionaryRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun repositoryMutationsPersistStorageConfigAndRebuildLookupPaths() {
        val filesDir = temporaryFolder.newFolder("files")
        val cacheDir = temporaryFolder.newFolder("cache")
        val storage = DictionaryStorageDataSource(filesDir)
        val bridge = RecordingDictionaryNativeBridge()
        val repository = DictionaryRepository(
            filesDir,
            cacheDir,
            storage,
            DictionaryImportDataSource(cacheDir, bridge),
            DictionaryLookupQueryService(bridge),
        )
        writeDictionary(storage.typeDirectory(DictionaryType.Term), "First", "First")
        writeDictionary(storage.typeDirectory(DictionaryType.Term), "Second", "Second")
        writeDictionary(storage.typeDirectory(DictionaryType.Frequency), "Freq", "Freq")
        writeDictionary(storage.typeDirectory(DictionaryType.Pitch), "Pitch", "Pitch")

        repository.setDictionaryEnabled(DictionaryType.Term, "Second", enabled = false)

        assertEquals(listOf("First", "Second"), repository.loadDictionaries(DictionaryType.Term).map { it.path.name })
        assertEquals(listOf(true, false), repository.loadDictionaries(DictionaryType.Term).map { it.isEnabled })
        assertEquals(listOf(filesDir.resolve("Dictionaries/Term/First").absolutePath), bridge.termPaths.toList())
        assertEquals(listOf(filesDir.resolve("Dictionaries/Frequency/Freq").absolutePath), bridge.freqPaths.toList())
        assertEquals(listOf(filesDir.resolve("Dictionaries/Pitch/Pitch").absolutePath), bridge.pitchPaths.toList())

        repository.deleteDictionary(DictionaryType.Frequency, "Freq")

        assertEquals(emptyList<String>(), repository.loadDictionaries(DictionaryType.Frequency).map { it.path.name })
        assertEquals(emptyList<String>(), bridge.freqPaths.toList())
    }

    @Test
    fun repositoryMoveRewritesIosOrderThroughStorage() {
        val filesDir = temporaryFolder.newFolder("move-files")
        val cacheDir = temporaryFolder.newFolder("move-cache")
        val storage = DictionaryStorageDataSource(filesDir)
        val repository = DictionaryRepository(
            filesDir,
            cacheDir,
            storage,
            DictionaryImportDataSource(cacheDir, RecordingDictionaryNativeBridge()),
            DictionaryLookupQueryService(RecordingDictionaryNativeBridge()),
        )
        writeDictionary(storage.typeDirectory(DictionaryType.Term), "First", "First")
        writeDictionary(storage.typeDirectory(DictionaryType.Term), "Second", "Second")
        writeDictionary(storage.typeDirectory(DictionaryType.Term), "Third", "Third")
        val initialOrder = repository.loadDictionaries(DictionaryType.Term).map { it.path.name }
        val expectedOrder = initialOrder.toMutableList().also { names ->
            val moved = names.removeAt(2)
            names.add(0, moved)
        }

        repository.moveDictionary(DictionaryType.Term, fromIndex = 2, toIndex = 0)

        assertEquals(expectedOrder, repository.loadDictionaries(DictionaryType.Term).map { it.path.name })
        assertEquals(listOf(0, 1, 2), repository.loadDictionaries(DictionaryType.Term).map { it.order })
    }

    private fun writeDictionary(typeDirectory: File, fileName: String, title: String) {
        val dictionaryDir = typeDirectory.resolve(fileName)
        dictionaryDir.mkdirs()
        dictionaryDir.resolve("index.json").writeText(
            """
            {
              "title": "$title",
              "format": 3,
              "revision": "rev"
            }
            """.trimIndent(),
        )
    }

    private class RecordingDictionaryNativeBridge : DictionaryNativeBridge {
        var termPaths: Array<String> = emptyArray()
            private set
        var freqPaths: Array<String> = emptyArray()
            private set
        var pitchPaths: Array<String> = emptyArray()
            private set

        override fun importDictionary(zipPath: String, outputDir: String): Boolean = true

        override fun rebuildQuery(
            termPaths: Array<String>,
            freqPaths: Array<String>,
            pitchPaths: Array<String>,
        ) {
            this.termPaths = termPaths
            this.freqPaths = freqPaths
            this.pitchPaths = pitchPaths
        }
    }
}
