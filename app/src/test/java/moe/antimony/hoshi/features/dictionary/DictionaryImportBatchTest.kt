package moe.antimony.hoshi.features.dictionary

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.R
import moe.antimony.hoshi.dictionary.DictionaryImportException
import moe.antimony.hoshi.dictionary.DictionaryImportFailureKind
import moe.antimony.hoshi.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryImportBatchTest {
    @Test
    fun knownNativeDiagnosticsUseLocalizedReasonsWithoutLosingDetails() {
        val error = DictionaryImportException.fromNative("failed to parse index.json")
        assertEquals("failed to parse index.json", error.detail)
        assertEquals(UiText.Resource(R.string.dictionary_import_index_error), dictionaryImportErrorText(error))
        assertEquals(UiText.Resource(R.string.dictionary_import_archive_error), dictionaryImportErrorText(DictionaryImportException.fromNative("failed to open zip")))
    }

    @Test
    fun failuresKeepReasonsAndLaterFilesContinue() = runBlocking {
        val items = listOf("坏𠮟.zip", "Good.zip", "Empty.zip").map { DictionaryImportItem(it) }
        val visited = mutableListOf<DictionaryImportItem>()
        val result = runDictionaryImportBatch(items, {}) { item ->
            visited += item
            when (item) {
                items[0] -> throw DictionaryImportException(DictionaryImportFailureKind.Native, "index.json: 無効𠮟")
                items[2] -> throw DictionaryImportException(DictionaryImportFailureKind.Native, "  ")
            }
        }
        assertEquals(items, visited)
        assertEquals(listOf(items[1]), result.imported)
        assertEquals(listOf(items[0], items[2]), result.failed.map { it.item })
        assertEquals(UiText.Resource(R.string.dictionary_import_native_error_format, "index.json: 無効𠮟"), result.failed[0].reason)
        assertEquals(UiText.Resource(R.string.dictionary_import_failed), result.failed[1].reason)
    }

    @Test
    fun allFailedFilesKeepTheirOwnReason() = runBlocking {
        val items = listOf("Index.zip", "Access.zip").map { DictionaryImportItem(it) }
        val result = runDictionaryImportBatch(items, {}) {
            if (it == items[0]) throw DictionaryImportException.fromNative("could not find index.json")
            throw SecurityException("denied")
        }
        assertTrue(result.imported.isEmpty())
        assertEquals(items, result.failed.map { it.item })
        assertEquals(listOf(UiText.Resource(R.string.dictionary_import_index_error), UiText.Resource(R.string.dictionary_import_file_access_error)), result.failed.map { it.reason })
    }

    @Test
    fun cancellationEscapesWithoutImportingLaterItems() = runBlocking {
        val visited = mutableListOf<DictionaryImportItem>()
        val items = listOf("One.zip", "Two.zip").map { DictionaryImportItem(it) }
        val failure = runCatching {
            runDictionaryImportBatch(items, {}) { item ->
                visited += item
                throw CancellationException("cancel")
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(listOf(items[0]), visited)
    }

    @Test
    fun knownFailuresHaveLocalizedReasonsAndUnexpectedExceptionsDoNotLeak() {
        assertEquals(UiText.Resource(R.string.dictionary_import_file_access_error), dictionaryImportErrorText(SecurityException("secret")))
        assertEquals(UiText.Resource(R.string.dictionary_import_archive_error), dictionaryImportErrorText(java.util.zip.ZipException("bad zip")))
        assertEquals(UiText.Resource(R.string.dictionary_import_index_error), dictionaryImportErrorText(DictionaryImportException(DictionaryImportFailureKind.InvalidIndex)))
        assertEquals(UiText.Resource(R.string.dictionary_import_failed), dictionaryImportErrorText(IllegalStateException("secret")))
    }
}
