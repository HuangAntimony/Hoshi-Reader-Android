package moe.antimony.hoshi.features.dictionary

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import moe.antimony.hoshi.R

enum class DictionaryCollapseMode(val rawValue: String, @get:StringRes val labelRes: Int) {
    ExpandAll("Expand All", R.string.dictionary_collapse_mode_expand_all),
    CollapseAll("Collapse All", R.string.dictionary_collapse_mode_collapse_all),
    Custom("Custom", R.string.dictionary_collapse_mode_custom),
    ;

    companion object {
        fun fromRawValue(value: String?): DictionaryCollapseMode? =
            entries.firstOrNull { it.rawValue == value }
    }
}

enum class DictionaryLanguage(
    val code: String,
    val keyboardLocaleTag: String,
    @get:StringRes val labelRes: Int,
) {
    Arabic("ar", "ar", R.string.dictionary_language_arabic),
    German("de", "de-DE", R.string.dictionary_language_german),
    ModernGreek("el", "el-GR", R.string.dictionary_language_modern_greek),
    English("en", "en-US", R.string.dictionary_language_english),
    Esperanto("eo", "eo", R.string.dictionary_language_esperanto),
    Spanish("es", "es-ES", R.string.dictionary_language_spanish),
    Basque("eu", "eu-ES", R.string.dictionary_language_basque),
    French("fr", "fr-FR", R.string.dictionary_language_french),
    Irish("ga", "ga-IE", R.string.dictionary_language_irish),
    AncientGreek("grc", "grc", R.string.dictionary_language_ancient_greek),
    Japanese("ja", "ja-JP", R.string.dictionary_language_japanese),
    Georgian("kat", "ka-GE", R.string.dictionary_language_georgian),
    Korean("ko", "ko-KR", R.string.dictionary_language_korean),
    Latin("la", "la", R.string.dictionary_language_latin),
    OldIrish("sga", "sga", R.string.dictionary_language_old_irish),
    Albanian("sq", "sq-AL", R.string.dictionary_language_albanian),
    Tagalog("tl", "tl-PH", R.string.dictionary_language_tagalog),
    Yiddish("yi", "yi", R.string.dictionary_language_yiddish),
    ;

    companion object {
        val Default = Japanese

        fun fromCode(code: String?): DictionaryLanguage? =
            entries.firstOrNull { it.code == code } ?: when (code) {
                "ka" -> Georgian
                "arz" -> Arabic
                else -> null
            }
    }
}

data class DictionarySettings(
    val lookupLanguage: DictionaryLanguage = DictionaryLanguage.Default,
    val dictionaryTabDefault: Boolean = false,
    val scanNonJapaneseText: Boolean = true,
    val maxResults: Int = 16,
    val scanLength: Int = 16,
    val collapseMode: DictionaryCollapseMode = DictionaryCollapseMode.ExpandAll,
    val expandFirstDictionary: Boolean = false,
    val collapsedDictionaries: Set<String> = emptySet(),
    val compactGlossaries: Boolean = true,
    val showExpressionTags: Boolean = false,
    val harmonicFrequency: Boolean = false,
    val deduplicatePitchAccents: Boolean = false,
    val compactPitchAccents: Boolean = true,
    val lowRamDictionaryImport: Boolean = false,
    val customCSS: String = "",
) {
    fun normalized(): DictionarySettings = copy(
        maxResults = maxResults.coerceIn(MIN_MAX_RESULTS, MAX_MAX_RESULTS),
        scanLength = scanLength.coerceIn(MIN_SCAN_LENGTH, MAX_SCAN_LENGTH),
    )

    companion object {
        const val MIN_MAX_RESULTS = 1
        const val MAX_MAX_RESULTS = 50
        const val MIN_SCAN_LENGTH = 1
        const val MAX_SCAN_LENGTH = 64
    }
}

interface DictionarySettingsLegacySource {
    fun load(): DictionarySettings
}

class DictionarySettingsStore(context: Context) : DictionarySettingsLegacySource {
    private val preferences = context.getSharedPreferences("dictionary-settings", Context.MODE_PRIVATE)

