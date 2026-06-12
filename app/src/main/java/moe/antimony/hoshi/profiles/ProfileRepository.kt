package moe.antimony.hoshi.profiles

import java.io.File
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.content.ContentLanguageProfile
import moe.antimony.hoshi.di.FilesDir
import moe.antimony.hoshi.epub.BookMetadata

@Singleton
class ProfileRepository internal constructor(
    private val filesDir: File,
    private val json: Json = defaultJson(),
) {
    @Inject
    constructor(@FilesDir filesDir: File) : this(filesDir, defaultJson())

    private val profilesDir: File = filesDir.resolve(ProfilesDirectoryName)
    private val indexFile: File = profilesDir.resolve(IndexFileName)
    private val lock = Any()
    private var loadedProfileId: String? = null
    private var storedIndex: StoredProfileIndex = initializeIndex()
    private val _state: MutableStateFlow<ProfileState> = MutableStateFlow(
        storedIndex.toProfileState(loadedProfileId = null),
    )

    val state: StateFlow<ProfileState>
        get() = _state.asStateFlow()

    val currentEffectiveProfileId: String
        get() = state.value.effectiveProfile.id

    val currentEffectiveContentLanguageProfile: ContentLanguageProfile
        get() = state.value.effectiveContentLanguageProfile

    fun createProfile(name: String, dictionaryLanguageId: String): HoshiProfile = synchronized(lock) {
        validateDictionaryLanguage(dictionaryLanguageId)
        val sourceProfileId = storedIndex.globalActiveProfileId
        val trimmedName = name.trim().ifBlank {
            ContentLanguageProfile.fromDictionaryLanguageId(dictionaryLanguageId)?.id?.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
            } ?: "Profile"
        }
        val profile = HoshiProfile(
            id = "profile-${UUID.randomUUID()}",
            name = trimmedName,
            dictionaryLanguageId = dictionaryLanguageId,
        )
        storedIndex = storedIndex.copy(profiles = storedIndex.profiles + profile).normalized()
        copyProfileOwnedFiles(sourceProfileId = sourceProfileId, targetProfileId = profile.id)
        persistIndexLocked()
        publishLocked()
        profile
    }

    fun renameProfile(profileId: String, name: String) = synchronized(lock) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Profile name must not be blank." }
        storedIndex = storedIndex.copy(
            profiles = storedIndex.profiles.map { profile ->
                if (profile.id == profileId) profile.copy(name = trimmed) else profile
            },
        ).normalized()
        require(storedIndex.profiles.any { it.id == profileId }) { "Unknown profile: $profileId" }
        persistIndexLocked()
        publishLocked()
    }

    fun deleteProfile(profileId: String) = synchronized(lock) {
        val profile = storedIndex.profiles.firstOrNull { it.id == profileId }
            ?: error("Unknown profile: $profileId")
        require(!profile.isDefault) { "Default profile cannot be deleted." }
        storedIndex = storedIndex.copy(
            profiles = storedIndex.profiles.filterNot { it.id == profileId },
            globalActiveProfileId = if (storedIndex.globalActiveProfileId == profileId) DefaultProfileId else storedIndex.globalActiveProfileId,
            primaryProfileIdsByLanguage = storedIndex.primaryProfileIdsByLanguage.filterValues { it != profileId },
        ).normalized()
        if (loadedProfileId == profileId) loadedProfileId = null
        profileDir(profileId).deleteRecursively()
        persistIndexLocked()
        publishLocked()
    }

    fun setPrimaryProfile(dictionaryLanguageId: String, profileId: String) = synchronized(lock) {
        validateDictionaryLanguage(dictionaryLanguageId)
        val profile = requireProfileLocked(profileId)
        require(profile.dictionaryLanguageId == dictionaryLanguageId) {
            "Profile language ${profile.dictionaryLanguageId} does not match $dictionaryLanguageId."
        }
        storedIndex = storedIndex.copy(
            primaryProfileIdsByLanguage = storedIndex.primaryProfileIdsByLanguage + (dictionaryLanguageId to profileId),
        ).normalized()
        persistIndexLocked()
        publishLocked()
    }

    fun activateGlobal(profileId: String) = synchronized(lock) {
        requireProfileLocked(profileId)
        storedIndex = storedIndex.copy(globalActiveProfileId = profileId).normalized()
        loadedProfileId = null
        persistIndexLocked()
        publishLocked()
    }

    fun activateForBook(metadata: BookMetadata): HoshiProfile = synchronized(lock) {
        val forced = metadata.profileId?.let { storedIndex.profiles.firstOrNull { profile -> profile.id == it } }
        val automatic = storedIndex.toProfileState(loadedProfileId = null).automaticBookProfile(metadata.bookLanguage)
        val profile = forced ?: automatic
        loadedProfileId = profile.id
        publishLocked()
        profile
    }

    fun clearLoadedProfile() = synchronized(lock) {
        loadedProfileId = null
        publishLocked()
    }

    fun dictionaryConfigFile(profileId: String = currentEffectiveProfileId): File =
        profileDataFile(profileId, DictionaryConfigFileName)

    fun dictionarySettingsFile(profileId: String = currentEffectiveProfileId): File =
        profileDataFile(profileId, DictionarySettingsFileName)

    fun ankiConfigFile(profileId: String = currentEffectiveProfileId): File =
        profileDataFile(profileId, AnkiConfigFileName)

    fun readerSettingsFile(profileId: String = currentEffectiveProfileId): File =
        profileDataFile(profileId, ReaderSettingsFileName)

    private fun initializeIndex(): StoredProfileIndex {
        profilesDir.mkdirs()
        val existing = runCatching {
            if (indexFile.isFile) json.decodeFromString<StoredProfileIndex>(indexFile.readText()) else null
        }.getOrNull()
        val initialized = (existing ?: defaultStoredIndex()).normalized()
        if (!indexFile.isFile || existing != initialized) {
            writeIndex(initialized)
        }
        migrateLegacyDictionaryConfig(initialized.defaultProfileId)
        return initialized
    }

    private fun migrateLegacyDictionaryConfig(defaultProfileId: String) {
        val legacyConfig = filesDir.resolve("Dictionaries").resolve(LegacyDictionaryConfigFileName)
        val profileConfig = profileDataFile(defaultProfileId, DictionaryConfigFileName)
        if (!legacyConfig.isFile || profileConfig.isFile) return
        profileConfig.parentFile?.mkdirs()
        if (!legacyConfig.renameTo(profileConfig)) {
            legacyConfig.copyTo(profileConfig, overwrite = false)
            legacyConfig.delete()
        }
    }

    private fun requireProfileLocked(profileId: String): HoshiProfile =
        storedIndex.profiles.firstOrNull { it.id == profileId }
            ?: error("Unknown profile: $profileId")

    private fun validateDictionaryLanguage(dictionaryLanguageId: String) {
        require(ContentLanguageProfile.fromDictionaryLanguageId(dictionaryLanguageId) != null) {
            "Unsupported dictionary language: $dictionaryLanguageId"
        }
    }

    private fun StoredProfileIndex.normalized(): StoredProfileIndex {
        val profiles = profiles
            .ifEmpty { listOf(defaultProfile()) }
            .distinctBy { it.id }
            .map { profile ->
                validateDictionaryLanguage(profile.dictionaryLanguageId)
                profile
            }
        val defaultId = defaultProfileId.takeIf { id -> profiles.any { it.id == id } }
            ?: profiles.first().id
        val globalId = globalActiveProfileId.takeIf { id -> profiles.any { it.id == id } }
            ?: defaultId
        val validIds = profiles.mapTo(mutableSetOf()) { it.id }
        val profilesByLanguage = profiles.groupBy { it.dictionaryLanguageId }
        val validPrimary = primaryProfileIdsByLanguage
            .filterKeys { ContentLanguageProfile.fromDictionaryLanguageId(it) != null }
            .filterValues { it in validIds }
            .filter { (languageId, profileId) ->
                profilesByLanguage[languageId]?.any { profile -> profile.id == profileId } == true
            }
        val primary = ContentLanguageProfile.Supported.fold(validPrimary) { current, language ->
            val languageProfiles = profilesByLanguage[language.dictionaryLanguageId].orEmpty()
            if (languageProfiles.isEmpty() || language.dictionaryLanguageId in current) {
                current
            } else {
                current + (language.dictionaryLanguageId to languageProfiles.first().id)
            }
        }
        return copy(
            profiles = profiles,
            defaultProfileId = defaultId,
            globalActiveProfileId = globalId,
            primaryProfileIdsByLanguage = primary,
        )
    }

    private fun StoredProfileIndex.toProfileState(loadedProfileId: String?): ProfileState {
        val normalized = normalized()
        val validLoaded = loadedProfileId?.takeIf { id -> normalized.profiles.any { it.id == id } }
        return ProfileState(
            profiles = normalized.profiles,
            defaultProfileId = normalized.defaultProfileId,
            globalActiveProfileId = normalized.globalActiveProfileId,
            loadedProfileId = validLoaded,
            primaryProfileIdsByLanguage = normalized.primaryProfileIdsByLanguage,
        )
    }

    private fun persistIndexLocked() {
        writeIndex(storedIndex.normalized())
    }

    private fun writeIndex(index: StoredProfileIndex) {
        profilesDir.mkdirs()
        indexFile.writeText(json.encodeToString(StoredProfileIndex.serializer(), index))
    }

    private fun publishLocked() {
        _state.value = storedIndex.toProfileState(loadedProfileId)
    }

    private fun profileDataFile(profileId: String, name: String): File =
        profileDir(profileId).resolve(name)

    private fun profileDir(profileId: String): File =
        profilesDir.resolve(profileId)

    private fun copyProfileOwnedFiles(sourceProfileId: String, targetProfileId: String) {
        val sourceDir = profileDir(sourceProfileId)
        val targetDir = profileDir(targetProfileId)
        ProfileOwnedFileNames.forEach { fileName ->
            val source = sourceDir.resolve(fileName)
            if (!source.isFile) return@forEach
            targetDir.mkdirs()
            source.copyTo(targetDir.resolve(fileName), overwrite = false)
        }
    }

    @Serializable
    private data class StoredProfileIndex(
        val profiles: List<HoshiProfile>,
        val defaultProfileId: String,
        val globalActiveProfileId: String,
        val primaryProfileIdsByLanguage: Map<String, String> = emptyMap(),
    )

    companion object {
        const val DefaultProfileId = "default-ja"
        private const val ProfilesDirectoryName = "Profiles"
        private const val IndexFileName = "profiles.json"
        private const val LegacyDictionaryConfigFileName = "config.json"
        private const val DictionaryConfigFileName = "dictionary_config.json"
        private const val DictionarySettingsFileName = "dictionary_settings.json"
        private const val AnkiConfigFileName = "anki_config.json"
        private const val ReaderSettingsFileName = "reader_settings.json"
        private val ProfileOwnedFileNames = listOf(
            DictionaryConfigFileName,
            DictionarySettingsFileName,
            AnkiConfigFileName,
            ReaderSettingsFileName,
        )

        internal fun defaultJson(): Json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        private fun defaultProfile(): HoshiProfile = HoshiProfile(
            id = DefaultProfileId,
            name = "Japanese",
            dictionaryLanguageId = ContentLanguageProfile.JapaneseLanguageId,
            isDefault = true,
        )

        private fun defaultStoredIndex(): StoredProfileIndex = StoredProfileIndex(
            profiles = listOf(defaultProfile()),
            defaultProfileId = DefaultProfileId,
            globalActiveProfileId = DefaultProfileId,
            primaryProfileIdsByLanguage = mapOf(ContentLanguageProfile.JapaneseLanguageId to DefaultProfileId),
        )
    }
}
