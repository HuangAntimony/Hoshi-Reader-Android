package moe.antimony.hoshi.dictionary

import kotlinx.serialization.Serializable
import moe.antimony.hoshi.content.ContentLanguageProfile
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
    val indexUrl: String = "",
    val downloadUrl: String = "",
    val description: String = "",
    val languageId: String = ContentLanguageProfile.JapaneseLanguageId,
)

val RecommendedDictionaries: List<RecommendedDictionary> = listOf(
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
    RecommendedDictionary(
        id = "wty-en-en",
        name = "Wiktionary English-English",
        type = DictionaryType.Term,
        indexUrl = "https://huggingface.co/datasets/daxida/wty-release/resolve/main/latest/index/wty-en-en-index.json?download=true",
        description = "Term",
        languageId = ContentLanguageProfile.EnglishLanguageId,
    ),
    RecommendedDictionary(
        id = "wty-en-en-ipa",
        name = "Wiktionary English-English IPA",
        type = DictionaryType.Pitch,
        indexUrl = "https://huggingface.co/datasets/daxida/wty-release/resolve/main/latest/index/wty-en-en-ipa-index.json?download=true",
        description = "IPA",
        languageId = ContentLanguageProfile.EnglishLanguageId,
    ),
    RecommendedDictionary(
        id = "wty-simple-simple",
        name = "Wiktionary Simple English-Simple English",
        type = DictionaryType.Term,
        indexUrl = "https://huggingface.co/datasets/daxida/wty-release/resolve/main/latest/index/wty-simple-simple-index.json?download=true",
        description = "Term",
        languageId = ContentLanguageProfile.EnglishLanguageId,
    ),
    RecommendedDictionary(
        id = "wty-en-ja",
        name = "Wiktionary English-Japanese",
        type = DictionaryType.Term,
        indexUrl = "https://huggingface.co/datasets/daxida/wty-release/resolve/main/latest/index/wty-en-ja-index.json?download=true",
        description = "Term",
        languageId = ContentLanguageProfile.EnglishLanguageId,
    ),
    RecommendedDictionary(
        id = "wty-en-ja-gloss",
        name = "Wiktionary English-Japanese Glossary",
        type = DictionaryType.Term,
        indexUrl = "https://huggingface.co/datasets/daxida/wty-release/resolve/main/latest/index/wty-en-ja-gloss-index.json?download=true",
        description = "Glossary",
        languageId = ContentLanguageProfile.EnglishLanguageId,
    ),
    RecommendedDictionary(
        id = "leipzig-english-web-rank",
        name = "Leipzig English Web",
        type = DictionaryType.Frequency,
        downloadUrl = "https://github.com/StefanVukovic99/leipzig-to-yomitan/releases/latest/download/Leipzig.English.Web.Rank.zip",
        description = "Frequency",
        languageId = ContentLanguageProfile.EnglishLanguageId,
    ),
    RecommendedDictionary(
        id = "leipzig-english-wikipedia-rank",
        name = "Leipzig English Wikipedia",
        type = DictionaryType.Frequency,
        downloadUrl = "https://github.com/StefanVukovic99/leipzig-to-yomitan/releases/latest/download/Leipzig.English.Wikipedia.Rank.zip",
        description = "Frequency",
        languageId = ContentLanguageProfile.EnglishLanguageId,
    ),
)

fun recommendedDictionariesForLanguage(languageId: String): List<RecommendedDictionary> =
    RecommendedDictionaries.filter { it.languageId == languageId }

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
