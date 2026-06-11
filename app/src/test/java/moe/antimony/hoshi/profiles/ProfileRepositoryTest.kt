package moe.antimony.hoshi.profiles

import moe.antimony.hoshi.content.ContentLanguageProfile
import moe.antimony.hoshi.epub.BookMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun firstLaunchCreatesJapaneseDefaultProfileAndMigratesDictionaryConfig() {
        val filesDir = tempFolder.newFolder("files")
        val legacyConfig = filesDir.resolve("Dictionaries/config.json")
        requireNotNull(legacyConfig.parentFile).mkdirs()
        legacyConfig.writeText("""{"termDictionaries":[],"frequencyDictionaries":[],"pitchDictionaries":[]}""")

        val repository = ProfileRepository(filesDir)

        val state = repository.state.value
        assertEquals(ContentLanguageProfile.Japanese.dictionaryLanguageId, state.effectiveProfile.dictionaryLanguageId)
        assertEquals(state.defaultProfileId, state.globalActiveProfileId)
        assertEquals(state.defaultProfileId, state.primaryProfileIdsByLanguage.getValue("ja"))
        assertEquals("dictionary_config.json", repository.dictionaryConfigFile().name)
        assertTrue(repository.dictionaryConfigFile().isFile)
        assertFalse(legacyConfig.exists())
    }

    @Test
    fun createsActivatesAndSelectsPrimaryEnglishProfile() {
        val repository = ProfileRepository(tempFolder.newFolder("files"))

        val english = repository.createProfile("English mining", "en")
        repository.activateGlobal(english.id)
        repository.setPrimaryProfile("en", english.id)

        val state = repository.state.value
        assertEquals(english.id, state.globalActiveProfileId)
        assertEquals(english.id, state.effectiveProfile.id)
        assertEquals(english.id, state.primaryProfileIdsByLanguage.getValue("en"))
        assertEquals(ContentLanguageProfile.English, state.effectiveContentLanguageProfile)
    }

    @Test
    fun createProfileCopiesProfileOwnedFilesFromGlobalActiveProfile() {
        val repository = ProfileRepository(tempFolder.newFolder("files"))
        val sourceProfileId = repository.state.value.globalActiveProfileId
        val dictionaryConfig = """{"termDictionaries":[{"fileName":"b","isEnabled":true,"order":0}]}"""
        val dictionarySettings = """{"maxResults":12,"customCSS":".term{}"}"""
        val collapsed = """["JMdict","Jitendex"]"""
        val ankiConfig = """{"selectedDeckName":"Japanese"}"""
        val readerSettings = """{"theme":"Dark","fontSize":30}"""
        repository.dictionaryConfigFile(sourceProfileId).writeProfileText(dictionaryConfig)
        repository.dictionarySettingsFile(sourceProfileId).writeProfileText(dictionarySettings)
        repository.collapsedDictionariesFile(sourceProfileId).writeProfileText(collapsed)
        repository.ankiConfigFile(sourceProfileId).writeProfileText(ankiConfig)
        repository.readerSettingsFile(sourceProfileId).writeProfileText(readerSettings)

        val english = repository.createProfile("English", "en")

        assertEquals(dictionaryConfig, repository.dictionaryConfigFile(english.id).readText())
        assertEquals(dictionarySettings, repository.dictionarySettingsFile(english.id).readText())
        assertEquals(collapsed, repository.collapsedDictionariesFile(english.id).readText())
        assertEquals(ankiConfig, repository.ankiConfigFile(english.id).readText())
        assertEquals(readerSettings, repository.readerSettingsFile(english.id).readText())
    }

    @Test
    fun rejectsUnsupportedDictionaryLanguageIds() {
        val repository = ProfileRepository(tempFolder.newFolder("files"))

        try {
            repository.createProfile("French", "fr")
            fail("Unsupported dictionary language id should be rejected.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("Unsupported dictionary language"))
        }
    }

    @Test
    fun activateForBookUsesForcedProfileThenLanguagePrimaryThenGlobalFallback() {
        val repository = ProfileRepository(tempFolder.newFolder("files"))
        val english = repository.createProfile("English", "en")
        repository.setPrimaryProfile("en", english.id)

        repository.activateForBook(bookMetadata(bookLanguage = "en-US"))
        assertEquals(english.id, repository.state.value.effectiveProfile.id)

        repository.activateForBook(bookMetadata(profileId = repository.state.value.defaultProfileId, bookLanguage = "en-US"))
        assertEquals(repository.state.value.defaultProfileId, repository.state.value.effectiveProfile.id)

        repository.clearLoadedProfile()
        assertEquals(repository.state.value.globalActiveProfileId, repository.state.value.effectiveProfile.id)
    }

    private fun bookMetadata(profileId: String? = null, bookLanguage: String? = null): BookMetadata =
        BookMetadata(
            id = "book",
            title = "Book",
            cover = null,
            folder = "book",
            lastAccess = 0.0,
            profileId = profileId,
            bookLanguage = bookLanguage,
        )

    private fun java.io.File.writeProfileText(value: String) {
        parentFile?.mkdirs()
        writeText(value)
    }
}
