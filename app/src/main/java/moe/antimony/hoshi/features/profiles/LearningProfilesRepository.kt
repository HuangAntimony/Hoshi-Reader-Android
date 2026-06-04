package moe.antimony.hoshi.features.profiles

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.dictionary.DictionaryConfig
import moe.antimony.hoshi.features.dictionary.DictionaryLanguage

data class LearningProfile(
    val id: String,
    val name: String,
    val lookupLanguage: DictionaryLanguage,
    val dictionarySelections: ProfileDictionarySelections? = null,
)

data class ProfileDictionarySelections(
    val termEnabledFileNames: Set<String> = emptySet(),
    val frequencyEnabledFileNames: Set<String> = emptySet(),
    val pitchEnabledFileNames: Set<String> = emptySet(),
)

data class LearningProfilesState(
    val profiles: List<LearningProfile> = listOf(defaultLearningProfile()),
    val activeProfileId: String = DefaultProfileId,
) {
    val activeProfile: LearningProfile
        get() = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.first()

    val hasSingleDefaultProfile: Boolean
        get() = profiles.size == 1 && activeProfileId == DefaultProfileId

    fun normalized(): LearningProfilesState {
        val usedIds = mutableSetOf<String>()
        val normalizedProfiles = profiles.mapIndexedNotNull { index, profile ->
            val id = profile.id.trim().ifBlank { "profile-${index + 1}" }
            if (!usedIds.add(id)) return@mapIndexedNotNull null
            profile.copy(
                id = id,
                name = profile.name.trim().ifBlank { fallbackProfileName(index + 1) },
            )
        }.ifEmpty { listOf(defaultLearningProfile()) }
        val safeActiveProfileId = activeProfileId.takeIf { id ->
            normalizedProfiles.any { it.id == id }
        } ?: normalizedProfiles.first().id
        return copy(
            profiles = normalizedProfiles,
            activeProfileId = safeActiveProfileId,
        )
    }
}

private const val DefaultProfileId = "default"
private const val DefaultProfileName = "Default"
private val ProfilesJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

private fun defaultLearningProfile(): LearningProfile =
    LearningProfile(
        id = DefaultProfileId,
        name = DefaultProfileName,
        lookupLanguage = DictionaryLanguage.Default,
    )

private fun fallbackProfileName(index: Int): String =
    if (index == 1) DefaultProfileName else "Profile $index"

private val Context.learningProfilesDataStore by preferencesDataStore(
    name = LearningProfilesRepository.DataStoreName,
)

fun Context.learningProfilesRepository(): LearningProfilesRepository =
    LearningProfilesRepository(learningProfilesDataStore)

