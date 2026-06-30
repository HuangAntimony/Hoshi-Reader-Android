package moe.antimony.hoshi.features.statistics

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

class StatisticsSettingsRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun emitsDashboardTargetDefaults() = runBlocking {
        repository().use { repository ->
            assertEquals(StatisticsTargetSettings(), repository.settings.first())
        }
    }

    @Test
    fun coercesAndPersistsTargetSettings() = runBlocking {
        repository().use { repository ->
            repository.update {
                it.copy(
                    dailyTargetType = DailyTargetType.Duration,
                    dailyCharacterTarget = 42,
                    dailyDurationTargetMinutes = 999,
                    weeklyTargetDays = 9,
                )
            }

            assertEquals(
                StatisticsTargetSettings(
                    dailyTargetType = DailyTargetType.Duration,
                    dailyCharacterTarget = 500,
                    dailyDurationTargetMinutes = 240,
                    weeklyTargetDays = 7,
                ),
                repository.settings.first(),
            )
        }
    }

    @Test
    fun snapsTargetValuesToSupportedSteps() = runBlocking {
        repository().use { repository ->
            repository.update {
                it.copy(
                    dailyCharacterTarget = 1_260,
                    dailyDurationTargetMinutes = 17,
                    weeklyTargetDays = 3,
                )
            }

            assertEquals(
                StatisticsTargetSettings(
                    dailyCharacterTarget = 1_500,
                    dailyDurationTargetMinutes = 15,
                    weeklyTargetDays = 3,
                ),
                repository.settings.first(),
            )
        }
    }

    private fun repository(): RepositoryHandle {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tempFolder.newFile("statistics-settings.preferences_pb") },
        )
        return RepositoryHandle(StatisticsSettingsRepository(dataStore), scope)
    }

    private class RepositoryHandle(
        private val repository: StatisticsSettingsRepository,
        private val scope: CoroutineScope,
    ) : AutoCloseable {
        val settings = repository.settings

        suspend fun update(transform: (StatisticsTargetSettings) -> StatisticsTargetSettings) {
            repository.update(transform)
        }

        override fun close() {
            scope.cancel()
        }
    }
}
