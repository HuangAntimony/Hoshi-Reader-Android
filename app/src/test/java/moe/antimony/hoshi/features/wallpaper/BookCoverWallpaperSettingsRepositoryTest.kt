package moe.antimony.hoshi.features.wallpaper

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookCoverWallpaperSettingsRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun settingsAreDisabledByDefault() = runBlocking {
        repository().use { handle ->
            assertEquals(BookCoverWallpaperSettings(), handle.repository.settings.first())
        }
    }

    @Test
    fun updatePersistsBothBackendsAndExportUri() = runBlocking {
        repository().use { handle ->
            handle.repository.update {
                it.copy(
                    updateLockScreen = true,
                    exportEnabled = true,
                    exportTargetUri = "content://documents/cover",
                )
            }

            assertEquals(
                BookCoverWallpaperSettings(
                    updateLockScreen = true,
                    exportEnabled = true,
                    exportTargetUri = "content://documents/cover",
                ),
                handle.repository.settings.first(),
            )
        }
    }

    @Test
    fun disablingExportRetainsItsTargetUri() = runBlocking {
        repository().use { handle ->
            handle.repository.update {
                it.copy(
                    exportEnabled = true,
                    exportTargetUri = "content://documents/cover",
                )
            }

            handle.repository.update { it.copy(exportEnabled = false) }

            assertEquals(
                BookCoverWallpaperSettings(
                    exportEnabled = false,
                    exportTargetUri = "content://documents/cover",
                ),
                handle.repository.settings.first(),
            )
        }
    }

    private fun repository(): RepositoryHandle {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tempFolder.newFile("book-cover-wallpaper.preferences_pb") },
        )
        return RepositoryHandle(BookCoverWallpaperSettingsRepository(dataStore), scope)
    }

    private class RepositoryHandle(
        val repository: BookCoverWallpaperSettingsRepository,
        private val scope: CoroutineScope,
    ) : AutoCloseable {
        override fun close() {
            scope.cancel()
        }
    }
}
