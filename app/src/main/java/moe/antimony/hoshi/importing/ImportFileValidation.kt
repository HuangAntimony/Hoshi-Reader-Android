package moe.antimony.hoshi.importing

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns

fun ContentResolver.validateImportFile(uri: Uri, type: ImportFileType): String {
    val name = importDisplayName(uri)
    val candidate = name.ifBlank { uri.lastPathSegment.orEmpty() }
    if (!type.matchesDisplayName(candidate)) {
        throw type.unsupportedFileError(name.takeIf { it.isNotBlank() })
    }
    return name
}

fun ContentResolver.importDisplayName(uri: Uri): String =
    runCatching {
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()
