package moe.antimony.hoshi.dictionary

import android.content.ContentResolver
import android.net.Uri
import moe.antimony.hoshi.importing.ImportFileType
import moe.antimony.hoshi.importing.validateImportFile
import java.io.File

internal class DictionaryImportDataSource(
    private val cacheDir: File,
    private val nativeBridge: DictionaryNativeBridge = HoshiDictionaryNativeBridge,
) {
    fun importDictionary(
        contentResolver: ContentResolver,
        uri: Uri,
        typeDirectory: File,
    ) {
        contentResolver.validateImportFile(uri, ImportFileType.DictionaryArchive)
        typeDirectory.mkdirs()
        val tempZip = File.createTempFile("hoshi-dictionary-", ".zip", cacheDir)
        try {
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Unable to open dictionary file." }
                tempZip.outputStream().use { output -> input.copyTo(output) }
            }
            val imported = nativeBridge.importDictionary(tempZip.absolutePath, typeDirectory.absolutePath)
            require(imported) { "Failed to import dictionary." }
        } finally {
            tempZip.delete()
        }
    }
}
