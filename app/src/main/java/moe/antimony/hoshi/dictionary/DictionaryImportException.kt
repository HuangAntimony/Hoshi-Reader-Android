package moe.antimony.hoshi.dictionary

internal enum class DictionaryImportFailureKind { Native, FileAccess, InvalidArchive, InvalidIndex, UnsupportedContents, WriteFiles }

internal class DictionaryImportException(
    val kind: DictionaryImportFailureKind,
    val detail: String? = null,
) : java.io.IOException(detail ?: kind.name) {
    companion object {
        fun fromNative(detail: String): DictionaryImportException = DictionaryImportException(
            kind = when (detail) {
                "failed to open zip" -> DictionaryImportFailureKind.InvalidArchive
                "could not find index.json", "could not read index.json", "failed to parse index.json" ->
                    DictionaryImportFailureKind.InvalidIndex
                "empty dictionary" -> DictionaryImportFailureKind.UnsupportedContents
                "failed to write index.json" -> DictionaryImportFailureKind.WriteFiles
                else -> DictionaryImportFailureKind.Native
            },
            detail = detail,
        )
    }
}
