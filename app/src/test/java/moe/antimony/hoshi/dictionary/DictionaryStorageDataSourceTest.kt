package moe.antimony.hoshi.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DictionaryStorageDataSourceTest {
    @Test
    fun loadDictionariesMergesStoredDirectoriesWithIosConfigOrder() {
        withTempDir { root ->
            val storage = DictionaryStorageDataSource(root)
            writeDictionary(storage.typeDirectory(DictionaryType.Term), "First", "First Title")
            writeDictionary(storage.typeDirectory(DictionaryType.Term), "Second", "Second Title")
            writeDictionary(storage.typeDirectory(DictionaryType.Term), "Unconfigured", "Unconfigured Title")
            storage.saveConfig(
                DictionaryConfig(
                    termDictionaries = listOf(
                        DictionaryConfig.DictionaryEntry("Second", isEnabled = false, order = 0),
                        DictionaryConfig.DictionaryEntry("First", isEnabled = true, order = 1),
                    ),
                    frequencyDictionaries = emptyList(),
                    pitchDictionaries = emptyList(),
                ),
            )

            val dictionaries = storage.loadDictionaries(DictionaryType.Term)

            assertEquals(listOf("Second", "First", "Unconfigured"), dictionaries.map { it.path.name })
            assertEquals(listOf(false, true, true), dictionaries.map { it.isEnabled })
            assertEquals(listOf(0, 1, 2), dictionaries.map { it.order })
        }
    }

    @Test
    fun currentConfigUsesStoredOrderAndEnabledStateWithoutChangingJsonShape() {
        withTempDir { root ->
            val storage = DictionaryStorageDataSource(root)
            writeDictionary(storage.typeDirectory(DictionaryType.Term), "JMdict", "JMdict")
            writeDictionary(storage.typeDirectory(DictionaryType.Frequency), "Freq", "Freq")
            storage.saveConfig(
                DictionaryConfig(
                    termDictionaries = listOf(DictionaryConfig.DictionaryEntry("JMdict", isEnabled = false, order = 0)),
                    frequencyDictionaries = listOf(DictionaryConfig.DictionaryEntry("Freq", isEnabled = true, order = 0)),
                    pitchDictionaries = emptyList(),
                ),
            )

            val config = storage.currentConfig()

            assertEquals(listOf(DictionaryConfig.DictionaryEntry("JMdict", isEnabled = false, order = 0)), config.termDictionaries)
            assertEquals(listOf(DictionaryConfig.DictionaryEntry("Freq", isEnabled = true, order = 0)), config.frequencyDictionaries)
            assertEquals(emptyList<DictionaryConfig.DictionaryEntry>(), config.pitchDictionaries)
            val configText = root.resolve("Dictionaries/config.json").readText()
            assertTrue(configText.contains("\"termDictionaries\""))
            assertTrue(configText.contains("\"frequencyDictionaries\""))
            assertTrue(configText.contains("\"pitchDictionaries\""))
            assertFalse(configText.contains("term_dictionaries"))
        }
    }

    @Test
    fun deleteDictionaryRemovesOnlyRequestedTypeDirectory() {
        withTempDir { root ->
            val storage = DictionaryStorageDataSource(root)
            val term = writeDictionary(storage.typeDirectory(DictionaryType.Term), "Shared", "Term")
            val pitch = writeDictionary(storage.typeDirectory(DictionaryType.Pitch), "Shared", "Pitch")

            storage.deleteDictionary(DictionaryType.Term, "Shared")

            assertFalse(term.exists())
            assertTrue(pitch.exists())
        }
    }

    private fun writeDictionary(typeDirectory: File, fileName: String, title: String): File {
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
        return dictionaryDir
    }

    private fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("hoshi-dictionary-storage-test").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
