package moe.antimony.hoshi.features.update

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
    fun emitsAutoDownloadEnabledByDefault() = runBlocking {
        repository().use { repository ->
            assertTrue(repository.settings.first().autoDownloadUpdates)
        }
    }

    @Test
    fun persistsAutoDownloadUpdatesToggle() = runBlocking {
        repository().use { repository ->
            repository.update { it.copy(autoDownloadUpdates = false) }

            assertFalse(repository.settings.first().autoDownloadUpdates)

            repository.update { it.copy(autoDownloadUpdates = true) }

            assertTrue(repository.settings.first().autoDownloadUpdates)
        }
    }

    private fun repository(): RepositoryHandle {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tempFolder.newFile("update-settings.preferences_pb") },
        )
        return RepositoryHandle(UpdateSettingsRepository(dataStore), scope)
    }

    private class RepositoryHandle(
        private val repository: UpdateSettingsRepository,
        private val scope: CoroutineScope,
    ) : AutoCloseable {
        val settings = repository.settings

        suspend fun update(transform: (UpdateSettings) -> UpdateSettings) {
            repository.update(transform)
        }

        override fun close() {
            scope.cancel()
        }
    }
}
