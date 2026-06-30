package moe.antimony.hoshi.features.statistics

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
            assertEquals(
                StatisticsTargetSettings(
                    dailyCharacterTarget = 5_000,
                    dailyDurationTargetMinutes = 30,
                ),
                repository.settings.first(),
            )
        }
    }

    @Test
    fun readsStoredTargetsAheadOfDefaults() = runBlocking {
        repository().use { repository ->
            repository.writeStoredTargetSettings(
                StatisticsTargetSettings(
                    dailyTargetType = DailyTargetType.Duration,
                    dailyCharacterTarget = 2_000,
                    dailyDurationTargetMinutes = 15,
                    weeklyTargetDays = 3,
                ),
            )

            assertEquals(
                StatisticsTargetSettings(
                    dailyTargetType = DailyTargetType.Duration,
                    dailyCharacterTarget = 2_000,
                    dailyDurationTargetMinutes = 15,
                    weeklyTargetDays = 3,
                ),
                repository.settings.first(),
            )
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
        return RepositoryHandle(StatisticsSettingsRepository(dataStore), dataStore, scope)
    }

    private class RepositoryHandle(
        private val repository: StatisticsSettingsRepository,
        private val dataStore: DataStore<Preferences>,
        private val scope: CoroutineScope,
    ) : AutoCloseable {
        val settings = repository.settings

        suspend fun update(transform: (StatisticsTargetSettings) -> StatisticsTargetSettings) {
            repository.update(transform)
        }

        suspend fun writeStoredTargetSettings(settings: StatisticsTargetSettings) {
            dataStore.edit { preferences ->
                preferences[stringPreferencesKey("statisticsDailyTargetType")] = settings.dailyTargetType.name
                preferences[intPreferencesKey("statisticsDailyCharacterTarget")] = settings.dailyCharacterTarget
                preferences[intPreferencesKey("statisticsDailyDurationTargetMinutes")] =
                    settings.dailyDurationTargetMinutes
                preferences[intPreferencesKey("statisticsWeeklyTargetDays")] = settings.weeklyTargetDays
            }
        }

        override fun close() {
            scope.cancel()
        }
    }
}
