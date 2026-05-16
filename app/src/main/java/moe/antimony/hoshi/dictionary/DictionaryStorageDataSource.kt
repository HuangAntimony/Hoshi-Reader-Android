package moe.antimony.hoshi.dictionary

import kotlinx.serialization.json.Json
import java.io.File

internal class DictionaryStorageDataSource(
    filesDir: File,
    private val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    },
) {
    private val dictionariesDir = File(filesDir, "Dictionaries")
    private val configFile = File(dictionariesDir, "config.json")

    fun loadDictionaries(type: DictionaryType): List<DictionaryInfo> {
        val stored = storedDictionaries(type)
        val entries = loadConfig().entriesForType(type)
        return DictionaryManager.collectDictionaries(stored, entries)
    }

    fun currentConfig(): DictionaryConfig = DictionaryConfig(
        termDictionaries = configEntries(DictionaryType.Term),
        frequencyDictionaries = configEntries(DictionaryType.Frequency),
        pitchDictionaries = configEntries(DictionaryType.Pitch),
    )

    fun updatableDictionaries(): List<DictionaryUpdateCandidate> =
        DictionaryType.entries.flatMap { type ->
            loadDictionaries(type)
                .filter { dictionary ->
                    dictionary.index.isUpdatable &&
                        dictionary.index.indexUrl.isNotBlank() &&
                        dictionary.index.downloadUrl.isNotBlank()
                }
                .map { dictionary -> DictionaryUpdateCandidate(dictionary, type) }
        }

    fun configWithDictionaryEnabled(
        type: DictionaryType,
        fileName: String,
        enabled: Boolean,
    ): DictionaryConfig =
        currentConfig().copyForType(type) { entries ->
            entries.map { entry ->
                if (entry.fileName == fileName) {
                    entry.copy(isEnabled = enabled)
                } else {
                    entry
                }
            }
        }

    fun configWithDictionaryMoved(
        type: DictionaryType,
        fromIndex: Int,
        toIndex: Int,
    ): DictionaryConfig =
        currentConfig().copyForType(type) {
            DictionaryManager.moveDictionaries(loadDictionaries(type), fromIndex, toIndex)
        }

    fun configWithImportedDictionaryReplacing(
        type: DictionaryType,
        replacementFileName: String,
        enabled: Boolean,
        order: Int,
    ): DictionaryConfig =
        currentConfig().copyForType(type) {
            val dictionaries = loadDictionaries(type)
            val replacement = dictionaries.firstOrNull { dictionary ->
                dictionary.path.name == replacementFileName
            } ?: return@copyForType it
            val ordered = dictionaries
                .filterNot { dictionary -> dictionary.path.name == replacementFileName }
                .toMutableList()
            ordered.add(order.coerceIn(0, ordered.size), replacement.copy(isEnabled = enabled))
            ordered.mapIndexed { index, dictionary ->
                DictionaryConfig.DictionaryEntry(
                    fileName = dictionary.path.name,
                    isEnabled = if (dictionary.path.name == replacementFileName) enabled else dictionary.isEnabled,
                    order = index,
                )
            }
        }

    fun saveConfigFromStorage() {
        saveConfig(currentConfig())
    }

    fun saveConfig(config: DictionaryConfig) {
        dictionariesDir.mkdirs()
        configFile.writeText(json.encodeToString(config))
    }

    fun deleteDictionary(type: DictionaryType, fileName: String) {
        File(typeDirectory(type), fileName).deleteRecursively()
    }

    fun hasDictionaryWithIndex(type: DictionaryType, index: DictionaryIndex): Boolean =
        loadDictionaries(type).any { dictionary ->
            dictionary.index.title == index.title
        }

    fun enabledDictionaryPaths(type: DictionaryType): List<File> =
        loadDictionaries(type)
            .filter { it.isEnabled }
            .map { it.path }

    fun typeDirectory(type: DictionaryType): File =
        File(dictionariesDir, type.directoryName)

    fun importRootDirectory(): File = dictionariesDir

    private fun storedDictionaries(type: DictionaryType): List<DictionaryInfo> {
        val directory = typeDirectory(type)
        directory.mkdirs()
        return directory.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dictionaryDir ->
                val index = runCatching {
                    json.decodeFromString<DictionaryIndex>(File(dictionaryDir, "index.json").readText())
                }.getOrNull() ?: return@mapNotNull null
                DictionaryInfo(index = index, path = dictionaryDir)
            }
            .orEmpty()
    }

    private fun configEntries(type: DictionaryType): List<DictionaryConfig.DictionaryEntry> =
        loadDictionaries(type).mapIndexed { index, dictionary ->
            DictionaryConfig.DictionaryEntry(dictionary.path.name, dictionary.isEnabled, index)
        }

    private fun loadConfig(): DictionaryConfig =
        runCatching {
            if (!configFile.exists()) return EmptyDictionaryConfig
            json.decodeFromString<DictionaryConfig>(configFile.readText())
        }.getOrDefault(EmptyDictionaryConfig)
}

private val EmptyDictionaryConfig = DictionaryConfig(
    termDictionaries = emptyList(),
    frequencyDictionaries = emptyList(),
    pitchDictionaries = emptyList(),
)

private fun DictionaryConfig.entriesForType(type: DictionaryType): List<DictionaryConfig.DictionaryEntry> =
    when (type) {
        DictionaryType.Term -> termDictionaries
        DictionaryType.Frequency -> frequencyDictionaries
        DictionaryType.Pitch -> pitchDictionaries
    }

private fun DictionaryConfig.copyForType(
    type: DictionaryType,
    transform: (List<DictionaryConfig.DictionaryEntry>) -> List<DictionaryConfig.DictionaryEntry>,
): DictionaryConfig = when (type) {
    DictionaryType.Term -> copy(termDictionaries = transform(termDictionaries))
    DictionaryType.Frequency -> copy(frequencyDictionaries = transform(frequencyDictionaries))
    DictionaryType.Pitch -> copy(pitchDictionaries = transform(pitchDictionaries))
}
