package moe.antimony.hoshi.features.dictionary

import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.ZipException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import moe.antimony.hoshi.R
import moe.antimony.hoshi.dictionary.DictionaryImportException
import moe.antimony.hoshi.dictionary.DictionaryImportFailureKind
import moe.antimony.hoshi.importing.UnsupportedImportFileTypeException
import moe.antimony.hoshi.ui.UiText

internal data class DictionaryImportFailure(val item: DictionaryImportItem, val reason: UiText)

internal suspend fun runDictionaryImportBatch(
    items: List<DictionaryImportItem>,
    onProgress: (DictionaryImportItem) -> Unit,
    importItem: suspend (DictionaryImportItem) -> Unit,
): DictionaryImportBatchResult {
    val imported = mutableListOf<DictionaryImportItem>()
    val failed = mutableListOf<DictionaryImportFailure>()
    items.forEach { item ->
        currentCoroutineContext().ensureActive()
        onProgress(item)
        try {
            importItem(item)
            imported += item
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failed += DictionaryImportFailure(item, dictionaryImportErrorText(error))
        }
    }
    return DictionaryImportBatchResult(imported, failed)
}

internal fun dictionaryImportErrorText(error: Throwable): UiText = when (error) {
    is DictionaryImportException -> when (error.kind) {
        DictionaryImportFailureKind.Native -> error.detail?.takeIf { it.isNotBlank() }?.let {
            UiText.Resource(R.string.dictionary_import_native_error_format, it)
        } ?: UiText.Resource(R.string.dictionary_import_failed)
        DictionaryImportFailureKind.FileAccess -> UiText.Resource(R.string.dictionary_import_file_access_error)
        DictionaryImportFailureKind.InvalidArchive -> UiText.Resource(R.string.dictionary_import_archive_error)
        DictionaryImportFailureKind.WriteFiles -> UiText.Resource(R.string.dictionary_import_io_error)
        DictionaryImportFailureKind.InvalidIndex -> UiText.Resource(R.string.dictionary_import_index_error)
        DictionaryImportFailureKind.UnsupportedContents -> UiText.Resource(R.string.dictionary_import_contents_error)
    }
    is UnsupportedImportFileTypeException -> UiText.Resource(error.messageRes)
    is FileNotFoundException, is SecurityException -> UiText.Resource(R.string.dictionary_import_file_access_error)
    is ZipException -> UiText.Resource(R.string.dictionary_import_archive_error)
    is SerializationException -> UiText.Resource(R.string.dictionary_import_index_error)
    is IOException -> UiText.Resource(R.string.dictionary_import_io_error)
    else -> UiText.Resource(R.string.dictionary_import_failed)
}
