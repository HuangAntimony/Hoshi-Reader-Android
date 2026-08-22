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
        assertEquals("Kanji", DictionaryType.Kanji.directoryName)
    }

    @Test
    fun dictionaryConfigUsesIosJsonShape() {
        val config = DictionaryConfig(
            termDictionaries = listOf(
                DictionaryConfig.DictionaryEntry(
                    "JMdict",
                    isEnabled = true,
                    order = 0,
                    category = DictionaryCategory.Monolingual,
                ),
            ),
            frequencyDictionaries = emptyList(),
            pitchDictionaries = emptyList(),
            kanjiDictionaries = listOf(DictionaryConfig.DictionaryEntry("KANJIDIC", isEnabled = true, order = 0)),
        )

        val json = Json.encodeToString(config)

        assertEquals(
            """{"termDictionaries":[{"fileName":"JMdict","isEnabled":true,"order":0,"category":"monolingual"}],"frequencyDictionaries":[],"pitchDictionaries":[],"kanjiDictionaries":[{"fileName":"KANJIDIC","isEnabled":true,"order":0,"category":"none"}]}""",
            json,
        )
    }

    @Test
    fun dictionaryConfigDefaultsNewFieldsWhenLoadingLegacyJson() {
        val config = Json.decodeFromString<DictionaryConfig>(
            """{"termDictionaries":[{"fileName":"JMdict","isEnabled":true,"order":0}],"frequencyDictionaries":[],"pitchDictionaries":[]}""",
        )

        assertEquals(DictionaryCategory.None, config.termDictionaries.single().category)
        assertEquals(emptyList<DictionaryConfig.DictionaryEntry>(), config.kanjiDictionaries)
    }

    @Test
    fun dictionaryIndexAcceptsYomitanMetaDictionariesWithMissingUpdateFields() {
        val json = Json { ignoreUnknownKeys = true }

        val index = json.decodeFromString<DictionaryIndex>(
            """
                {
                  "title": "アクセント辞典",
                  "format": 3,
                  "revision": "pitch1",
                  "sequenced": false,
                  "author": "Sayash"
                }
            """.trimIndent(),
        )

        assertEquals("アクセント辞典", index.title)
        assertEquals(3, index.format)
        assertEquals("pitch1", index.revision)
        assertEquals(false, index.isUpdatable)
        assertEquals("", index.indexUrl)
        assertEquals("", index.downloadUrl)
    }

    @Test
    fun dictionaryIndexAcceptsNativeSummaryWithVersionInsteadOfFormat() {
        val index = Json { ignoreUnknownKeys = true }.decodeFromString<DictionaryIndex>(
            """{"title":"KANJIDIC","revision":"2026.08","version":3}""",
        )

        assertEquals(3, index.format)
        assertEquals("KANJIDIC", index.title)
    }

    @Test
    fun collectDictionariesPreservesConfigOrderAndAppendsUnconfiguredImports() {
        val stored = listOf(
            dictionaryInfo(title = "Unconfigured", fileName = "Unconfigured"),
            dictionaryInfo(title = "Second", fileName = "Second"),
            dictionaryInfo(title = "First", fileName = "First"),
        )
        val config = listOf(
            DictionaryConfig.DictionaryEntry(
                fileName = "First",
                isEnabled = false,
                order = 1,
                category = DictionaryCategory.Bilingual,
            ),
            DictionaryConfig.DictionaryEntry(fileName = "Second", isEnabled = true, order = 0),
        )

        val result = DictionaryManager.collectDictionaries(stored, config)

        assertEquals(listOf("Second", "First", "Unconfigured"), result.map { it.index.title })
        assertEquals(listOf(true, false, true), result.map { it.isEnabled })
        assertEquals(listOf(0, 1, 2), result.map { it.order })
        assertEquals(
            listOf(DictionaryCategory.None, DictionaryCategory.Bilingual, DictionaryCategory.None),
            result.map { it.category },
        )
    }

    @Test
    fun moveDictionariesRewritesIosConfigOrderWithoutChangingEnabledState() {
        val dictionaries = listOf(
            dictionaryInfo(title = "First", fileName = "First", isEnabled = true),
            dictionaryInfo(title = "Second", fileName = "Second", isEnabled = false),
            dictionaryInfo(
                title = "Third",
                fileName = "Third",
                isEnabled = true,
                category = DictionaryCategory.Exclude,
            ),
        )

        val result = DictionaryManager.moveDictionaries(dictionaries, fromIndex = 2, toIndex = 0)

        assertEquals(listOf("Third", "First", "Second"), result.map { it.fileName })
        assertEquals(listOf(true, true, false), result.map { it.isEnabled })
        assertEquals(listOf(0, 1, 2), result.map { it.order })
        assertEquals(DictionaryCategory.Exclude, result.first().category)
    }

    @Test
    fun moveDictionariesClampsTargetIndexLikeIosMove() {
        val dictionaries = listOf(
            dictionaryInfo(title = "First", fileName = "First"),
            dictionaryInfo(title = "Second", fileName = "Second"),
        )

        val result = DictionaryManager.moveDictionaries(dictionaries, fromIndex = 0, toIndex = 99)

        assertEquals(listOf("Second", "First"), result.map { it.fileName })
        assertEquals(listOf(0, 1), result.map { it.order })
    }

    private fun dictionaryInfo(
        title: String,
        fileName: String,
        isEnabled: Boolean = true,
        category: DictionaryCategory = DictionaryCategory.None,
    ): DictionaryInfo =
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
            isEnabled = isEnabled,
            category = category,
        )
}
