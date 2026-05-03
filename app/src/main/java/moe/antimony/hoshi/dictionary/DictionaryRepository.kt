package moe.antimony.hoshi.dictionary

import android.content.ContentResolver
import android.net.Uri
import java.io.File

internal class DictionaryRepository(
    filesDir: File,
    cacheDir: File,
    private val storage: DictionaryStorageDataSource = DictionaryStorageDataSource(filesDir),
    private val importDataSource: DictionaryImportDataSource = DictionaryImportDataSource(cacheDir),
    private val lookupQueryService: DictionaryLookupQueryService = DictionaryLookupQueryService(),
) {
    fun loadDictionaries(type: DictionaryType): List<DictionaryInfo> =
        storage.loadDictionaries(type)

    fun importDictionary(contentResolver: ContentResolver, uri: Uri, type: DictionaryType) {
        importDataSource.importDictionary(contentResolver, uri, storage.typeDirectory(type))
        storage.saveConfigFromStorage()
        rebuildLookupQuery()
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

    fun rebuildLookupQuery() {
        lookupQueryService.rebuild(
            termDictionaries = storage.enabledDictionaryPaths(DictionaryType.Term),
            frequencyDictionaries = storage.enabledDictionaryPaths(DictionaryType.Frequency),
            pitchDictionaries = storage.enabledDictionaryPaths(DictionaryType.Pitch),
        )
    }
}
