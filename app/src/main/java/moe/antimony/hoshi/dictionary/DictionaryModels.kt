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

data class DictionaryUpdateCandidate(
    val dictionary: DictionaryInfo,
    val type: DictionaryType,
)

enum class DictionaryUpdateStage {
    Fetching,
    Checking,
    Downloading,
    Importing,
}

data class DictionaryUpdateProgress(
    val stage: DictionaryUpdateStage,
    val title: String,
)

data class DictionaryRename(
    val oldTitle: String,
    val newTitle: String,
)

data class DictionaryUpdateFailure(
    val title: String,
    val message: String,
)

data class DictionaryUpdateSummary(
    val checkedCount: Int,
    val successfulCount: Int = 0,
    val updatedCount: Int,
    val renamedDictionaries: List<DictionaryRename> = emptyList(),
    val failures: List<DictionaryUpdateFailure> = emptyList(),
)

data class ImportedDictionary(
    val fileName: String,
    val index: DictionaryIndex,
)

data class RecommendedDictionary(
    val id: String,
    val name: String,
    val type: DictionaryType,
    val indexUrl: String,
    val description: String = "",
)

val RecommendedDictionaries = listOf(
    RecommendedDictionary(
        id = "jmdict",
        name = "JMdict",
        type = DictionaryType.Term,
        indexUrl = "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/JMdict_english_without_proper_names.json",
        description = "Term",
    ),
    RecommendedDictionary(
        id = "jmnedict",
        name = "JMnedict",
        type = DictionaryType.Term,
        indexUrl = "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/JMnedict.json",
        description = "Names",
    ),
    RecommendedDictionary(
        id = "jiten",
        name = "Jiten",
        type = DictionaryType.Frequency,
        indexUrl = "https://api.jiten.moe/api/frequency-list/index",
        description = "Frequency",
    ),
    RecommendedDictionary(
        id = "jitendex",
        name = "Jitendex",
        type = DictionaryType.Term,
        indexUrl = "https://jitendex.org/static/yomitan.json",
        description = "Term",
    ),
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
    val isUpdatable: Boolean = false,
    val indexUrl: String = "",
    val downloadUrl: String = "",
)
