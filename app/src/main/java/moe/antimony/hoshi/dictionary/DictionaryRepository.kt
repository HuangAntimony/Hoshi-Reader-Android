package moe.antimony.hoshi.dictionary

import android.content.ContentResolver
import android.net.Uri
import de.manhhao.hoshi.LookupResult
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import moe.antimony.hoshi.di.FilesDir

@Singleton
internal class DictionaryRepository private constructor(
    private val storage: DictionaryStorageDataSource,
    private val importDataSource: DictionaryImportDataSource,
    private val lookupQueryService: DictionaryLookupQueryService,
    private val remoteDataSource: DictionaryRemoteDataSource,
) {
    @Inject
    constructor(@FilesDir filesDir: File) : this(
        storage = DictionaryStorageDataSource(filesDir),
        importDataSource = DictionaryImportDataSource(),
        lookupQueryService = DictionaryLookupQueryService(),
        remoteDataSource = UrlDictionaryRemoteDataSource(),
    )

    internal constructor(
        filesDir: File,
        storage: DictionaryStorageDataSource,
        importDataSource: DictionaryImportDataSource,
        lookupQueryService: DictionaryLookupQueryService,
        remoteDataSource: DictionaryRemoteDataSource = UrlDictionaryRemoteDataSource(),
    ) : this(
        storage = storage,
        importDataSource = importDataSource,
        lookupQueryService = lookupQueryService,
        remoteDataSource = remoteDataSource,
    )

    private val lookupQueryLock = Any()

    @Volatile
    private var lookupQueryReady = false

    fun loadDictionaries(type: DictionaryType): List<DictionaryInfo> =
        storage.loadDictionaries(type)

    fun updatableDictionaries(): List<DictionaryUpdateCandidate> =
        storage.updatableDictionaries()

    fun importDictionary(contentResolver: ContentResolver, uri: Uri, lowRamImport: Boolean = false) {
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
    }

    fun importDictionary(input: InputStream, lowRamImport: Boolean = false) {
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
    }

    fun setDictionaryEnabled(type: DictionaryType, fileName: String, enabled: Boolean) {
        val config = storage.configWithDictionaryEnabled(type, fileName, enabled)
        storage.saveConfig(config)
        rebuildLookupQuery()
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
                storage.saveConfig(
                    storage.configWithImportedDictionaryReplacing(
                        type = candidate.type,
                        replacementFileName = replacement.fileName,
                        enabled = installed.isEnabled,
                        order = installed.order,
                    ),
                )
                if (replacement.index.title != installedIndex.title) {
                    renames += DictionaryRename(
                        oldTitle = installedIndex.title,
                        newTitle = replacement.index.title,
                    )
                }
                successfulCount += 1
                updatedCount += 1
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failures += DictionaryUpdateFailure(
                    title = installedIndex.title,
                    message = error.localizedMessage ?: error::class.java.simpleName,
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
        onProgress: (DictionaryUpdateProgress) -> Unit = {},
    ) {
        var importedCount = 0
        dictionaries.forEach { dictionary ->
            onProgress(DictionaryUpdateProgress(DictionaryUpdateStage.Fetching, dictionary.name))
            val remoteIndex = remoteDataSource.fetchIndex(dictionary.indexUrl)
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
                storage.saveConfigFromStorage()
            }
        }
        if (importedCount > 0) {
            rebuildLookupQuery()
        }
    }

    fun rebuildLookupQuery() {
        synchronized(lookupQueryLock) {
            rebuildLookupQueryLocked()
        }
    }

    fun ensureLookupQueryReady() {
        if (lookupQueryReady) return
        synchronized(lookupQueryLock) {
            if (!lookupQueryReady) {
                rebuildLookupQueryLocked()
            }
        }
    }

    fun lookup(text: String, maxResults: Int = 16, scanLength: Int = 16): List<LookupResult> {
        ensureLookupQueryReady()
        return LookupEngine.lookup(text, maxResults, scanLength)
    }

    fun dictionaryStyles(): Map<String, String> {
        ensureLookupQueryReady()
        return LookupEngine.getStyles().associate { it.dictName to it.styles }
    }

    private fun rebuildLookupQueryLocked() {
        lookupQueryService.rebuild(
            termDictionaries = storage.enabledDictionaryPaths(DictionaryType.Term),
            frequencyDictionaries = storage.enabledDictionaryPaths(DictionaryType.Frequency),
            pitchDictionaries = storage.enabledDictionaryPaths(DictionaryType.Pitch),
        )
        lookupQueryReady = true
    }

    private fun typeDirectories(): Map<DictionaryType, File> =
        DictionaryType.entries.associateWith { type -> storage.typeDirectory(type) }
}
