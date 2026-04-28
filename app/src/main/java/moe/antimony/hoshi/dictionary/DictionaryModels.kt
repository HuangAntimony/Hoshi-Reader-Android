package moe.antimony.hoshi.dictionary

import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

enum class DictionaryType(val directoryName: String) {
    Term("Term"),
    Frequency("Frequency"),
    Pitch("Pitch"),
}

data class DictionaryInfo(
    val id: String = UUID.randomUUID().toString(),
    val index: DictionaryIndex,
    val path: File,
    val isEnabled: Boolean = true,
    val order: Int = 0,
)

@Serializable
data class DictionaryConfig(
    val termDictionaries: List<DictionaryEntry>,
    val frequencyDictionaries: List<DictionaryEntry>,
    val pitchDictionaries: List<DictionaryEntry>,
) {
    @Serializable
    data class DictionaryEntry(
        val fileName: String,
        val isEnabled: Boolean,
        val order: Int,
    )
}

@Serializable
data class DictionaryIndex(
    val title: String,
    val format: Int,
    val revision: String,
    val isUpdatable: Boolean,
    val indexUrl: String,
    val downloadUrl: String,
)
