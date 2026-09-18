package moe.antimony.hoshi.features.display

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
            assertTrue(settings.automaticInitialized)
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
                    singlePalette = it.singlePalette.copy(customBackgroundColor = 0x44112233L),
                )
            }

            val restored = AppDisplaySettingsRepository(dataStore).settings.first()
            assertEquals(0xFF654321L, restored.accentSeed)
            assertEquals(0x44112233L, restored.singlePalette.customBackgroundColor)
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
                if (migrated.autoSwitch) {
                    assertEquals(expected.second, migrated.lightPalette.preset)
                    assertEquals(expected.third, migrated.darkPalette.preset)
                } else {
                    assertEquals(expected.second, migrated.singlePalette.preset)
                    assertFalse(migrated.automaticInitialized)
                }
            }
        }
    }

    @Test
    fun customMigrationKeepsColorsAndEInk() = runBlocking {
        repository(
            migrationSource = StaticMigrationSource(
                AppDisplayMigrationPayload(
                    activeSettings = LegacyDisplaySettingsSnapshot(
                        theme = LegacyDisplayTheme.Custom,
                        eInkMode = true,
                        customBackgroundColor = 0x44112233L,
                        customTextColor = 0x88445566L,
                        customInfoColor = 0xCC778899L,
                    ),
                ),
            ),
        ).use { repository ->
            val migrated = repository.settings.first()

            assertFalse(migrated.autoSwitch)
            assertTrue(migrated.eInkMode)
            assertEquals(DisplayPalettePreset.Custom, migrated.singlePalette.preset)
            assertEquals(0x44112233L, migrated.singlePalette.customBackgroundColor)
            assertEquals(0x88445566L, migrated.singlePalette.customTextColor)
            assertEquals(0xCC778899L, migrated.singlePalette.customInfoColor)
        }
    }

    @Test
    fun migrationDeduplicatesImportedCustomPalettesByColors() = runBlocking {
        val palette = DisplayPaletteSelection(
            preset = DisplayPalettePreset.Custom,
            customBackgroundColor = 0xFF010203L,
            customTextColor = 0xFF040506L,
            customInfoColor = 0xFF070809L,
        )
        repository(
            migrationSource = StaticMigrationSource(
                AppDisplayMigrationPayload(
                    importedPalettes = listOf(
                        NamedDisplayPalette(id = "a", name = "Japanese", palette = palette),
                        NamedDisplayPalette(id = "b", name = "English", palette = palette),
                        NamedDisplayPalette(
                            id = "c",
                            name = "Korean",
                            palette = palette.copy(customTextColor = 0xFF111111L),
                        ),
                    ),
                ),
            ),
        ).use { repository ->
            val imported = repository.settings.first().importedPalettes

            assertEquals(2, imported.size)
            assertEquals("Japanese", imported[0].name)
            assertEquals("Korean", imported[1].name)
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
            assertEquals(DisplayPalettePreset.Dark, migrated.singlePalette.preset)
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
