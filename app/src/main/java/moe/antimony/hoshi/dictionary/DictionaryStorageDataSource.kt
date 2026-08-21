package moe.antimony.hoshi.dictionary

import java.io.File
import javax.inject.Inject
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.di.FilesDir
import moe.antimony.hoshi.profiles.ProfileRepository

internal class DictionaryStorageDataSource(
    filesDir: File,
    private val json: Json = defaultJson(),
    private val profileRepository: ProfileRepository? = null,
) {
    @Inject
    constructor(@FilesDir filesDir: File, profileRepository: ProfileRepository) : this(
        filesDir = filesDir,
        json = defaultJson(),
        profileRepository = profileRepository,
    )

    private val dictionariesDir = File(filesDir, "Dictionaries")
    private val configFile: File
        get() = configFile()

    fun loadDictionaries(type: DictionaryType): List<DictionaryInfo> {
        return loadDictionaries(
            type = type,
            configFile = configFile,
            unconfiguredDictionariesEnabled = true,
        )
    }

    fun currentConfig(): DictionaryConfig = currentConfig(
        configFile = configFile,
        unconfiguredDictionariesEnabled = true,
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

    fun configWithDictionaryCategory(
        fileName: String,
        category: DictionaryCategory,
    ): DictionaryConfig =
        currentConfig().copyForType(DictionaryType.Term) { entries ->
            entries.map { entry ->
                if (entry.fileName == fileName) entry.copy(category = category) else entry
            }
        }

    fun configWithImportedDictionaryReplacing(
        type: DictionaryType,
        replacementFileName: String,
        enabled: Boolean,
        order: Int,
        category: DictionaryCategory,
    ): DictionaryConfig =
        currentConfig().copyForType(type) {
            val dictionaries = loadDictionaries(type)
            val replacement = dictionaries.firstOrNull { dictionary ->
                dictionary.path.name == replacementFileName
            } ?: return@copyForType it
            val ordered = dictionaries
                .filterNot { dictionary -> dictionary.path.name == replacementFileName }
                .toMutableList()
            ordered.add(
                order.coerceIn(0, ordered.size),
                replacement.copy(isEnabled = enabled, category = category),
            )
            ordered.mapIndexed { index, dictionary ->
                DictionaryConfig.DictionaryEntry(
                    fileName = dictionary.path.name,
                    isEnabled = if (dictionary.path.name == replacementFileName) enabled else dictionary.isEnabled,
                    order = index,
                    category = dictionary.category,
                )
            }
        }

    fun saveConfigsWithUpdatedDictionaryReplacement(
        type: DictionaryType,
        oldFileName: String,
        replacementFileName: String,
        enabled: Boolean,
        order: Int,
        category: DictionaryCategory,
    ) {
        val profiles = profileRepository?.state?.value?.profiles
        if (profiles == null) {
            saveConfig(configWithImportedDictionaryReplacing(type, replacementFileName, enabled, order, category))
            return
        }

        profiles.forEach { profile ->
            val file = configFile(profile.id)
            val current = loadConfig(file)
            val updated = current.withUpdatedDictionaryReplacement(type, oldFileName, replacementFileName)
            if (updated != current) {
                saveConfig(updated, file)
            }
        }
    }

    fun saveConfigFromStorage() {
        val profiles = profileRepository?.state?.value?.profiles
        if (profiles == null) {
            saveConfig(currentConfig())
            return
        }

        val activeProfileId = profileRepository.currentEffectiveProfileId
        profiles.forEach { profile ->
            val file = configFile(profile.id)
            val config = currentConfig(
                configFile = file,
                unconfiguredDictionariesEnabled = profile.id == activeProfileId,
            )
            saveConfig(config, file)
        }
    }

    fun saveConfig(config: DictionaryConfig) {
        saveConfig(config, configFile)
    }

    private fun saveConfig(config: DictionaryConfig, file: File) {
        dictionariesDir.mkdirs()
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(config))
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

    private fun currentConfig(
        configFile: File,
        unconfiguredDictionariesEnabled: Boolean,
    ): DictionaryConfig = DictionaryConfig(
        termDictionaries = configEntries(
            type = DictionaryType.Term,
            configFile = configFile,
            unconfiguredDictionariesEnabled = unconfiguredDictionariesEnabled,
        ),
        frequencyDictionaries = configEntries(
            type = DictionaryType.Frequency,
            configFile = configFile,
            unconfiguredDictionariesEnabled = unconfiguredDictionariesEnabled,
        ),
        pitchDictionaries = configEntries(
            type = DictionaryType.Pitch,
            configFile = configFile,
            unconfiguredDictionariesEnabled = unconfiguredDictionariesEnabled,
        ),
        kanjiDictionaries = configEntries(
            type = DictionaryType.Kanji,
            configFile = configFile,
            unconfiguredDictionariesEnabled = unconfiguredDictionariesEnabled,
        ),
    )

    private fun configEntries(
        type: DictionaryType,
        configFile: File,
        unconfiguredDictionariesEnabled: Boolean,
    ): List<DictionaryConfig.DictionaryEntry> =
        loadDictionaries(
            type = type,
            configFile = configFile,
            unconfiguredDictionariesEnabled = unconfiguredDictionariesEnabled,
        ).mapIndexed { index, dictionary ->
            DictionaryConfig.DictionaryEntry(
                fileName = dictionary.path.name,
                isEnabled = dictionary.isEnabled,
                order = index,
                category = dictionary.category,
            )
        }

    private fun loadDictionaries(
        type: DictionaryType,
        configFile: File,
        unconfiguredDictionariesEnabled: Boolean,
    ): List<DictionaryInfo> {
        val stored = storedDictionaries(type)
        val entries = loadConfig(configFile).entriesForType(type)
        return DictionaryManager.collectDictionaries(
            storedDicts = stored,
            configDicts = entries,
            unconfiguredDictionariesEnabled = unconfiguredDictionariesEnabled,
        )
    }

    private fun configFile(profileId: String? = null): File =
        if (profileRepository != null) {
            if (profileId == null) {
                profileRepository.dictionaryConfigFile()
            } else {
                profileRepository.dictionaryConfigFile(profileId)
            }
        } else {
            File(dictionariesDir, "config.json")
        }

    private fun loadConfig(file: File = configFile): DictionaryConfig =
        runCatching {
            if (!file.exists()) return EmptyDictionaryConfig
            json.decodeFromString<DictionaryConfig>(file.readText())
        }.getOrDefault(EmptyDictionaryConfig)

    private companion object {
        fun defaultJson(): Json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }
    }
}