    override fun load(): DictionarySettings = DictionarySettings(
        lookupLanguage = DictionaryLanguage.fromCode(preferences.getString(KEY_LOOKUP_LANGUAGE, null))
            ?: DictionaryLanguage.Default,
        dictionaryTabDefault = preferences.getBoolean(KEY_DICTIONARY_TAB_DEFAULT, false),
        scanNonJapaneseText = preferences.getBoolean(KEY_SCAN_NON_JAPANESE_TEXT, true),
        maxResults = preferences.getInt(KEY_MAX_RESULTS, 16),
        scanLength = preferences.getInt(KEY_SCAN_LENGTH, 16),
        collapseMode = DictionaryCollapseMode.fromRawValue(preferences.getString(KEY_COLLAPSE_MODE, null))
            ?: if (preferences.getBoolean(KEY_COLLAPSE_DICTIONARIES, false)) {
                DictionaryCollapseMode.CollapseAll
            } else {
                DictionaryCollapseMode.ExpandAll
            },
        expandFirstDictionary = if (preferences.contains(KEY_EXPAND_FIRST_DICTIONARY)) {
            preferences.getBoolean(KEY_EXPAND_FIRST_DICTIONARY, false)
        } else {
            preferences.getBoolean(KEY_COLLAPSE_DICTIONARIES, false)
        },
        collapsedDictionaries = preferences.getStringSet(KEY_COLLAPSED_DICTIONARIES, emptySet()).orEmpty(),
        compactGlossaries = preferences.getBoolean(KEY_COMPACT_GLOSSARIES, true),
        showExpressionTags = preferences.getBoolean(KEY_SHOW_EXPRESSION_TAGS, false),
        harmonicFrequency = preferences.getBoolean(KEY_HARMONIC_FREQUENCY, false),
        deduplicatePitchAccents = preferences.getBoolean(KEY_DEDUPLICATE_PITCH_ACCENTS, false),
        compactPitchAccents = preferences.getBoolean(KEY_COMPACT_PITCH_ACCENTS, true),
        lowRamDictionaryImport = preferences.getBoolean(KEY_LOW_RAM_DICTIONARY_IMPORT, false),
        customCSS = preferences.getString(KEY_CUSTOM_CSS, "").orEmpty(),
    ).normalized()

    fun save(settings: DictionarySettings) {
        val normalized = settings.normalized()
        preferences.edit()
            .putBoolean(KEY_DICTIONARY_TAB_DEFAULT, normalized.dictionaryTabDefault)
            .putBoolean(KEY_SCAN_NON_JAPANESE_TEXT, normalized.scanNonJapaneseText)
            .putInt(KEY_MAX_RESULTS, normalized.maxResults)
            .putInt(KEY_SCAN_LENGTH, normalized.scanLength)
            .putString(KEY_COLLAPSE_MODE, normalized.collapseMode.rawValue)
            .putBoolean(KEY_EXPAND_FIRST_DICTIONARY, normalized.expandFirstDictionary)
            .putStringSet(KEY_COLLAPSED_DICTIONARIES, normalized.collapsedDictionaries)
            .putBoolean(KEY_COMPACT_GLOSSARIES, normalized.compactGlossaries)
            .putBoolean(KEY_SHOW_EXPRESSION_TAGS, normalized.showExpressionTags)
            .putBoolean(KEY_HARMONIC_FREQUENCY, normalized.harmonicFrequency)
            .putBoolean(KEY_DEDUPLICATE_PITCH_ACCENTS, normalized.deduplicatePitchAccents)
            .putBoolean(KEY_COMPACT_PITCH_ACCENTS, normalized.compactPitchAccents)
            .putBoolean(KEY_LOW_RAM_DICTIONARY_IMPORT, normalized.lowRamDictionaryImport)
            .putString(KEY_CUSTOM_CSS, normalized.customCSS)
            .putString(KEY_LOOKUP_LANGUAGE, normalized.lookupLanguage.code)
            .apply()
    }

