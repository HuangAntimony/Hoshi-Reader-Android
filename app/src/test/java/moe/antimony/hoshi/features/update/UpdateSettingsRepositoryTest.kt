package moe.antimony.hoshi.features.update

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateSettingsRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun emitsAutoCheckEnabledByDefault() = runBlocking {
        repository().use { repository ->
            assertTrue(repository.settings.first().autoCheckUpdates)
        }
    }

    @Test
    fun persistsAutoCheckUpdatesToggle() = runBlocking {
        repository().use { repository ->
            repository.update { it.copy(autoCheckUpdates = false) }

            assertFalse(repository.settings.first().autoCheckUpdates)

            repository.update { it.copy(autoCheckUpdates = true) }

            assertTrue(repository.settings.first().autoCheckUpdates)
        }
    }

    @Test
    fun migratesDisabledAutoDownloadPreferenceToAutoCheck() = runBlocking {
        repository().use { repository ->
            repository.writeLegacyAutoDownloadUpdates(false)

            assertFalse(repository.settings.first().autoCheckUpdates)

            repository.update { it.copy(autoCheckUpdates = true) }

            assertTrue(repository.settings.first().autoCheckUpdates)
        }
    }

    private fun repository(): RepositoryHandle {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tempFolder.newFile("update-settings.preferences_pb") },
        )
        return RepositoryHandle(UpdateSettingsRepository(dataStore), dataStore, scope)
    }

    private class RepositoryHandle(
        private val repository: UpdateSettingsRepository,
        private val dataStore: DataStore<Preferences>,
        private val scope: CoroutineScope,
    ) : AutoCloseable {
        val settings = repository.settings

        suspend fun update(transform: (UpdateSettings) -> UpdateSettings) {
            repository.update(transform)
        }

        suspend fun writeLegacyAutoDownloadUpdates(enabled: Boolean) {
            dataStore.edit { preferences ->
                preferences[booleanPreferencesKey("autoDownloadUpdates")] = enabled
            }
        }

        override fun close() {
            scope.cancel()
        }
    }
}
