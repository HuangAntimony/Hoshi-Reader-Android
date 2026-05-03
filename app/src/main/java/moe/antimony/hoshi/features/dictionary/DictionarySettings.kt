package moe.antimony.hoshi.features.dictionary

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

data class DictionarySettings(
    val dictionaryTabDefault: Boolean = false,
    val maxResults: Int = 16,
    val scanLength: Int = 16,
    val collapseDictionaries: Boolean = false,
    val compactGlossaries: Boolean = true,
    val showExpressionTags: Boolean = false,
    val harmonicFrequency: Boolean = false,
    val deduplicatePitchAccents: Boolean = false,
    val compactPitchAccents: Boolean = true,
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
        dictionaryTabDefault = preferences.getBoolean(KEY_DICTIONARY_TAB_DEFAULT, false),
        maxResults = preferences.getInt(KEY_MAX_RESULTS, 16),
        scanLength = preferences.getInt(KEY_SCAN_LENGTH, 16),
        collapseDictionaries = preferences.getBoolean(KEY_COLLAPSE_DICTIONARIES, false),
        compactGlossaries = preferences.getBoolean(KEY_COMPACT_GLOSSARIES, true),
        showExpressionTags = preferences.getBoolean(KEY_SHOW_EXPRESSION_TAGS, false),
        harmonicFrequency = preferences.getBoolean(KEY_HARMONIC_FREQUENCY, false),
        deduplicatePitchAccents = preferences.getBoolean(KEY_DEDUPLICATE_PITCH_ACCENTS, false),
        compactPitchAccents = preferences.getBoolean(KEY_COMPACT_PITCH_ACCENTS, true),
        customCSS = preferences.getString(KEY_CUSTOM_CSS, "").orEmpty(),
    ).normalized()

    fun save(settings: DictionarySettings) {
        val normalized = settings.normalized()
        preferences.edit()
            .putBoolean(KEY_DICTIONARY_TAB_DEFAULT, normalized.dictionaryTabDefault)
            .putInt(KEY_MAX_RESULTS, normalized.maxResults)
            .putInt(KEY_SCAN_LENGTH, normalized.scanLength)
            .putBoolean(KEY_COLLAPSE_DICTIONARIES, normalized.collapseDictionaries)
            .putBoolean(KEY_COMPACT_GLOSSARIES, normalized.compactGlossaries)
            .putBoolean(KEY_SHOW_EXPRESSION_TAGS, normalized.showExpressionTags)
            .putBoolean(KEY_HARMONIC_FREQUENCY, normalized.harmonicFrequency)
            .putBoolean(KEY_DEDUPLICATE_PITCH_ACCENTS, normalized.deduplicatePitchAccents)
            .putBoolean(KEY_COMPACT_PITCH_ACCENTS, normalized.compactPitchAccents)
            .putString(KEY_CUSTOM_CSS, normalized.customCSS)
            .apply()
    }

    private companion object {
        const val KEY_DICTIONARY_TAB_DEFAULT = "dictionaryTabDefault"
        const val KEY_MAX_RESULTS = "maxResults"
        const val KEY_SCAN_LENGTH = "scanLength"
        const val KEY_COLLAPSE_DICTIONARIES = "collapseDictionaries"
        const val KEY_COMPACT_GLOSSARIES = "compactGlossaries"
        const val KEY_SHOW_EXPRESSION_TAGS = "showExpressionTags"
        const val KEY_HARMONIC_FREQUENCY = "harmonicFrequency"
        const val KEY_DEDUPLICATE_PITCH_ACCENTS = "deduplicatePitchAccents"
        const val KEY_COMPACT_PITCH_ACCENTS = "compactPitchAccents"
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

    private fun Preferences.toDictionarySettings(): DictionarySettings =
        DictionarySettings(
            dictionaryTabDefault = this[KEY_DICTIONARY_TAB_DEFAULT] ?: false,
            maxResults = this[KEY_MAX_RESULTS] ?: 16,
            scanLength = this[KEY_SCAN_LENGTH] ?: 16,
            collapseDictionaries = this[KEY_COLLAPSE_DICTIONARIES] ?: false,
            compactGlossaries = this[KEY_COMPACT_GLOSSARIES] ?: true,
            showExpressionTags = this[KEY_SHOW_EXPRESSION_TAGS] ?: false,
            harmonicFrequency = this[KEY_HARMONIC_FREQUENCY] ?: false,
            deduplicatePitchAccents = this[KEY_DEDUPLICATE_PITCH_ACCENTS] ?: false,
            compactPitchAccents = this[KEY_COMPACT_PITCH_ACCENTS] ?: true,
            customCSS = this[KEY_CUSTOM_CSS].orEmpty(),
        ).normalized()

    private fun MutablePreferences.writeDictionarySettings(settings: DictionarySettings) {
        val normalized = settings.normalized()
        this[KEY_DICTIONARY_TAB_DEFAULT] = normalized.dictionaryTabDefault
        this[KEY_MAX_RESULTS] = normalized.maxResults
        this[KEY_SCAN_LENGTH] = normalized.scanLength
        this[KEY_COLLAPSE_DICTIONARIES] = normalized.collapseDictionaries
        this[KEY_COMPACT_GLOSSARIES] = normalized.compactGlossaries
        this[KEY_SHOW_EXPRESSION_TAGS] = normalized.showExpressionTags
        this[KEY_HARMONIC_FREQUENCY] = normalized.harmonicFrequency
        this[KEY_DEDUPLICATE_PITCH_ACCENTS] = normalized.deduplicatePitchAccents
        this[KEY_COMPACT_PITCH_ACCENTS] = normalized.compactPitchAccents
        this[KEY_CUSTOM_CSS] = normalized.customCSS
    }

    companion object {
        const val DataStoreName = "dictionary-settings"

        private val KEY_MIGRATED_FROM_SHARED_PREFERENCES =
            booleanPreferencesKey("dictionarySettingsMigratedFromSharedPreferences")
        private val KEY_DICTIONARY_TAB_DEFAULT = booleanPreferencesKey("dictionaryTabDefault")
        private val KEY_MAX_RESULTS = intPreferencesKey("maxResults")
        private val KEY_SCAN_LENGTH = intPreferencesKey("scanLength")
        private val KEY_COLLAPSE_DICTIONARIES = booleanPreferencesKey("collapseDictionaries")
        private val KEY_COMPACT_GLOSSARIES = booleanPreferencesKey("compactGlossaries")
        private val KEY_SHOW_EXPRESSION_TAGS = booleanPreferencesKey("showExpressionTags")
        private val KEY_HARMONIC_FREQUENCY = booleanPreferencesKey("harmonicFrequency")
        private val KEY_DEDUPLICATE_PITCH_ACCENTS = booleanPreferencesKey("deduplicatePitchAccents")
        private val KEY_COMPACT_PITCH_ACCENTS = booleanPreferencesKey("compactPitchAccents")
        private val KEY_CUSTOM_CSS = stringPreferencesKey("customCSS")
    }
}