    private companion object {
        const val KEY_LOOKUP_LANGUAGE = "lookupLanguage"
        const val KEY_DICTIONARY_TAB_DEFAULT = "dictionaryTabDefault"
        const val KEY_SCAN_NON_JAPANESE_TEXT = "scanNonJapaneseText"
        const val KEY_MAX_RESULTS = "maxResults"
        const val KEY_SCAN_LENGTH = "scanLength"
        const val KEY_COLLAPSE_DICTIONARIES = "collapseDictionaries"
        const val KEY_COLLAPSE_MODE = "collapseMode"
        const val KEY_EXPAND_FIRST_DICTIONARY = "expandFirstDictionary"
        const val KEY_COLLAPSED_DICTIONARIES = "collapsedDictionaries"
        const val KEY_COMPACT_GLOSSARIES = "compactGlossaries"
        const val KEY_SHOW_EXPRESSION_TAGS = "showExpressionTags"
        const val KEY_HARMONIC_FREQUENCY = "harmonicFrequency"
        const val KEY_DEDUPLICATE_PITCH_ACCENTS = "deduplicatePitchAccents"
        const val KEY_COMPACT_PITCH_ACCENTS = "compactPitchAccents"
        const val KEY_LOW_RAM_DICTIONARY_IMPORT = "lowRamDictionaryImport"
        const val KEY_CUSTOM_CSS = "customCSS"
    }
}

private val Context.dictionarySettingsDataStore by preferencesDataStore(name = DictionarySettingsRepository.DataStoreName)

fun Context.dictionarySettingsRepository(): DictionarySettingsRepository =
    DictionarySettingsRepository(
        dataStore = dictionarySettingsDataStore,
        legacySource = DictionarySettingsStore(this),
    )

class DictionarySettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val legacySource: DictionarySettingsLegacySource? = null,
) {
    val settings: Flow<DictionarySettings> = dataStore.data
        .onStart { migrateLegacySettingsIfNeeded() }
        .map { preferences -> preferences.toDictionarySettings() }

    suspend fun update(transform: (DictionarySettings) -> DictionarySettings) {
        migrateLegacySettingsIfNeeded()
        dataStore.edit { preferences ->
            val current = preferences.toDictionarySettings()
            preferences.writeDictionarySettings(transform(current).normalized())
            preferences[KEY_MIGRATED_FROM_SHARED_PREFERENCES] = true
        }
    }

    private suspend fun migrateLegacySettingsIfNeeded() {
        dataStore.edit { preferences ->
            if (preferences[KEY_MIGRATED_FROM_SHARED_PREFERENCES] == true) return@edit
            preferences.writeDictionarySettings(legacySource?.load()?.normalized() ?: DictionarySettings())
            preferences[KEY_MIGRATED_FROM_SHARED_PREFERENCES] = true
        }
    }

    private fun Preferences.toDictionarySettings(): DictionarySettings {
        val legacyCollapseDictionaries = this[KEY_COLLAPSE_DICTIONARIES]
        return DictionarySettings(
            lookupLanguage = DictionaryLanguage.fromCode(this[KEY_LOOKUP_LANGUAGE]) ?: DictionaryLanguage.Default,
            dictionaryTabDefault = this[KEY_DICTIONARY_TAB_DEFAULT] ?: false,
            scanNonJapaneseText = this[KEY_SCAN_NON_JAPANESE_TEXT] ?: true,
            maxResults = this[KEY_MAX_RESULTS] ?: 16,
            scanLength = this[KEY_SCAN_LENGTH] ?: 16,
            collapseMode = DictionaryCollapseMode.fromRawValue(this[KEY_COLLAPSE_MODE])
                ?: if (legacyCollapseDictionaries == true) {
                    DictionaryCollapseMode.CollapseAll
                } else {
                    DictionaryCollapseMode.ExpandAll
                },
            expandFirstDictionary = this[KEY_EXPAND_FIRST_DICTIONARY] ?: (legacyCollapseDictionaries == true),
            collapsedDictionaries = this[KEY_COLLAPSED_DICTIONARIES].orEmpty(),
            compactGlossaries = this[KEY_COMPACT_GLOSSARIES] ?: true,
            showExpressionTags = this[KEY_SHOW_EXPRESSION_TAGS] ?: false,
            harmonicFrequency = this[KEY_HARMONIC_FREQUENCY] ?: false,
            deduplicatePitchAccents = this[KEY_DEDUPLICATE_PITCH_ACCENTS] ?: false,
            compactPitchAccents = this[KEY_COMPACT_PITCH_ACCENTS] ?: true,
            lowRamDictionaryImport = this[KEY_LOW_RAM_DICTIONARY_IMPORT] ?: false,
            customCSS = this[KEY_CUSTOM_CSS].orEmpty(),
        ).normalized()
    }

    private fun MutablePreferences.writeDictionarySettings(settings: DictionarySettings) {
        val normalized = settings.normalized()
        this[KEY_DICTIONARY_TAB_DEFAULT] = normalized.dictionaryTabDefault
        this[KEY_SCAN_NON_JAPANESE_TEXT] = normalized.scanNonJapaneseText
        this[KEY_MAX_RESULTS] = normalized.maxResults
        this[KEY_SCAN_LENGTH] = normalized.scanLength
        this[KEY_COLLAPSE_MODE] = normalized.collapseMode.rawValue
        this[KEY_EXPAND_FIRST_DICTIONARY] = normalized.expandFirstDictionary
        this[KEY_COLLAPSED_DICTIONARIES] = normalized.collapsedDictionaries
        this[KEY_COMPACT_GLOSSARIES] = normalized.compactGlossaries
        this[KEY_SHOW_EXPRESSION_TAGS] = normalized.showExpressionTags
        this[KEY_HARMONIC_FREQUENCY] = normalized.harmonicFrequency
        this[KEY_DEDUPLICATE_PITCH_ACCENTS] = normalized.deduplicatePitchAccents
        this[KEY_COMPACT_PITCH_ACCENTS] = normalized.compactPitchAccents
        this[KEY_LOW_RAM_DICTIONARY_IMPORT] = normalized.lowRamDictionaryImport
        this[KEY_CUSTOM_CSS] = normalized.customCSS
        this[KEY_LOOKUP_LANGUAGE] = normalized.lookupLanguage.code
    }

    companion object {
        const val DataStoreName = "dictionary-settings"

        private val KEY_MIGRATED_FROM_SHARED_PREFERENCES =
            booleanPreferencesKey("dictionarySettingsMigratedFromSharedPreferences")
        private val KEY_LOOKUP_LANGUAGE = stringPreferencesKey("lookupLanguage")
        private val KEY_DICTIONARY_TAB_DEFAULT = booleanPreferencesKey("dictionaryTabDefault")
        private val KEY_SCAN_NON_JAPANESE_TEXT = booleanPreferencesKey("scanNonJapaneseText")
        private val KEY_MAX_RESULTS = intPreferencesKey("maxResults")
        private val KEY_SCAN_LENGTH = intPreferencesKey("scanLength")
        private val KEY_COLLAPSE_DICTIONARIES = booleanPreferencesKey("collapseDictionaries")
        private val KEY_COLLAPSE_MODE = stringPreferencesKey("collapseMode")
        private val KEY_EXPAND_FIRST_DICTIONARY = booleanPreferencesKey("expandFirstDictionary")
        private val KEY_COLLAPSED_DICTIONARIES = stringSetPreferencesKey("collapsedDictionaries")
        private val KEY_COMPACT_GLOSSARIES = booleanPreferencesKey("compactGlossaries")
        private val KEY_SHOW_EXPRESSION_TAGS = booleanPreferencesKey("showExpressionTags")
        private val KEY_HARMONIC_FREQUENCY = booleanPreferencesKey("harmonicFrequency")
        private val KEY_DEDUPLICATE_PITCH_ACCENTS = booleanPreferencesKey("deduplicatePitchAccents")
        private val KEY_COMPACT_PITCH_ACCENTS = booleanPreferencesKey("compactPitchAccents")
        private val KEY_LOW_RAM_DICTIONARY_IMPORT = booleanPreferencesKey("lowRamDictionaryImport")
        private val KEY_CUSTOM_CSS = stringPreferencesKey("customCSS")
    }
}
