package moe.antimony.hoshi.dictionary

import moe.antimony.hoshi.ui.diagnosticMessage
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.UiText

import android.content.ContentResolver
import android.net.Uri
import de.manhhao.hoshi.LookupOptions
import de.manhhao.hoshi.LookupResult
import de.manhhao.hoshi.KanjiResult
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import moe.antimony.hoshi.profiles.HoshiProfile
import moe.antimony.hoshi.profiles.ProfileRepository

@Singleton
internal class DictionaryRepository @Inject constructor(
    private val storage: DictionaryStorageDataSource,
    private val importDataSource: DictionaryImportDataSource,
    private val lookupQueryService: DictionaryLookupQueryService,
    private val remoteDataSource: DictionaryRemoteDataSource,
    private val profileRepository: ProfileRepository,
) {
    internal constructor(
        @Suppress("UNUSED_PARAMETER")
        filesDir: File,
        storage: DictionaryStorageDataSource,
        importDataSource: DictionaryImportDataSource,
        lookupQueryService: DictionaryLookupQueryService,
        remoteDataSource: DictionaryRemoteDataSource = UrlDictionaryRemoteDataSource(),
        profileRepository: ProfileRepository? = null,
    ) : this(
        storage = storage,
        importDataSource = importDataSource,
        lookupQueryService = lookupQueryService,
        remoteDataSource = remoteDataSource,
        profileRepository = profileRepository ?: ProfileRepository(filesDir),
    )

    private val lookupQueryLock = Any()

    @Volatile
    private var lookupQueryProfile: HoshiProfile? = null

    fun loadDictionaries(type: DictionaryType): List<DictionaryInfo> =
        storage.loadDictionaries(type)

    fun updatableDictionaries(): List<DictionaryUpdateCandidate> =
        storage.updatableDictionaries()

    fun importDictionary(contentResolver: ContentResolver, uri: Uri, lowRamImport: Boolean = false): Int {
        val imported = importDataSource.importDictionaryByDetectedTypes(
            contentResolver = contentResolver,
            uri = uri,
            importRootDirectory = storage.importRootDirectory(),
            typeDirectories = typeDirectories(),
            lowRamImport = lowRamImport,
            shouldSkip = { type, index -> storage.hasDictionaryWithIndex(type, index) },
        ).values.sumOf { it.size }
        if (imported > 0) {
            storage.saveConfigFromStorage()
            rebuildLookupQuery()
        }
        return imported
    }

    fun importDictionary(input: InputStream, lowRamImport: Boolean = false): Int {
        val imported = importDataSource.importDictionaryByDetectedTypes(
            input = input,
            importRootDirectory = storage.importRootDirectory(),
            typeDirectories = typeDirectories(),
            lowRamImport = lowRamImport,
            shouldSkip = { type, index -> storage.hasDictionaryWithIndex(type, index) },
        ).values.sumOf { it.size }
        if (imported > 0) {
            storage.saveConfigFromStorage()
            rebuildLookupQuery()
        }
        return imported
    }

    fun setDictionaryEnabled(type: DictionaryType, fileName: String, enabled: Boolean) {
        val config = storage.configWithDictionaryEnabled(type, fileName, enabled)
        storage.saveConfig(config)
        rebuildLookupQuery()
    }

    fun setDictionaryCategory(fileName: String, category: DictionaryCategory) {
        synchronized(lookupQueryLock) {
            storage.saveConfig(storage.configWithDictionaryCategory(fileName, category))
        }
    }

    fun deleteDictionary(type: DictionaryType, fileName: String) {
        storage.deleteDictionary(type, fileName)
        storage.saveConfig(storage.currentConfig())
        rebuildLookupQuery()
    }

    fun moveDictionary(type: DictionaryType, fromIndex: Int, toIndex: Int) {
        val config = storage.configWithDictionaryMoved(type, fromIndex, toIndex)
        storage.saveConfig(config)
        rebuildLookupQuery()
    }

    fun updateDictionaries(
        lowRamImport: Boolean = false,
        onProgress: (DictionaryUpdateProgress) -> Unit = {},
    ): DictionaryUpdateSummary {
        val candidates = updatableDictionaries()
        var successfulCount = 0
        var updatedCount = 0
        val renames = mutableListOf<DictionaryRename>()
        val failures = mutableListOf<DictionaryUpdateFailure>()
        candidates.forEach { candidate ->
            val installed = candidate.dictionary
            val installedIndex = installed.index
            try {
                onProgress(DictionaryUpdateProgress(DictionaryUpdateStage.Checking, installedIndex.title))
                val remoteIndex = remoteDataSource.fetchIndex(installedIndex.indexUrl)
                if (remoteIndex.revision == installedIndex.revision) {
                    successfulCount += 1
                    return@forEach
                }

                onProgress(DictionaryUpdateProgress(DictionaryUpdateStage.Downloading, remoteIndex.title))
                val imported = remoteDataSource.downloadArchive(remoteIndex.downloadUrl).use { input ->
                    onProgress(DictionaryUpdateProgress(DictionaryUpdateStage.Importing, remoteIndex.title))
                    importDataSource.importDictionaryWithResult(
                        input = input,
                        typeDirectory = storage.typeDirectory(candidate.type),
                        lowRamImport = lowRamImport,
                    )
                }
                val replacement = imported.firstOrNull()
                if (replacement == null) {
                    failures += DictionaryUpdateFailure(installedIndex.title, "Import failed")
                    return@forEach
                }
                if (replacement.fileName != installed.path.name) {
                    storage.deleteDictionary(candidate.type, installed.path.name)
                }
                storage.saveConfigsWithUpdatedDictionaryReplacement(
                    type = candidate.type,
                    oldFileName = installed.path.name,
                    replacementFileName = replacement.fileName,
                    enabled = installed.isEnabled,
                    order = installed.order,
                    category = installed.category,
                )
                if (replacement.index.title != installedIndex.title) {
                    renames += DictionaryRename(
                        oldTitle = installedIndex.title,
                        newTitle = replacement.index.title,
                        type = candidate.type,
                    )
                }
                successfulCount += 1
                updatedCount += 1
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                ErrorDiagnosticEvents.buffer.record(UiText.Resource(R.string.dictionary_update_failed), error)
                failures += DictionaryUpdateFailure(
                    title = installedIndex.title,
                    message = error.diagnosticMessage(),
                )
            }
        }
        if (updatedCount > 0) {
            rebuildLookupQuery()
        }
        return DictionaryUpdateSummary(
            checkedCount = candidates.size,
            successfulCount = successfulCount,
            updatedCount = updatedCount,
            renamedDictionaries = renames,
            failures = failures,
        )
    }

    fun importRecommendedDictionaries(
        dictionaries: List<RecommendedDictionary>,
        lowRamImport: Boolean = false,
        onDictionariesChanged: () -> Unit = {},
        onProgress: (DictionaryUpdateProgress) -> Unit = {},
    ): Int {
        var importedCount = 0
        dictionaries.forEach { dictionary ->
            val remoteIndex = if (dictionary.downloadUrl.isBlank()) {
                onProgress(DictionaryUpdateProgress(DictionaryUpdateStage.Fetching, dictionary.name))
                remoteDataSource.fetchIndex(dictionary.indexUrl)
            } else {
                DictionaryIndex(
                    title = dictionary.name,
                    format = 3,
                    revision = "",
                    downloadUrl = dictionary.downloadUrl,
                )
            }
            onProgress(DictionaryUpdateProgress(DictionaryUpdateStage.Downloading, remoteIndex.title))
            val imported = remoteDataSource.downloadArchive(remoteIndex.downloadUrl).use { input ->
                onProgress(DictionaryUpdateProgress(DictionaryUpdateStage.Importing, remoteIndex.title))
                importDataSource.importDictionaryWithResult(
                    input = input,
                    typeDirectory = storage.typeDirectory(dictionary.type),
                    lowRamImport = lowRamImport,
                    shouldSkip = { index -> storage.hasDictionaryWithIndex(dictionary.type, index) },
                )
            }
            importedCount += imported.size
            if (imported.isNotEmpty()) {
                try {
                    synchronized(lookupQueryLock) {
                        // The files are committed even if a later config write or download fails.
                        lookupQueryProfile = null
                        storage.saveConfigFromStorage()
                    }
                } finally {
                    onDictionariesChanged()
                }
            }
        }
        if (importedCount > 0) {
            rebuildLookupQuery()
        }
        return importedCount
    }

    fun rebuildLookupQuery() {
        synchronized(lookupQueryLock) {
            rebuildLookupQueryLocked(profileRepository.state.value.effectiveProfile)
        }
    }

    fun ensureLookupQueryReady() {
        if (lookupQueryMatches(profileRepository.state.value.effectiveProfile)) return
        synchronized(lookupQueryLock) {
            while (true) {
                val profile = profileRepository.state.value.effectiveProfile
                if (lookupQueryMatches(profile)) return
                rebuildLookupQueryLocked(profile)
            }
        }
    }

    private fun lookupQueryMatches(profile: HoshiProfile): Boolean {
        val loaded = lookupQueryProfile
        return loaded?.id == profile.id && loaded.dictionaryLanguageId == profile.dictionaryLanguageId
    }

    fun lookup(text: String, maxResults: Int = 16, scanLength: Int = 16, options: LookupOptions = LookupOptions()): List<LookupResult> {
        ensureLookupQueryReady()
        return lookupQueryService.lookup(text, maxResults, scanLength, options)
    }

    fun lookupKanji(kanji: String): KanjiResult {
        ensureLookupQueryReady()
        return lookupQueryService.queryKanji(kanji)
    }

    fun dictionaryStyles(): Map<String, String> {
        ensureLookupQueryReady()
        return lookupQueryService.getStyles()
    }

    fun dictionaryMedia(dictionary: String, path: String): ByteArray? {
        ensureLookupQueryReady()
        return lookupQueryService.getMediaFile(dictionary, path)
    }

    private fun rebuildLookupQueryLocked(profile: HoshiProfile) {
        lookupQueryProfile = null
        val paths = storage.enabledDictionaryPaths(profile.id)
        lookupQueryService.rebuild(
            termDictionaries = paths.getValue(DictionaryType.Term),
            frequencyDictionaries = paths.getValue(DictionaryType.Frequency),
            pitchDictionaries = paths.getValue(DictionaryType.Pitch),
            kanjiDictionaries = paths.getValue(DictionaryType.Kanji),
            dictionaryLanguageId = profile.dictionaryLanguageId,
        )
        lookupQueryProfile = profile
    }

    private fun typeDirectories(): Map<DictionaryType, File> =
        DictionaryType.entries.associateWith { type -> storage.typeDirectory(type) }
}
