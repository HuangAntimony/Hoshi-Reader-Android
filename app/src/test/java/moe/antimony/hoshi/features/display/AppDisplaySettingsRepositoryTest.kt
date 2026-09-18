package moe.antimony.hoshi.features.display

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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
            assertEquals(DisplayPalettePreset.Custom, migrated.selection(migrated.manualPaletteSlot).preset)
            assertEquals(0x44112233L, migrated.selection(migrated.manualPaletteSlot).customBackgroundColor)
            assertEquals(0x88445566L, migrated.selection(migrated.manualPaletteSlot).customTextColor)
            assertEquals(0xCC778899L, migrated.selection(migrated.manualPaletteSlot).customInfoColor)
        }
    }

    @Test
    fun upgradeRemovesImportedPalettesWithoutResettingSavedDisplaySettingsAndRetriesFailures() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tempFolder.newFile("display-upgrade.preferences_pb") },
        )
        val key = stringPreferencesKey("settings")
        val json = Json { encodeDefaults = true }
        val saved = AppDisplaySettings(
            autoSwitch = true,
            lightPalette = DisplayPaletteSelection(DisplayPalettePreset.Sepia, 0xFFABCDEF, 0xFF123456, 0xFF654321),
            darkPalette = DisplayPaletteSelection(DisplayPalettePreset.Custom, 0xFF112233, 0xFFEEEEEE, 0xFFAAAAAA),
            accentSource = DisplayAccentSource.Custom,
            accentSeed = 0xFF00796B,
            eInkMode = true,
            migrationVersion = 1,
        )
        val oldJson = JsonObject(
            json.parseToJsonElement(json.encodeToString(saved)).jsonObject +
                ("importedPalettes" to json.parseToJsonElement(
                    """[{"id":"legacy-profile","name":"Japanese","palette":{"preset":"Custom","customBackgroundColor":4278256131}}]""",
                )),
        ).toString()
        try {
            dataStore.edit { it[key] = oldJson }
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
            val repository = AppDisplaySettingsRepository(failingStore, object : AppDisplaySettingsMigrationSource {
                override suspend fun loadMigrationPayload(): AppDisplayMigrationPayload =
                    error("Existing global display settings must not be read again from profiles")
            })

            assertTrue(runCatching { repository.settings.first() }.isFailure)
            assertEquals(oldJson, dataStore.data.first()[key])

            val upgraded = repository.settings.first()
            assertEquals(saved.copy(migrationVersion = AppDisplaySettingsRepository.CurrentMigrationVersion), upgraded)
            val persisted = requireNotNull(dataStore.data.first()[key])
            assertFalse(json.parseToJsonElement(persisted).jsonObject.containsKey("importedPalettes"))
            repository.ensureMigrated()
            assertEquals(persisted, dataStore.data.first()[key])
            assertEquals(upgraded, AppDisplaySettingsRepository(dataStore).settings.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun upgradeMovesManualSelectionIntoMatchingGroupAndPersistsItOnlyOnce() = runBlocking {
        val choices = listOf(
            DisplayPaletteSelection(DisplayPalettePreset.Light) to DisplayPaletteSlot.Light,
            DisplayPaletteSelection(DisplayPalettePreset.Sepia) to DisplayPaletteSlot.Light,
            DisplayPaletteSelection(DisplayPalettePreset.Dark) to DisplayPaletteSlot.Dark,
            DisplayPaletteSelection(DisplayPalettePreset.DarkSepia) to DisplayPaletteSlot.Dark,
            DisplayPaletteSelection(DisplayPalettePreset.Custom, 0xAAEEEEEE, 0x44112233, 0x88999999) to DisplayPaletteSlot.Light,
            DisplayPaletteSelection(DisplayPalettePreset.Custom, 0xCC999999, 0xDDFFFFFF, 0xEEAAAAAA) to DisplayPaletteSlot.Dark,
        )
        for ((index, choice) in choices.withIndex()) {
            val (previous, expectedSlot) = choice
            val scope = CoroutineScope(Dispatchers.IO + Job())
            val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = {
                tempFolder.newFile("display-manual-upgrade-$index.preferences_pb")
            })
            val key = stringPreferencesKey("settings")
            val json = Json { encodeDefaults = true }
            val saved = AppDisplaySettings(
                autoSwitch = false,
                lightPalette = DisplayPaletteSelection(DisplayPalettePreset.Custom, 0xFFAABBCC, 0xFF223344, 0xFF778899),
                darkPalette = DisplayPaletteSelection(DisplayPalettePreset.Custom, 0xFF223344, 0xFFAABBCC, 0xFF778899),
                migrationVersion = 2,
            )
            val oldJson = JsonObject(
                (json.parseToJsonElement(json.encodeToString(saved)).jsonObject - "manualPaletteSlot") +
                    ("singlePalette" to json.parseToJsonElement(json.encodeToString(previous))),
            ).toString()
            try {
                dataStore.edit { it[key] = oldJson }
                val repository = AppDisplaySettingsRepository(dataStore)
                val upgraded = repository.settings.first()
                assertEquals(expectedSlot, upgraded.manualPaletteSlot)
                val active = resolveDisplaySettings(upgraded, false)
                assertEquals(previous.preset, active.palette)
                assertEquals(expectedSlot == DisplayPaletteSlot.Dark, active.isDark)
                assertEquals(active, resolveDisplaySettings(upgraded, true))
                if (previous.preset == DisplayPalettePreset.Custom) {
                    assertEquals(previous.customBackgroundColor, active.backgroundColor)
                    assertEquals(previous.customTextColor, active.textColor)
                    assertEquals(previous.customInfoColor, active.infoColor)
                }
                val other = if (expectedSlot == DisplayPaletteSlot.Light) DisplayPaletteSlot.Dark else DisplayPaletteSlot.Light
                assertEquals(saved.selection(other), upgraded.selection(other))
                val persisted = dataStore.data.first()[key]
                assertFalse(json.parseToJsonElement(persisted!!).jsonObject.containsKey("singlePalette"))
                repository.ensureMigrated()
                assertEquals(persisted, dataStore.data.first()[key])
                assertEquals(upgraded, AppDisplaySettingsRepository(dataStore).settings.first())
            } finally {
                scope.cancel()
            }
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
