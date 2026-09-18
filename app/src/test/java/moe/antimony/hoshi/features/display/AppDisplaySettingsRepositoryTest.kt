package moe.antimony.hoshi.features.display

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppDisplaySettingsRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun newInstallUsesAutomaticStandardPalettes() = runBlocking {
        repository().use { repository ->
            val settings = repository.settings.first()

            assertTrue(settings.autoSwitch)
            assertEquals(DisplayPaletteSlot.Light, settings.manualPaletteSlot)
            assertEquals(DisplayPalettePreset.Light, settings.lightPalette.preset)
            assertEquals(DisplayPalettePreset.Dark, settings.darkPalette.preset)
            assertEquals(DisplayAccentSource.System, settings.accentSource)
            assertEquals(0xFF6650A4L, settings.accentSeed)
            assertEquals(AppDisplaySettingsRepository.CurrentMigrationVersion, settings.migrationVersion)
        }
    }

    @Test
    fun updatePersistsOpaqueAccentSeedAndPaletteAlpha() = runBlocking {
        val file = tempFolder.newFile("display.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        try {
            val first = AppDisplaySettingsRepository(dataStore)
            first.update {
                it.copy(
                    accentSource = DisplayAccentSource.Custom,
                    accentSeed = 0x12654321L,
                    eInkDarkTheme = true,
                    lightPalette = it.lightPalette.copy(customBackgroundColor = 0x44112233L),
                )
            }

            val restored = AppDisplaySettingsRepository(dataStore).settings.first()
            assertEquals(0xFF654321L, restored.accentSeed)
            assertEquals(0x44112233L, restored.lightPalette.customBackgroundColor)
            assertEquals(true, restored.eInkDarkTheme)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun migratesEveryLegacyThemeShape() = runBlocking {
        val cases = listOf(
            LegacyDisplaySettingsSnapshot(theme = LegacyDisplayTheme.System) to
                Triple(true, DisplayPalettePreset.Light, DisplayPalettePreset.Dark),
            LegacyDisplaySettingsSnapshot(theme = LegacyDisplayTheme.System, systemLightSepia = true) to
                Triple(true, DisplayPalettePreset.Sepia, DisplayPalettePreset.Dark),
            LegacyDisplaySettingsSnapshot(theme = LegacyDisplayTheme.Light) to
                Triple(false, DisplayPalettePreset.Light, DisplayPalettePreset.Light),
            LegacyDisplaySettingsSnapshot(theme = LegacyDisplayTheme.Dark) to
                Triple(false, DisplayPalettePreset.Dark, DisplayPalettePreset.Light),
            LegacyDisplaySettingsSnapshot(theme = LegacyDisplayTheme.Sepia, sepiaInvertInDark = false) to
                Triple(false, DisplayPalettePreset.Sepia, DisplayPalettePreset.Light),
            LegacyDisplaySettingsSnapshot(theme = LegacyDisplayTheme.Sepia, sepiaInvertInDark = true) to
                Triple(true, DisplayPalettePreset.Sepia, DisplayPalettePreset.DarkSepia),
        )

        cases.forEachIndexed { index, (legacy, expected) ->
            repository(
                suffix = index.toString(),
                migrationSource = StaticMigrationSource(AppDisplayMigrationPayload(activeSettings = legacy)),
            ).use { repository ->
                val migrated = repository.settings.first()
                assertEquals(expected.first, migrated.autoSwitch)
                assertEquals(0xFF000000L, migrated.darkPalette.customBackgroundColor)
                assertEquals(0xFFFFFFFFL, migrated.darkPalette.customTextColor)
                if (migrated.autoSwitch) {
                    assertEquals(expected.second, migrated.lightPalette.preset)
                    assertEquals(expected.third, migrated.darkPalette.preset)
                } else {
                    assertEquals(expected.second, migrated.selection(migrated.manualPaletteSlot).preset)
                }
            }
        }
    }

    @Test
    fun releaseCustomColorsChooseMatchingGroupAndKeepAlphaAndEInk() = runBlocking {
        val cases = listOf(
            0xAAEEEEEEL to DisplayPaletteSlot.Light,
            0xCC999999L to DisplayPaletteSlot.Dark,
            0x44112233L to DisplayPaletteSlot.Dark,
        )
        for ((background, expectedSlot) in cases) {
            repository(
                migrationSource = StaticMigrationSource(
                    AppDisplayMigrationPayload(
                        activeSettings = LegacyDisplaySettingsSnapshot(
                            theme = LegacyDisplayTheme.Custom,
                            eInkMode = true,
                            customBackgroundColor = background,
                            customTextColor = 0x88445566L,
                            customInfoColor = 0xCC778899L,
                        ),
                    ),
                ),
            ).use { repository ->
                val migrated = repository.settings.first()
                val selected = migrated.selection(expectedSlot)
                assertFalse(migrated.autoSwitch)
                assertTrue(migrated.eInkMode)
                assertEquals(expectedSlot, migrated.manualPaletteSlot)
                assertEquals(DisplayPalettePreset.Custom, selected.preset)
                assertEquals(background, selected.customBackgroundColor)
                assertEquals(0x88445566L, selected.customTextColor)
                assertEquals(0xCC778899L, selected.customInfoColor)
            }
        }
    }

    @Test
    fun completedReleaseMigrationKeepsUserChangesAcrossRestart() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tempFolder.newFile("release-migration.preferences_pb") },
        )
        val key = stringPreferencesKey("settings")
        try {
            val source = StaticMigrationSource(
                AppDisplayMigrationPayload(activeSettings = LegacyDisplaySettingsSnapshot(theme = LegacyDisplayTheme.Dark)),
            )
            val repository = AppDisplaySettingsRepository(dataStore, source)
            assertEquals(DisplayPaletteSlot.Dark, repository.settings.first().manualPaletteSlot)
            repository.update {
                it.copy(
                    manualPaletteSlot = DisplayPaletteSlot.Light,
                    lightPalette = DisplayPaletteSelection(DisplayPalettePreset.Sepia),
                    accentSource = DisplayAccentSource.Custom,
                    accentSeed = 0xFF00796BL,
                    eInkMode = true,
                )
            }
            val persisted = dataStore.data.first()[key]
            val restored = AppDisplaySettingsRepository(dataStore, object : AppDisplaySettingsMigrationSource {
                override suspend fun loadMigrationPayload(): AppDisplayMigrationPayload =
                    error("Completed migration must not reread legacy settings")
            })
            restored.ensureMigrated()
            val settings = restored.settings.first()
            assertEquals(DisplayPaletteSlot.Light, settings.manualPaletteSlot)
            assertEquals(DisplayPalettePreset.Sepia, settings.lightPalette.preset)
            assertEquals(DisplayAccentSource.Custom, settings.accentSource)
            assertEquals(0xFF00796BL, settings.accentSeed)
            assertTrue(settings.eInkMode)
            assertEquals(persisted, dataStore.data.first()[key])
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failedReleaseMigrationWriteCanRetryFromUnmodifiedLegacySettings() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tempFolder.newFile("failed-release-migration.preferences_pb") },
        )
        try {
            var rejectNextWrite = true
            val failingStore = object : DataStore<Preferences> by dataStore {
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                    if (rejectNextWrite) {
                        rejectNextWrite = false
                        throw java.io.IOException("storage unavailable")
                    }
                    return dataStore.updateData(transform)
                }
            }
            val repository = AppDisplaySettingsRepository(
                failingStore,
                StaticMigrationSource(AppDisplayMigrationPayload(
                    activeSettings = LegacyDisplaySettingsSnapshot(theme = LegacyDisplayTheme.Sepia, sepiaInvertInDark = true),
                )),
            )
            assertTrue(runCatching { repository.settings.first() }.isFailure)
            assertNull(dataStore.data.first()[stringPreferencesKey("settings")])

            val migrated = repository.settings.first()
            assertTrue(migrated.autoSwitch)
            assertEquals(DisplayPalettePreset.Sepia, migrated.lightPalette.preset)
            assertEquals(DisplayPalettePreset.DarkSepia, migrated.darkPalette.preset)
            assertEquals(migrated, AppDisplaySettingsRepository(dataStore).settings.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failedMigrationIsRetriedWithoutMarkingItComplete() = runBlocking {
        var attempts = 0
        val source = object : AppDisplaySettingsMigrationSource {
            override suspend fun loadMigrationPayload(): AppDisplayMigrationPayload {
                attempts += 1
                if (attempts == 1) error("temporary read failure")
                return AppDisplayMigrationPayload(
                    activeSettings = LegacyDisplaySettingsSnapshot(theme = LegacyDisplayTheme.Dark),
                )
            }
        }
        repository(migrationSource = source).use { repository ->
            assertTrue(runCatching { repository.ensureMigrated() }.isFailure)

            repository.ensureMigrated()
            val migrated = repository.settings.first()

            assertEquals(2, attempts)
            assertEquals(DisplayPalettePreset.Dark, migrated.selection(migrated.manualPaletteSlot).preset)
            assertEquals(AppDisplaySettingsRepository.CurrentMigrationVersion, migrated.migrationVersion)
        }
    }

    private fun repository(
        suffix: String = "default",
        migrationSource: AppDisplaySettingsMigrationSource? = null,
    ): RepositoryResource {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tempFolder.newFile("display-$suffix-${System.nanoTime()}.preferences_pb") },
        )
        return RepositoryResource(AppDisplaySettingsRepository(dataStore, migrationSource), scope)
    }

    private class StaticMigrationSource(
        private val payload: AppDisplayMigrationPayload,
    ) : AppDisplaySettingsMigrationSource {
        override suspend fun loadMigrationPayload(): AppDisplayMigrationPayload = payload
    }

    private class RepositoryResource(
        private val delegate: AppDisplaySettingsRepository,
        private val scope: CoroutineScope,
    ) : Closeable {
        val settings get() = delegate.settings

        suspend fun update(transform: (AppDisplaySettings) -> AppDisplaySettings) = delegate.update(transform)

        suspend fun ensureMigrated() = delegate.ensureMigrated()

        override fun close() {
            scope.cancel()
        }
    }
}