class LearningProfilesRepository(
    private val dataStore: DataStore<Preferences>,
) {
    val state: Flow<LearningProfilesState> = dataStore.data
        .map { preferences -> preferences.toLearningProfilesState() }

    suspend fun addProfile(name: String, lookupLanguage: DictionaryLanguage): LearningProfile {
        return addProfile(
            name = name,
            lookupLanguage = lookupLanguage,
            dictionarySelections = null,
        )
    }

    suspend fun addProfile(
        name: String,
        lookupLanguage: DictionaryLanguage,
        dictionarySelections: ProfileDictionarySelections?,
    ): LearningProfile {
        var createdProfile: LearningProfile? = null
        dataStore.edit { preferences ->
            val current = preferences.toLearningProfilesState()
            val profile = LearningProfile(
                id = current.nextProfileId(),
                name = name.trim().ifBlank { fallbackProfileName(current.profiles.size + 1) },
                lookupLanguage = lookupLanguage,
                dictionarySelections = dictionarySelections,
            )
            val next = current.copy(
                profiles = current.profiles + profile,
                activeProfileId = profile.id,
            ).normalized()
            createdProfile = next.activeProfile
            preferences.writeLearningProfilesState(next)
        }
        return checkNotNull(createdProfile)
    }

    suspend fun selectProfile(profileId: String): LearningProfile? {
        return selectProfile(
            profileId = profileId,
            currentDictionarySelections = null,
        )
    }

    suspend fun selectProfile(
        profileId: String,
        currentDictionarySelections: ProfileDictionarySelections?,
    ): LearningProfile? {
        var selectedProfile: LearningProfile? = null
        dataStore.edit { preferences ->
            val current = preferences.toLearningProfilesState()
            if (current.profiles.none { it.id == profileId }) return@edit
            val nextProfiles = if (currentDictionarySelections == null) {
                current.profiles
            } else {
                current.profiles.map { profile ->
                    if (profile.id == current.activeProfile.id) {
                        profile.copy(dictionarySelections = currentDictionarySelections)
                    } else {
                        profile
                    }
                }
            }
            val next = current.copy(
                profiles = nextProfiles,
                activeProfileId = profileId,
            ).normalized()
            selectedProfile = next.activeProfile
            preferences.writeLearningProfilesState(next)
        }
        return selectedProfile
    }

    suspend fun updateProfile(
        profileId: String,
        name: String,
        lookupLanguage: DictionaryLanguage,
    ): LearningProfile? {
        var updatedProfile: LearningProfile? = null
        dataStore.edit { preferences ->
            val current = preferences.toLearningProfilesState()
            val nextProfiles = current.profiles.map { profile ->
                if (profile.id == profileId) {
                    profile.copy(
                        name = name.trim().ifBlank { profile.name },
                        lookupLanguage = lookupLanguage,
                    )
                } else {
                    profile
                }
            }
            val next = current.copy(profiles = nextProfiles).normalized()
            updatedProfile = next.profiles.firstOrNull { it.id == profileId }
            preferences.writeLearningProfilesState(next)
        }
        return updatedProfile
    }

    suspend fun deleteProfile(profileId: String): LearningProfile {
        var activeProfile: LearningProfile? = null
        dataStore.edit { preferences ->
            val current = preferences.toLearningProfilesState()
            if (current.profiles.size <= 1) {
                activeProfile = current.activeProfile
                return@edit
            }
            val nextProfiles = current.profiles.filterNot { it.id == profileId }
            if (nextProfiles.size == current.profiles.size) {
                activeProfile = current.activeProfile
                return@edit
            }
            val next = current.copy(
                profiles = nextProfiles,
                activeProfileId = if (current.activeProfileId == profileId) {
                    nextProfiles.first().id
                } else {
                    current.activeProfileId
                },
            ).normalized()
            activeProfile = next.activeProfile
            preferences.writeLearningProfilesState(next)
        }
        return checkNotNull(activeProfile)
    }

    suspend fun updateActiveDictionarySelections(dictionarySelections: ProfileDictionarySelections) {
        dataStore.edit { preferences ->
            val current = preferences.toLearningProfilesState()
            val activeProfile = current.activeProfile
            val nextProfiles = current.profiles.map { profile ->
                if (profile.id == activeProfile.id) {
                    profile.copy(dictionarySelections = dictionarySelections)
                } else {
                    profile
                }
            }
            preferences.writeLearningProfilesState(current.copy(profiles = nextProfiles).normalized())
        }
    }

    suspend fun updateActiveLookupLanguage(lookupLanguage: DictionaryLanguage) {
        dataStore.edit { preferences ->
            val current = preferences.toLearningProfilesState()
            val activeProfile = current.activeProfile
            if (activeProfile.lookupLanguage == lookupLanguage) return@edit
            val nextProfiles = current.profiles.map { profile ->
                if (profile.id == activeProfile.id) {
                    profile.copy(lookupLanguage = lookupLanguage)
                } else {
                    profile
                }
            }
            preferences.writeLearningProfilesState(current.copy(profiles = nextProfiles).normalized())
        }
    }

    private fun LearningProfilesState.nextProfileId(): String {
        var index = profiles.size + 1
        while (profiles.any { it.id == "profile-$index" }) {
            index += 1
        }
        return "profile-$index"
    }

    private fun Preferences.toLearningProfilesState(): LearningProfilesState {
        val persisted = this[KEY_PROFILES_JSON]
            ?.let { json ->
                runCatching {
                    ProfilesJson.decodeFromString<PersistedLearningProfilesState>(json)
                }.getOrNull()
            }
        return persisted
            ?.toLearningProfilesState()
            ?.normalized()
            ?: LearningProfilesState()
    }

    private fun MutablePreferences.writeLearningProfilesState(state: LearningProfilesState) {
        this[KEY_PROFILES_JSON] = ProfilesJson.encodeToString(state.normalized().toPersisted())
    }

    companion object {
        const val DataStoreName = "learning-profiles"
        private val KEY_PROFILES_JSON = stringPreferencesKey("learningProfiles")
    }
}

