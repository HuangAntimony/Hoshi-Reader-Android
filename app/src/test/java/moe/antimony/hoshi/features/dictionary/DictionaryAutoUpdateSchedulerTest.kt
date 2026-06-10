package moe.antimony.hoshi.features.dictionary

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.dictionary.DictionaryIndex
import moe.antimony.hoshi.dictionary.DictionaryImportDataSource
import moe.antimony.hoshi.dictionary.DictionaryLookupQueryService
import moe.antimony.hoshi.dictionary.DictionaryNativeBridge
import moe.antimony.hoshi.dictionary.DictionaryRemoteDataSource
import moe.antimony.hoshi.dictionary.DictionaryRepository
import moe.antimony.hoshi.dictionary.DictionaryStorageDataSource
import moe.antimony.hoshi.dictionary.DictionaryType
import moe.antimony.hoshi.dictionary.NativeDictionaryImportResult
import moe.antimony.hoshi.features.anki.AnkiSettings
import moe.antimony.hoshi.features.anki.AnkiSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DictionaryAutoUpdateSchedulerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun autoUpdateIsDueWhenEnabledWithUpdatableDictionariesAndNoLastUpdate() {
        val settings = DictionarySettings(
            autoUpdateDictionaries = true,
            dictionaryUpdateInterval = DictionaryUpdateInterval.Weekly,
            lastDictionaryUpdateEpochMillis = null,
        )

        assertTrue(
            shouldEnqueueDictionaryAutoUpdate(
                settings = settings,
                nowEpochMillis = 1_900_000_000_000L,
                hasUpdatableDictionaries = true,
                isMutationInProgress = false,
            ),
        )
    }

    @Test
    fun autoUpdateIsSkippedWhenDisabledOrThereAreNoUpdatableDictionaries() {
        assertFalse(
            shouldEnqueueDictionaryAutoUpdate(
                settings = DictionarySettings(autoUpdateDictionaries = false),
                nowEpochMillis = 1_900_000_000_000L,
                hasUpdatableDictionaries = true,
                isMutationInProgress = false,
            ),
        )
        assertFalse(
            shouldEnqueueDictionaryAutoUpdate(
                settings = DictionarySettings(autoUpdateDictionaries = true),
                nowEpochMillis = 1_900_000_000_000L,
                hasUpdatableDictionaries = false,
                isMutationInProgress = false,
            ),
        )
    }

    @Test
    fun autoUpdateRespectsIntervalAndBusyState() {
        val lastUpdate = 1_900_000_000_000L
        val settings = DictionarySettings(
            autoUpdateDictionaries = true,
            dictionaryUpdateInterval = DictionaryUpdateInterval.Weekly,
            lastDictionaryUpdateEpochMillis = lastUpdate,
        )

        assertFalse(
            shouldEnqueueDictionaryAutoUpdate(
                settings = settings,
                nowEpochMillis = lastUpdate + DictionaryUpdateInterval.Weekly.intervalMillis - 1L,
                hasUpdatableDictionaries = true,
                isMutationInProgress = false,
            ),
        )
        assertTrue(
            shouldEnqueueDictionaryAutoUpdate(
                settings = settings,
                nowEpochMillis = lastUpdate + DictionaryUpdateInterval.Weekly.intervalMillis,
                hasUpdatableDictionaries = true,
                isMutationInProgress = false,
            ),
        )
        assertFalse(
            shouldEnqueueDictionaryAutoUpdate(
                settings = settings,
                nowEpochMillis = lastUpdate + DictionaryUpdateInterval.Weekly.intervalMillis,
                hasUpdatableDictionaries = true,
                isMutationInProgress = true,
            ),
        )
    }

    @Test
    fun workerRunnerSkipsWhenAutoUpdateWasDisabledBeforeExecution() = runBlocking {
        settingsRepository("disabled-before-worker").use { settingsHandle ->
            val filesDir = temporaryFolder.newFolder("disabled-worker-files")
            val storage = DictionaryStorageDataSource(filesDir)
            val installed = updatableIndex()
            val remote = CountingDictionaryRemoteDataSource()
            writeDictionary(storage.typeDirectory(DictionaryType.Term), installed.title, installed)
            storage.saveConfigFromStorage()
            settingsHandle.repository.update {
                it.copy(autoUpdateDictionaries = false)
            }
            val runner = autoUpdateRunner(
                filesDir = filesDir,
                storage = storage,
                remote = remote,
                settingsRepository = settingsHandle.repository,
            )

            val summary = runner.updateIfDue(nowEpochMillis = 1_900_000_000_000L)

            assertNull(summary)
            assertEquals(0, remote.fetchCount)
        }
    }

    @Test
    fun workerRunnerSkipsWhenManualUpdateAlreadyMadeQueuedWorkNotDue() = runBlocking {
        settingsRepository("not-due-before-worker").use { settingsHandle ->
            val filesDir = temporaryFolder.newFolder("not-due-worker-files")
            val storage = DictionaryStorageDataSource(filesDir)
            val installed = updatableIndex()
            val remote = CountingDictionaryRemoteDataSource()
            val now = 1_900_000_000_000L
            writeDictionary(storage.typeDirectory(DictionaryType.Term), installed.title, installed)
            storage.saveConfigFromStorage()
            settingsHandle.repository.update {
                it.copy(
                    autoUpdateDictionaries = true,
                    dictionaryUpdateInterval = DictionaryUpdateInterval.Weekly,
                    lastDictionaryUpdateEpochMillis = now,
                )
            }
            val runner = autoUpdateRunner(
                filesDir = filesDir,
                storage = storage,
                remote = remote,
                settingsRepository = settingsHandle.repository,
            )

            val summary = runner.updateIfDue(nowEpochMillis = now + DictionaryUpdateInterval.Weekly.intervalMillis - 1L)

            assertNull(summary)
            assertEquals(0, remote.fetchCount)
        }
    }

    @Test
    fun workerRunnerSkipsWhenDictionaryMutationIsAlreadyInProgress() = runBlocking {
        settingsRepository("busy-worker").use { settingsHandle ->
            val filesDir = temporaryFolder.newFolder("busy-worker-files")
            val storage = DictionaryStorageDataSource(filesDir)
            val installed = updatableIndex()
            val remote = CountingDictionaryRemoteDataSource()
            val coordinator = DictionaryMutationCoordinator()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            writeDictionary(storage.typeDirectory(DictionaryType.Term), installed.title, installed)
            storage.saveConfigFromStorage()
            settingsHandle.repository.update {
                it.copy(autoUpdateDictionaries = true)
            }
            val runner = autoUpdateRunner(
                filesDir = filesDir,
                storage = storage,
                remote = remote,
                settingsRepository = settingsHandle.repository,
                mutationCoordinator = coordinator,
            )
            val running = async {
                coordinator.runExclusive(DictionaryMutationOperation.Import) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()

            val summary = runner.updateIfDue(nowEpochMillis = 1_900_000_000_000L)

            release.complete(Unit)
            running.await()
            assertNull(summary)
            assertEquals(0, remote.fetchCount)
        }
    }

    @Test
    fun workerResultRethrowsCancellation() = runBlocking {
        try {
            runDictionaryAutoUpdateWork {
                throw CancellationException("stopped")
            }
            fail("CancellationException should be rethrown.")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun workerResultRetriesNonCancellationFailure() = runBlocking {
        val result = runDictionaryAutoUpdateWork {
            error("network failed")
        }

        assertEquals(androidx.work.ListenableWorker.Result.retry(), result)
    }

    private fun settingsRepository(name: String): SettingsHandle {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { temporaryFolder.newFile("$name.preferences_pb") },
        )
        return SettingsHandle(DictionarySettingsRepository(dataStore), scope)
    }

    private fun autoUpdateRunner(
        filesDir: File,
        storage: DictionaryStorageDataSource,
        remote: DictionaryRemoteDataSource,
        settingsRepository: DictionarySettingsRepository,
        mutationCoordinator: DictionaryMutationCoordinator = DictionaryMutationCoordinator(),
    ): DictionaryAutoUpdateRunner {
        val dictionaryRepository = DictionaryRepository(
            filesDir,
            storage,
            DictionaryImportDataSource(NoOpDictionaryNativeBridge),
            DictionaryLookupQueryService(NoOpDictionaryNativeBridge),
            remote,
        )
        return DictionaryAutoUpdateRunner(
            dictionarySettingsRepository = settingsRepository,
            dictionaryRepository = dictionaryRepository,
            dictionaryUpdateService = DictionaryUpdateService(
                dictionaryRepository = dictionaryRepository,
                dictionarySettingsRepository = settingsRepository,
                ankiSettingsRepository = InMemoryAnkiSettingsRepository(),
                ioDispatcher = Dispatchers.Unconfined,
                clock = FakeDictionaryUpdateClock(1_900_000_000_000L),
                mutationCoordinator = mutationCoordinator,
            ),
        )
    }

    private fun updatableIndex(): DictionaryIndex =
        DictionaryIndex(
            title = "JMdict [2026-01-01]",
            format = 3,
            revision = "JMdict.2026-01-01",
            isUpdatable = true,
            indexUrl = "https://example.invalid/JMdict_english.json",
            downloadUrl = "https://example.invalid/JMdict_english.zip",
        )

    private fun writeDictionary(typeDirectory: File, fileName: String, index: DictionaryIndex) {
        val dictionaryDir = typeDirectory.resolve(fileName)
        dictionaryDir.mkdirs()
        dictionaryDir.resolve("index.json").writeText(
            kotlinx.serialization.json.Json.encodeToString(DictionaryIndex.serializer(), index),
        )
    }

    private class SettingsHandle(
        val repository: DictionarySettingsRepository,
        private val scope: CoroutineScope,
    ) : AutoCloseable {
        override fun close() {
            scope.cancel()
        }
    }

    private class CountingDictionaryRemoteDataSource : DictionaryRemoteDataSource {
        var fetchCount = 0
            private set

        override fun fetchIndex(url: String): DictionaryIndex {
            fetchCount += 1
            error("No remote fetch expected.")
        }

        override fun downloadArchive(url: String): InputStream =
            error("No archive download expected.")
    }

    private object NoOpDictionaryNativeBridge : DictionaryNativeBridge {
        override fun importDictionary(zipPath: String, outputDir: String, lowRam: Boolean): NativeDictionaryImportResult =
            error("No imports expected.")

        override fun rebuildQuery(termPaths: Array<String>, freqPaths: Array<String>, pitchPaths: Array<String>) = Unit
    }

    private class FakeDictionaryUpdateClock(private val now: Long) : DictionaryUpdateClock {
        override fun currentTimeMillis(): Long = now
    }

    private class InMemoryAnkiSettingsRepository(
        initialSettings: AnkiSettings = AnkiSettings(),
    ) : AnkiSettingsRepository {
        override val settings = MutableStateFlow(initialSettings)

        override suspend fun update(transform: (AnkiSettings) -> AnkiSettings) {
            settings.value = transform(settings.value)
        }
    }
}
