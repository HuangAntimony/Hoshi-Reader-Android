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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileAnkiSettingsRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

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
            produceFile = { tempFolder.newFile("anki-settings.preferences_pb") },
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
