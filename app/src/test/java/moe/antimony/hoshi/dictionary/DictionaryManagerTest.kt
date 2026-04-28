package moe.antimony.hoshi.dictionary

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DictionaryManagerTest {
    @Test
    fun dictionaryTypeDirectoryNamesMatchIosRawValues() {
        assertEquals("Term", DictionaryType.Term.directoryName)
        assertEquals("Frequency", DictionaryType.Frequency.directoryName)
        assertEquals("Pitch", DictionaryType.Pitch.directoryName)
    }

    @Test
    fun dictionaryConfigUsesIosJsonShape() {
        val config = DictionaryConfig(
            termDictionaries = listOf(DictionaryConfig.DictionaryEntry("JMdict", isEnabled = true, order = 0)),
            frequencyDictionaries = emptyList(),
            pitchDictionaries = emptyList(),
        )

        val json = Json.encodeToString(config)

        assertEquals(
            """{"termDictionaries":[{"fileName":"JMdict","isEnabled":true,"order":0}],"frequencyDictionaries":[],"pitchDictionaries":[]}""",
            json,
        )
    }

    @Test
    fun collectDictionariesPreservesConfigOrderAndAppendsUnconfiguredImports() {
        val stored = listOf(
            dictionaryInfo(title = "Unconfigured", fileName = "Unconfigured"),
            dictionaryInfo(title = "Second", fileName = "Second"),
            dictionaryInfo(title = "First", fileName = "First"),
        )
        val config = listOf(
            DictionaryConfig.DictionaryEntry(fileName = "First", isEnabled = false, order = 1),
            DictionaryConfig.DictionaryEntry(fileName = "Second", isEnabled = true, order = 0),
        )

        val result = DictionaryManager.collectDictionaries(stored, config)

        assertEquals(listOf("Second", "First", "Unconfigured"), result.map { it.index.title })
        assertEquals(listOf(true, false, true), result.map { it.isEnabled })
        assertEquals(listOf(0, 1, 2), result.map { it.order })
    }

    private fun dictionaryInfo(title: String, fileName: String): DictionaryInfo =
        DictionaryInfo(
            index = DictionaryIndex(
                title = title,
                format = 3,
                revision = "test",
                isUpdatable = false,
                indexUrl = "",
                downloadUrl = "",
            ),
            path = File("/tmp/$fileName"),
        )
}
