package moe.antimony.hoshi.features.dictionary

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.R
import moe.antimony.hoshi.profiles.ProfileRepository

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

enum class DictionaryUpdateInterval(
    val rawValue: String,
    val intervalMillis: Long,
    @get:StringRes val labelRes: Int,
) {
    Daily("Daily", 24L * 60L * 60L * 1000L, R.string.dictionary_update_interval_daily),
    Weekly("Weekly", 7L * 24L * 60L * 60L * 1000L, R.string.dictionary_update_interval_weekly),
    Monthly("Monthly", 30L * 24L * 60L * 60L * 1000L, R.string.dictionary_update_interval_monthly),
    ;

    companion object {
        fun fromRawValue(value: String?): DictionaryUpdateInterval? =
            entries.firstOrNull { it.rawValue == value }
    }
}

data class DictionarySettings(
    val autoUpdateDictionaries: Boolean = true,
    val dictionaryUpdateInterval: DictionaryUpdateInterval = DictionaryUpdateInterval.Weekly,
    val lastDictionaryUpdateEpochMillis: Long? = null,
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
        autoUpdateDictionaries = preferences.getBoolean(KEY_AUTO_UPDATE_DICTIONARIES, true),
        dictionaryUpdateInterval = DictionaryUpdateInterval.fromRawValue(
            preferences.getString(KEY_DICTIONARY_UPDATE_INTERVAL, null),
        ) ?: DictionaryUpdateInterval.Weekly,
        lastDictionaryUpdateEpochMillis = if (preferences.contains(KEY_LAST_DICTIONARY_UPDATE_EPOCH_MILLIS)) {
            preferences.getLong(KEY_LAST_DICTIONARY_UPDATE_EPOCH_MILLIS, 0L)
        } else {
            null
        },
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
            .putBoolean(KEY_AUTO_UPDATE_DICTIONARIES, normalized.autoUpdateDictionaries)
            .putString(KEY_DICTIONARY_UPDATE_INTERVAL, normalized.dictionaryUpdateInterval.rawValue)
            .apply {
                if (normalized.lastDictionaryUpdateEpochMillis == null) {
                    remove(KEY_LAST_DICTIONARY_UPDATE_EPOCH_MILLIS)
                } else {
                    putLong(KEY_LAST_DICTIONARY_UPDATE_EPOCH_MILLIS, normalized.lastDictionaryUpdateEpochMillis)
                }
            }
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
            .apply()
    }

    private companion object {
        const val KEY_AUTO_UPDATE_DICTIONARIES = "autoUpdateDictionaries"
        const val KEY_DICTIONARY_UPDATE_INTERVAL = "dictionaryUpdateInterval"
        const val KEY_LAST_DICTIONARY_UPDATE_EPOCH_MILLIS = "lastDictionaryUpdateEpochMillis"
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

fun Context.dictionarySettingsRepository(profileRepository: ProfileRepository? = null): DictionarySettingsRepository =
    DictionarySettingsRepository(
        dataStore = dictionarySettingsDataStore,
        legacySource = DictionarySettingsStore(this),
        profileRepository = profileRepository,
    )

class DictionarySettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val legacySource: DictionarySettingsLegacySource? = null,
    private val profileRepository: ProfileRepository? = null,
) {
    private val profileSettingsVersion = MutableStateFlow(0)

    val settings: Flow<DictionarySettings> =
        if (profileRepository == null) {
            dataStore.data
                .onStart { migrateLegacySettingsIfNeeded() }
                .map { preferences -> preferences.toDictionarySettings() }
        } else {
            combine(
                dataStore.data.onStart { migrateLegacySettingsIfNeeded() },
                profileRepository.state,
                profileSettingsVersion,
            ) { preferences, _, _ ->
                val globalSettings = preferences.toDictionarySettings()
                globalSettings.withProfileDictionarySettings(
                    profileDictionarySettingsOrMigrate(globalSettings),
                )
            }
        }

    suspend fun update(transform: (DictionarySettings) -> DictionarySettings) {
        migrateLegacySettingsIfNeeded()
        if (profileRepository != null) {
            val globalCurrent = dataStore.data.first().toDictionarySettings()
            val current = globalCurrent.withProfileDictionarySettings(
                profileDictionarySettingsOrMigrate(globalCurrent),
            )
            val updated = transform(current).normalized()
            saveProfileDictionarySettings(updated.toProfileDictionarySettings())
            dataStore.edit { preferences ->
                preferences.writeGlobalDictionarySettings(updated)
                preferences[KEY_MIGRATED_FROM_SHARED_PREFERENCES] = true
            }
            profileSettingsVersion.value += 1
            return
        }
        dataStore.edit { preferences ->
            val globalCurrent = preferences.toDictionarySettings()
            val current = globalCurrent
            val updated = transform(current).normalized()
            preferences.writeDictionarySettings(updated)
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
            autoUpdateDictionaries = this[KEY_AUTO_UPDATE_DICTIONARIES] ?: true,
            dictionaryUpdateInterval = DictionaryUpdateInterval.fromRawValue(this[KEY_DICTIONARY_UPDATE_INTERVAL])
                ?: DictionaryUpdateInterval.Weekly,
            lastDictionaryUpdateEpochMillis = this[KEY_LAST_DICTIONARY_UPDATE_EPOCH_MILLIS],
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
        this[KEY_AUTO_UPDATE_DICTIONARIES] = normalized.autoUpdateDictionaries
        this[KEY_DICTIONARY_UPDATE_INTERVAL] = normalized.dictionaryUpdateInterval.rawValue
        val lastUpdate = normalized.lastDictionaryUpdateEpochMillis
        if (lastUpdate == null) {
            remove(KEY_LAST_DICTIONARY_UPDATE_EPOCH_MILLIS)
        } else {
            this[KEY_LAST_DICTIONARY_UPDATE_EPOCH_MILLIS] = lastUpdate
        }
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
    }

    private fun MutablePreferences.writeGlobalDictionarySettings(settings: DictionarySettings) {
        val normalized = settings.normalized()
        this[KEY_AUTO_UPDATE_DICTIONARIES] = normalized.autoUpdateDictionaries
        this[KEY_DICTIONARY_UPDATE_INTERVAL] = normalized.dictionaryUpdateInterval.rawValue
        val lastUpdate = normalized.lastDictionaryUpdateEpochMillis
        if (lastUpdate == null) {
            remove(KEY_LAST_DICTIONARY_UPDATE_EPOCH_MILLIS)
        } else {
            this[KEY_LAST_DICTIONARY_UPDATE_EPOCH_MILLIS] = lastUpdate
        }
        this[KEY_LOW_RAM_DICTIONARY_IMPORT] = normalized.lowRamDictionaryImport
    }

    private fun profileDictionarySettingsOrMigrate(globalSettings: DictionarySettings): ProfileDictionarySettings {
        val repository = profileRepository ?: return globalSettings.toProfileDictionarySettings()
        val file = repository.dictionarySettingsFile()
        if (file.isFile) {
            return runCatching {
                json.decodeFromString<ProfileDictionarySettings>(file.readText()).normalized()
            }.getOrDefault(globalSettings.toProfileDictionarySettings())
        }
        val migrated = globalSettings.toProfileDictionarySettings().copy(
            collapsedDictionaries = legacyCollapsedDictionariesOrNull()?.toSet()
                ?: globalSettings.collapsedDictionaries,
        ).normalized()
        saveProfileDictionarySettings(migrated)
        return migrated
    }

    private fun legacyCollapsedDictionariesOrNull(): Set<String>? {
        val file = profileRepository?.collapsedDictionariesFile() ?: return null
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString(StringSetSerializer, file.readText())
        }.getOrNull()
    }

    private fun saveProfileDictionarySettings(settings: ProfileDictionarySettings) {
        val file = profileRepository?.dictionarySettingsFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(ProfileDictionarySettings.serializer(), settings.normalized()))
    }

    companion object {
        const val DataStoreName = "dictionary-settings"

        private val KEY_MIGRATED_FROM_SHARED_PREFERENCES =
            booleanPreferencesKey("dictionarySettingsMigratedFromSharedPreferences")
        private val KEY_AUTO_UPDATE_DICTIONARIES = booleanPreferencesKey("autoUpdateDictionaries")
        private val KEY_DICTIONARY_UPDATE_INTERVAL = stringPreferencesKey("dictionaryUpdateInterval")
        private val KEY_LAST_DICTIONARY_UPDATE_EPOCH_MILLIS = longPreferencesKey("lastDictionaryUpdateEpochMillis")
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
        private val StringSetSerializer = SetSerializer(String.serializer())
        private val json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}

