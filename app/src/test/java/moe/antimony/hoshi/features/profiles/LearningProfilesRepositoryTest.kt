package moe.antimony.hoshi.features.profiles

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.dictionary.DictionaryConfig
import moe.antimony.hoshi.features.dictionary.DictionaryLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LearningProfilesRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun emitsDefaultProfileWhenNoProfilesWereSaved() = runBlocking {
        repository().use { repository ->
            val state = repository.state.first()

            assertEquals(1, state.profiles.size)
            assertEquals("Default", state.activeProfile.name)
            assertEquals(DictionaryLanguage.Japanese, state.activeProfile.lookupLanguage)
            assertTrue(state.hasSingleDefaultProfile)
        }
    }

    @Test
    fun addsSelectsUpdatesAndDeletesProfiles() = runBlocking {
        repository().use { repository ->
            val frenchSelections = ProfileDictionarySelections(
                termEnabledFileNames = setOf("FrenchTerms"),
            )
            val french = repository.addProfile("French", DictionaryLanguage.French, frenchSelections)
            val spanish = repository.addProfile("Spanish", DictionaryLanguage.Spanish)

            assertEquals(spanish.id, repository.state.first().activeProfileId)
            assertEquals(DictionaryLanguage.Spanish, repository.state.first().activeProfile.lookupLanguage)

            val selected = repository.selectProfile(french.id)
            assertEquals(french.id, selected?.id)
            assertEquals(frenchSelections, selected?.dictionarySelections)
            assertEquals(DictionaryLanguage.French, repository.state.first().activeProfile.lookupLanguage)

            val updated = repository.updateProfile(french.id, "French Reading", DictionaryLanguage.German)
            assertEquals("French Reading", updated?.name)
            assertEquals(DictionaryLanguage.German, repository.state.first().activeProfile.lookupLanguage)

            val activeAfterDelete = repository.deleteProfile(french.id)
            assertEquals("Default", activeAfterDelete.name)
            assertEquals(activeAfterDelete.id, repository.state.first().activeProfileId)

            repository.deleteProfile(spanish.id)
            repository.deleteProfile(repository.state.first().activeProfileId)
            assertEquals(1, repository.state.first().profiles.size)
        }
    }

    @Test
    fun ignoresUnknownProfileSelectionAndSyncsActiveLanguage() = runBlocking {
        repository().use { repository ->
            assertNull(repository.selectProfile("missing"))

            repository.updateActiveLookupLanguage(DictionaryLanguage.Korean)

            val state = repository.state.first()
            assertEquals(DictionaryLanguage.Korean, state.activeProfile.lookupLanguage)
            assertEquals(1, state.profiles.size)
        }
    }

    @Test
    fun profileSwitchPersistsCurrentDictionarySelectionsBeforeSelectingNextProfile() = runBlocking {
        repository().use { repository ->
            val german = repository.addProfile(
                name = "German",
                lookupLanguage = DictionaryLanguage.German,
                dictionarySelections = ProfileDictionarySelections(termEnabledFileNames = setOf("GermanTerms")),
            )
            val currentGermanSelections = ProfileDictionarySelections(termEnabledFileNames = setOf("JMdict"))

            repository.selectProfile("default", currentDictionarySelections = currentGermanSelections)

            val state = repository.state.first()
            assertEquals("default", state.activeProfileId)
            assertEquals(
                currentGermanSelections,
                state.profiles.single { it.id == german.id }.dictionarySelections,
            )
        }
    }

    @Test
    fun dictionaryConfigConversionsPreserveOrderAndApplyProfileEnabledStates() {
        val config = DictionaryConfig(
            termDictionaries = listOf(
                DictionaryConfig.DictionaryEntry("JMdict", isEnabled = true, order = 0),
                DictionaryConfig.DictionaryEntry("GermanTerms", isEnabled = false, order = 1),
            ),
            frequencyDictionaries = listOf(
                DictionaryConfig.DictionaryEntry("Jiten", isEnabled = true, order = 0),
            ),
            pitchDictionaries = listOf(
                DictionaryConfig.DictionaryEntry("Pitch", isEnabled = false, order = 0),
            ),
        )
        val selections = ProfileDictionarySelections(
            termEnabledFileNames = setOf("GermanTerms"),
            pitchEnabledFileNames = setOf("Pitch"),
        )

        val applied = config.withProfileDictionarySelections(selections)

        assertEquals(listOf("JMdict", "GermanTerms"), applied.termDictionaries.map { it.fileName })
        assertEquals(listOf(false, true), applied.termDictionaries.map { it.isEnabled })
        assertEquals(listOf(false), applied.frequencyDictionaries.map { it.isEnabled })
        assertEquals(listOf(true), applied.pitchDictionaries.map { it.isEnabled })
        assertEquals(selections, applied.toProfileDictionarySelections())
    }

    private fun repository(): RepositoryHandle {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tempFolder.newFile("learning-profiles.preferences_pb") },
        )
        return RepositoryHandle(LearningProfilesRepository(dataStore), scope)
    }

    private class RepositoryHandle(
        private val repository: LearningProfilesRepository,
        private val scope: CoroutineScope,
    ) : AutoCloseable {
        val state = repository.state

        suspend fun addProfile(name: String, lookupLanguage: DictionaryLanguage): LearningProfile =
            repository.addProfile(name, lookupLanguage)

        suspend fun addProfile(
            name: String,
            lookupLanguage: DictionaryLanguage,
            dictionarySelections: ProfileDictionarySelections?,
        ): LearningProfile =
            repository.addProfile(name, lookupLanguage, dictionarySelections)

        suspend fun selectProfile(profileId: String): LearningProfile? =
            repository.selectProfile(profileId)

        suspend fun selectProfile(
            profileId: String,
            currentDictionarySelections: ProfileDictionarySelections?,
        ): LearningProfile? =
            repository.selectProfile(profileId, currentDictionarySelections)

        suspend fun updateProfile(
            profileId: String,
            name: String,
            lookupLanguage: DictionaryLanguage,
        ): LearningProfile? =
            repository.updateProfile(profileId, name, lookupLanguage)

        suspend fun deleteProfile(profileId: String): LearningProfile =
            repository.deleteProfile(profileId)

        suspend fun updateActiveLookupLanguage(lookupLanguage: DictionaryLanguage) {
            repository.updateActiveLookupLanguage(lookupLanguage)
        }

        override fun close() {
            scope.cancel()
        }
    }
}
