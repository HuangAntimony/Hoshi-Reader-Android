package moe.antimony.hoshi.features.sync

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

class SyncSettingsRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun emitsIosUploadBooksDefaultWhenNoSettingsWereSaved() = runBlocking {
        repository().use { repository ->
            val settings = repository.settings.first()

            assertFalse(settings.enabled)
            assertTrue(settings.uploadBooks)
        }
    }

    @Test
    fun persistsUploadBooksDisabledOverride() = runBlocking {
        repository().use { repository ->
            repository.update { it.copy(uploadBooks = false) }

            assertFalse(repository.settings.first().uploadBooks)
        }
    }

    private fun repository(): RepositoryHandle {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tempFolder.newFile("sync-settings.preferences_pb") },
        )
        return RepositoryHandle(SyncSettingsRepository(dataStore), scope)
    }

    private class RepositoryHandle(
        private val repository: SyncSettingsRepository,
        private val scope: CoroutineScope,
    ) : AutoCloseable {
        val settings = repository.settings

        suspend fun update(transform: (SyncSettings) -> SyncSettings) {
            repository.update(transform)
        }

        override fun close() {
            scope.cancel()
        }
    }
}