@Serializable
private data class ProfileDictionarySettings(
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
    val customCSS: String = "",
) {
    fun normalized(): ProfileDictionarySettings = copy(
        maxResults = maxResults.coerceIn(DictionarySettings.MIN_MAX_RESULTS, DictionarySettings.MAX_MAX_RESULTS),
        scanLength = scanLength.coerceIn(DictionarySettings.MIN_SCAN_LENGTH, DictionarySettings.MAX_SCAN_LENGTH),
    )
}

private fun DictionarySettings.toProfileDictionarySettings(): ProfileDictionarySettings =
    normalized().let { settings ->
        ProfileDictionarySettings(
            dictionaryTabDefault = settings.dictionaryTabDefault,
            scanNonJapaneseText = settings.scanNonJapaneseText,
            maxResults = settings.maxResults,
            scanLength = settings.scanLength,
            collapseMode = settings.collapseMode,
            expandFirstDictionary = settings.expandFirstDictionary,
            collapsedDictionaries = settings.collapsedDictionaries,
            compactGlossaries = settings.compactGlossaries,
            showExpressionTags = settings.showExpressionTags,
            harmonicFrequency = settings.harmonicFrequency,
            deduplicatePitchAccents = settings.deduplicatePitchAccents,
            compactPitchAccents = settings.compactPitchAccents,
            customCSS = settings.customCSS,
        )
    }

private fun DictionarySettings.withProfileDictionarySettings(profileSettings: ProfileDictionarySettings): DictionarySettings =
    copy(
        dictionaryTabDefault = profileSettings.dictionaryTabDefault,
        scanNonJapaneseText = profileSettings.scanNonJapaneseText,
        maxResults = profileSettings.maxResults,
        scanLength = profileSettings.scanLength,
        collapseMode = profileSettings.collapseMode,
        expandFirstDictionary = profileSettings.expandFirstDictionary,
        collapsedDictionaries = profileSettings.collapsedDictionaries,
        compactGlossaries = profileSettings.compactGlossaries,
        showExpressionTags = profileSettings.showExpressionTags,
        harmonicFrequency = profileSettings.harmonicFrequency,
        deduplicatePitchAccents = profileSettings.deduplicatePitchAccents,
        compactPitchAccents = profileSettings.compactPitchAccents,
        customCSS = profileSettings.customCSS,
    ).normalized()
