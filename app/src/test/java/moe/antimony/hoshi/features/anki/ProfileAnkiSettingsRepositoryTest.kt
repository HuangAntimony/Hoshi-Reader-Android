package moe.antimony.hoshi.features.anki

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.profiles.ProfileRepository
import moe.antimony.hoshi.testing.CountingCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileAnkiSettingsRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test fun clientSelectionPersistsPerProfileAndStaleWritesAreRejected() = runBlocking {
        val profiles = ProfileRepository(tempFolder.newFolder("clients"))
        val repository = repository(profiles)
        val originalProfile = profiles.state.value.defaultProfileId
        repository.update { it.withAnkiDroidClient(AnkiDroidClient.Kuma3) }
        val english = profiles.createProfile("English", "en")
        profiles.activateGlobal(english.id)
        repository.update { it.withAnkiDroidClient(AnkiDroidClient.AnkiDroid) }
        assertFalse(repository.updateIfCurrent(originalProfile) { it.copy(tags = "stale") })
        assertEquals(AnkiDroidClient.AnkiDroid, repository(profiles).settings.first().ankiDroidClient)
        profiles.activateGlobal(originalProfile)
        assertEquals(AnkiDroidClient.Kuma3, repository(profiles).settings.first().ankiDroidClient)
        assertFalse(repository.settings.first().tags == "stale")
    }

    @Test
    fun accidentalDuplicatePreferencePersistsPerProfileWhileAllowDuplicatesIsOff() = runBlocking {
        val profiles = ProfileRepository(tempFolder.newFolder("duplicate-settings"))
        val repository = repository(profiles)
        assertFalse(repository.settings.first().preventAccidentalDuplicates)
        repository.update { it.copy(allowDupes = true, preventAccidentalDuplicates = true) }

        val english = profiles.createProfile("English", "en")
        profiles.activateGlobal(english.id)
        repository.update { it.copy(preventAccidentalDuplicates = false) }
        profiles.activateGlobal(profiles.state.value.defaultProfileId)
        repository.update { it.copy(allowDupes = false) }
        val restored = repository(profiles).settings.first()
        assertFalse(restored.allowDupes)
        assertTrue(restored.preventAccidentalDuplicates)

        repository.update { it.copy(allowDupes = true) }
        assertTrue(repository(profiles).settings.first().preventAccidentalDuplicates)
        profiles.activateGlobal(english.id)
        assertFalse(repository(profiles).settings.first().preventAccidentalDuplicates)
    }

    @Test
    fun freshProfileStartsWithHoshiAndPreservesExplicitlyClearedTags() = runBlocking {
        val profileRepository = ProfileRepository(tempFolder.newFolder("tag-files"))
        val repository = repository(profileRepository)
        assertEquals("hoshi", repository.settings.first().cardFormats.single().tags)

        repository.update { settings ->
            settings.updateCardFormat(settings.cardFormats.single().id) { it.copy(tags = "") }
        }
        assertEquals("", repository(profileRepository).settings.first().cardFormats.single().tags)
    }

    @Test
    fun profileSettingsReadsAndWritesUseInjectedIoDispatcher() = runBlocking {
        CountingCoroutineDispatcher().use { ioDispatcher ->
            val profileRepository = ProfileRepository(
                filesDir = tempFolder.newFolder("files"),
                ioDispatcher = ioDispatcher,
            )
            val repository = repository(
                profileRepository = profileRepository,
                ioDispatcher = ioDispatcher,
            )
            val beforeProfileAccess = ioDispatcher.dispatchCount

            repository.update { it.copy(selectedDeckName = "Japanese") }
            assertEquals("Japanese", repository.settings.first().selectedDeckName)

            assertTrue(ioDispatcher.dispatchCount >= beforeProfileAccess + 2)
        }
    }

    @Test
    fun settingsArePersistedPerActiveProfile() = runBlocking {
        val profileRepository = ProfileRepository(tempFolder.newFolder("files"))
        val repository = repository(profileRepository)

        repository.update { it.copy(selectedDeckName = "Japanese") }
        val english = profileRepository.createProfile("English", "en")
        profileRepository.activateGlobal(english.id)

        assertEquals("Japanese", repository.settings.first().selectedDeckName)

        repository.update { it.copy(selectedDeckName = "English") }
        profileRepository.activateGlobal(profileRepository.state.value.defaultProfileId)

        assertEquals("Japanese", repository.settings.first().selectedDeckName)
        profileRepository.activateGlobal(english.id)
        assertEquals("English", repository.settings.first().selectedDeckName)
    }

    @Test
    fun cardFormatIdsAndEditsFollowTheActiveProfile() = runBlocking {
        val profileRepository = ProfileRepository(tempFolder.newFolder("format-profile-files"))
        val repository = repository(profileRepository)
        repository.update {
            it.copy(cardFormats = listOf(AnkiCardFormat(id = "default-format", name = "Default")))
        }
        val english = profileRepository.createProfile("English", "en")
        profileRepository.activateGlobal(english.id)
        repository.update {
            it.copy(cardFormats = listOf(AnkiCardFormat(id = "english-format", name = "English")))
        }

        profileRepository.activateGlobal(profileRepository.state.value.defaultProfileId)
        assertEquals("default-format", repository.settings.first().cardFormats.single().id)

        profileRepository.activateGlobal(english.id)
        assertEquals("english-format", repository.settings.first().cardFormats.single().id)
    }

    @Test
    fun legacyProfileSettingsAreMigratedAndWrittenBackWithStableFormatId() = runBlocking {
        val profileRepository = ProfileRepository(tempFolder.newFolder("legacy-files"))
        val legacyFile = profileRepository.ankiConfigFile()
        legacyFile.parentFile?.mkdirs()
        legacyFile.writeText(
            """
                {
                  "selectedDeckId": 3,
                  "selectedDeckName": "Mining",
                  "selectedNoteTypeId": 7,
                  "selectedNoteTypeName": "Lapis",
                  "fieldMappings": {"Expression":"{expression}"},
                  "tags": "legacy"
                }
            """.trimIndent(),
        )
        val repository = repository(profileRepository)

        val firstRead = repository.settings.first()
        val firstId = firstRead.cardFormats.single().id
        val secondRead = repository.settings.first()

        assertTrue(firstId.isNotBlank())
        assertEquals(firstId, secondRead.cardFormats.single().id)
        assertTrue(legacyFile.readText().contains("\"schemaVersion\":2"))
        assertTrue(legacyFile.readText().contains(firstId))
    }

    @Test
    fun damagedProfileSettingsAreRepairedWithAStableFormatId() = runBlocking {
        val profileRepository = ProfileRepository(tempFolder.newFolder("damaged-files"))
        val file = profileRepository.ankiConfigFile().apply {
            parentFile?.mkdirs()
            writeText("not-json")
        }
        val repository = repository(profileRepository)

        val firstId = repository.settings.first().cardFormats.single().id
        val secondId = repository.settings.first().cardFormats.single().id

        assertEquals(firstId, secondId)
        assertTrue(file.readText().contains(firstId))
    }

    private fun repository(
        profileRepository: ProfileRepository,
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    ): AnkiSettingsRepository {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tempFolder.newFolder().resolve("anki-settings.preferences_pb") },
        )
        return DataStoreAnkiSettingsRepository(
            dataStore = dataStore,
            profileRepository = profileRepository,
            ioDispatcher = ioDispatcher,
        ).also {
            Runtime.getRuntime().addShutdownHook(Thread { scope.cancel() })
        }
    }
}