private val EmptyDictionaryConfig = DictionaryConfig(
    termDictionaries = emptyList(),
    frequencyDictionaries = emptyList(),
    pitchDictionaries = emptyList(),
    kanjiDictionaries = emptyList(),
)

private fun DictionaryConfig.entriesForType(type: DictionaryType): List<DictionaryConfig.DictionaryEntry> =
    when (type) {
        DictionaryType.Term -> termDictionaries
        DictionaryType.Frequency -> frequencyDictionaries
        DictionaryType.Pitch -> pitchDictionaries
        DictionaryType.Kanji -> kanjiDictionaries
    }

private fun DictionaryConfig.copyForType(
    type: DictionaryType,
    transform: (List<DictionaryConfig.DictionaryEntry>) -> List<DictionaryConfig.DictionaryEntry>,
): DictionaryConfig = when (type) {
    DictionaryType.Term -> copy(termDictionaries = transform(termDictionaries))
    DictionaryType.Frequency -> copy(frequencyDictionaries = transform(frequencyDictionaries))
    DictionaryType.Pitch -> copy(pitchDictionaries = transform(pitchDictionaries))
    DictionaryType.Kanji -> copy(kanjiDictionaries = transform(kanjiDictionaries))
}

private fun DictionaryConfig.withUpdatedDictionaryReplacement(
    type: DictionaryType,
    oldFileName: String,
    replacementFileName: String,
): DictionaryConfig = copyForType(type) { entries ->
    if (entries.none { it.fileName == oldFileName }) return@copyForType entries
    var replaced = false
    entries.mapNotNull { entry ->
        when {
            entry.fileName == oldFileName && !replaced -> {
                replaced = true
                entry.copy(fileName = replacementFileName)
            }
            entry.fileName == oldFileName -> null
            entry.fileName == replacementFileName -> null
            else -> entry
        }
    }
}