@Serializable
private data class PersistedLearningProfilesState(
    val profiles: List<PersistedLearningProfile> = emptyList(),
    val activeProfileId: String = DefaultProfileId,
) {
    fun toLearningProfilesState(): LearningProfilesState =
        LearningProfilesState(
            profiles = profiles.map { profile ->
                LearningProfile(
                    id = profile.id,
                    name = profile.name,
                    lookupLanguage = DictionaryLanguage.fromCode(profile.lookupLanguage)
                        ?: DictionaryLanguage.Default,
                    dictionarySelections = profile.toDictionarySelections(),
                )
            },
            activeProfileId = activeProfileId,
        )
}

@Serializable
private data class PersistedLearningProfile(
    val id: String,
    val name: String,
    val lookupLanguage: String,
    val termEnabledDictionaries: List<String>? = null,
    val frequencyEnabledDictionaries: List<String>? = null,
    val pitchEnabledDictionaries: List<String>? = null,
)

private fun LearningProfilesState.toPersisted(): PersistedLearningProfilesState =
    PersistedLearningProfilesState(
        profiles = profiles.map { profile ->
            PersistedLearningProfile(
                id = profile.id,
                name = profile.name,
                lookupLanguage = profile.lookupLanguage.code,
                termEnabledDictionaries = profile.dictionarySelections?.termEnabledFileNames?.sorted(),
                frequencyEnabledDictionaries = profile.dictionarySelections?.frequencyEnabledFileNames?.sorted(),
                pitchEnabledDictionaries = profile.dictionarySelections?.pitchEnabledFileNames?.sorted(),
            )
        },
        activeProfileId = activeProfileId,
    )

private fun PersistedLearningProfile.toDictionarySelections(): ProfileDictionarySelections? {
    if (
        termEnabledDictionaries == null &&
        frequencyEnabledDictionaries == null &&
        pitchEnabledDictionaries == null
    ) {
        return null
    }
    return ProfileDictionarySelections(
        termEnabledFileNames = termEnabledDictionaries.orEmpty().toSet(),
        frequencyEnabledFileNames = frequencyEnabledDictionaries.orEmpty().toSet(),
        pitchEnabledFileNames = pitchEnabledDictionaries.orEmpty().toSet(),
    )
}

fun DictionaryConfig.toProfileDictionarySelections(): ProfileDictionarySelections =
    ProfileDictionarySelections(
        termEnabledFileNames = termDictionaries.enabledFileNames(),
        frequencyEnabledFileNames = frequencyDictionaries.enabledFileNames(),
        pitchEnabledFileNames = pitchDictionaries.enabledFileNames(),
    )

fun DictionaryConfig.withProfileDictionarySelections(
    selections: ProfileDictionarySelections,
): DictionaryConfig =
    copy(
        termDictionaries = termDictionaries.withEnabledFileNames(selections.termEnabledFileNames),
        frequencyDictionaries = frequencyDictionaries.withEnabledFileNames(selections.frequencyEnabledFileNames),
        pitchDictionaries = pitchDictionaries.withEnabledFileNames(selections.pitchEnabledFileNames),
    )

private fun List<DictionaryConfig.DictionaryEntry>.enabledFileNames(): Set<String> =
    filter { it.isEnabled }.map { it.fileName }.toSet()

private fun List<DictionaryConfig.DictionaryEntry>.withEnabledFileNames(
    enabledFileNames: Set<String>,
): List<DictionaryConfig.DictionaryEntry> =
    map { entry -> entry.copy(isEnabled = entry.fileName in enabledFileNames) }
