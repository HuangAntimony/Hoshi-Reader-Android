package moe.antimony.hoshi.features.reader

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.features.display.LegacyDisplayTheme
import moe.antimony.hoshi.profiles.ProfileRepository
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderDisplaySettingsMigrationSourceTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun migrationReadsOnlyGlobalActiveProfileEvenWhenLoadedBookProfileIsInvalid() = runBlocking {
        val filesDir = tempFolder.newFolder("global-profile-files")
        val profiles = ProfileRepository(filesDir)
        profiles.readerSettingsFile().writeProfileSettings(
            theme = "Custom",
            background = 0xFF111111L,
            text = 0xFFEEEEeeL,
            info = 0xFFAAAAAAL,
        )
        val english = profiles.createProfile("English", "en")
        profiles.readerSettingsFile(english.id).writeProfileSettings(
            theme = "Dark",
            background = 0xFF222222L,
            text = 0xFFDDDDDDL,
            info = 0xFFBBBBBBL,
        )
        profiles.activateGlobal(english.id)
        profiles.activateForBook(
            BookMetadata(
                id = "book",
                lastAccess = 0.0,
                profileId = profiles.state.value.defaultProfileId,
            ),
        )
        profiles.readerSettingsFile(profiles.state.value.defaultProfileId).writeText("not valid JSON")
        dataStore("global-profile").use { handle ->
            val payload = ReaderDisplaySettingsMigrationSource(
                dataStore = handle.dataStore,
                legacySource = null,
                profileRepository = profiles,
            ).loadMigrationPayload()

            assertEquals(LegacyDisplayTheme.Dark, payload.activeSettings?.theme)
            assertEquals(0xFF222222L, payload.activeSettings?.customBackgroundColor)
        }
    }

    @Test
    fun missingProfileSettingsFallsBackToGlobalReaderDataStore() = runBlocking {
        val profiles = ProfileRepository(tempFolder.newFolder("datastore-fallback-files"))
        dataStore("datastore-fallback").use { handle ->
            handle.dataStore.edit { preferences ->
                preferences[booleanPreferencesKey("readerSettingsMigratedFromSharedPreferences")] = true
                preferences[stringPreferencesKey("theme")] = "Custom"
                preferences[booleanPreferencesKey("eInkMode")] = true
                preferences[longPreferencesKey("customBackgroundColor")] = 0x44112233L
                preferences[longPreferencesKey("customTextColor")] = 0x88445566L
                preferences[longPreferencesKey("customInfoColor")] = 0xCC778899L
            }

            val payload = ReaderDisplaySettingsMigrationSource(
                dataStore = handle.dataStore,
                legacySource = FakeLegacy(ReaderSettings(theme = ReaderTheme.Light)),
                profileRepository = profiles,
            ).loadMigrationPayload()

            assertEquals(LegacyDisplayTheme.Custom, payload.activeSettings?.theme)
            assertEquals(0x44112233L, payload.activeSettings?.customBackgroundColor)
            assertEquals(true, payload.activeSettings?.eInkMode)
        }
    }

    @Test
    fun emptyReaderDataStoreFallsBackToSharedPreferencesSnapshot() = runBlocking {
        val profiles = ProfileRepository(tempFolder.newFolder("shared-fallback-files"))
        dataStore("shared-fallback").use { handle ->
            val payload = ReaderDisplaySettingsMigrationSource(
                dataStore = handle.dataStore,
                legacySource = FakeLegacy(
                    ReaderSettings(
                        theme = ReaderTheme.Sepia,
                        sepiaInvertInDark = true,
                        customBackgroundColor = 0xFF123456L,
                    ),
                ),
                profileRepository = profiles,
            ).loadMigrationPayload()

            assertEquals(LegacyDisplayTheme.Sepia, payload.activeSettings?.theme)
            assertEquals(true, payload.activeSettings?.sepiaInvertInDark)
            assertEquals(0xFF123456L, payload.activeSettings?.customBackgroundColor)
        }
    }

    private fun dataStore(name: String): DataStoreHandle {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        return DataStoreHandle(
            dataStore = PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { tempFolder.newFile("$name.preferences_pb") },
            ),
            scope = scope,
        )
    }

    private class FakeLegacy(private val settings: ReaderSettings) : ReaderSettingsLegacySource {
        override fun load(): ReaderSettings = settings
    }

    private class DataStoreHandle(
        val dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
        private val scope: CoroutineScope,
    ) : AutoCloseable {
        override fun close() = scope.cancel()
    }

    private fun File.writeProfileSettings(
        theme: String,
        background: Long,
        text: Long,
        info: Long,
    ) {
        parentFile?.mkdirs()
        writeText(
            """{"theme":"$theme","customBackgroundColor":$background,"customTextColor":$text,"customInfoColor":$info}""",
        )
    }
}
