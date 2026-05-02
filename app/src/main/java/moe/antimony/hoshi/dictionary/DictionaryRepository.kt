package moe.antimony.hoshi.dictionary

import android.content.ContentResolver
import android.net.Uri
import de.manhhao.hoshi.HoshiDicts
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.importing.ImportFileType
import moe.antimony.hoshi.importing.validateImportFile
import java.io.File

class DictionaryRepository(
    private val filesDir: File,
    private val cacheDir: File,
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    private val dictionariesDir = File(filesDir, "Dictionaries")
    private val configFile = File(dictionariesDir, "config.json")

    fun loadDictionaries(type: DictionaryType): List<DictionaryInfo> {
        val stored = dictionariesForType(type)
        val config = loadConfig()
        val entries = when (type) {
            DictionaryType.Term -> config?.termDictionaries.orEmpty()
            DictionaryType.Frequency -> config?.frequencyDictionaries.orEmpty()
            DictionaryType.Pitch -> config?.pitchDictionaries.orEmpty()
        }
        return DictionaryManager.collectDictionaries(stored, entries)
    }

    fun importDictionary(contentResolver: ContentResolver, uri: Uri, type: DictionaryType) {
        contentResolver.validateImportFile(uri, ImportFileType.DictionaryArchive)
        val typeDirectory = typeDirectory(type).also { it.mkdirs() }
        val tempZip = File.createTempFile("hoshi-dictionary-", ".zip", cacheDir)
        try {
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Unable to open dictionary file." }
                tempZip.outputStream().use { output -> input.copyTo(output) }
            }
            val result = HoshiDicts.importDictionary(tempZip.absolutePath, typeDirectory.absolutePath)
            require(result.success) { "Failed to import dictionary." }
            saveConfigFromStorage()
            rebuildLookupQuery()
        } finally {
            tempZip.delete()
        }
    }

    fun setDictionaryEnabled(type: DictionaryType, fileName: String, enabled: Boolean) {
        val config = currentConfig().copyForType(type) { entries ->
            entries.map { entry ->
                if (entry.fileName == fileName) {
                    entry.copy(isEnabled = enabled)
                } else {
                    entry
                }
            }
        }
        saveConfig(config)
        rebuildLookupQuery()
    }

    fun deleteDictionary(type: DictionaryType, fileName: String) {
        File(typeDirectory(type), fileName).deleteRecursively()
        saveConfig(currentConfig())
        rebuildLookupQuery()
    }

    fun moveDictionary(type: DictionaryType, fromIndex: Int, toIndex: Int) {
        val config = currentConfig().copyForType(type) {
            DictionaryManager.moveDictionaries(loadDictionaries(type), fromIndex, toIndex)
        }
        saveConfig(config)
        rebuildLookupQuery()
    }

    fun rebuildLookupQuery() {
        val termPaths = loadDictionaries(DictionaryType.Term).filter { it.isEnabled }.map { it.path.absolutePath }.toTypedArray()
        val freqPaths = loadDictionaries(DictionaryType.Frequency).filter { it.isEnabled }.map { it.path.absolutePath }.toTypedArray()
        val pitchPaths = loadDictionaries(DictionaryType.Pitch).filter { it.isEnabled }.map { it.path.absolutePath }.toTypedArray()
        HoshiDicts.rebuildQuery(HoshiDicts.lookupObject, termPaths, freqPaths, pitchPaths)
    }

    private fun dictionariesForType(type: DictionaryType): List<DictionaryInfo> {
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

    private fun saveConfigFromStorage() {
        saveConfig(currentConfig())
    }

    private fun currentConfig(): DictionaryConfig = DictionaryConfig(
        termDictionaries = configEntries(DictionaryType.Term),
        frequencyDictionaries = configEntries(DictionaryType.Frequency),
        pitchDictionaries = configEntries(DictionaryType.Pitch),
    )

    private fun configEntries(type: DictionaryType): List<DictionaryConfig.DictionaryEntry> =
        loadDictionaries(type).mapIndexed { index, dictionary ->
            DictionaryConfig.DictionaryEntry(dictionary.path.name, dictionary.isEnabled, index)
        }

    private fun saveConfig(config: DictionaryConfig) {
        dictionariesDir.mkdirs()
        configFile.writeText(json.encodeToString(config))
    }

    private fun loadConfig(): DictionaryConfig? =
        runCatching {
            if (!configFile.exists()) return null
            json.decodeFromString<DictionaryConfig>(configFile.readText())
        }.getOrNull()

    private fun typeDirectory(type: DictionaryType): File =
        File(dictionariesDir, type.directoryName)
}

private fun DictionaryConfig.copyForType(
    type: DictionaryType,
    transform: (List<DictionaryConfig.DictionaryEntry>) -> List<DictionaryConfig.DictionaryEntry>,
): DictionaryConfig = when (type) {
    DictionaryType.Term -> copy(termDictionaries = transform(termDictionaries))
    DictionaryType.Frequency -> copy(frequencyDictionaries = transform(frequencyDictionaries))
    DictionaryType.Pitch -> copy(pitchDictionaries = transform(pitchDictionaries))
}
