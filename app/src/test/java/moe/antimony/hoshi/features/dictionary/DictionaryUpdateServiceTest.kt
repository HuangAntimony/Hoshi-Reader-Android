package moe.antimony.hoshi.features.dictionary

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DictionaryUpdateServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun successfulUpdateRecordsLastUpdateAndMigratesDictionaryTitleReferences() = runBlocking {
        settingsRepository().use { settingsHandle ->
            val filesDir = temporaryFolder.newFolder("service-files")
            val storage = DictionaryStorageDataSource(filesDir)
            val installed = updatableIndex("JMdict [2026-01-01]", "rev-2026")
            val remoteIndex = installed.copy(
                title = "JMdict [2099-01-01]",
                revision = "rev-2099",
                downloadUrl = "https://example.invalid/jmdict-2099.zip",
            )
            writeDictionary(storage.typeDirectory(DictionaryType.Term), installed.title, installed)
            storage.saveConfigFromStorage()
            settingsHandle.repository.update {
                it.copy(
                    collapsedDictionaries = setOf(installed.title, "Other"),
                    lowRamDictionaryImport = true,
                )
            }
            val ankiRepository = InMemoryAnkiSettingsRepository(
                AnkiSettings(
                    fieldMappings = mapOf(
                        "MainDefinition" to "{single-glossary-${installed.title}}",
                        "BriefDefinition" to "{single-glossary-${installed.title}-brief}",
                        "CleanDefinition" to "{single-glossary-${installed.title}-no-dictionary}",
                        "Sentence" to "{sentence}",
                    ),
                ),
            )
            val coordinator = DictionaryMutationCoordinator()
            val service = DictionaryUpdateService(
                dictionaryRepository = DictionaryRepository(
                    filesDir,
                    storage,
                    DictionaryImportDataSource(ImportingDictionaryNativeBridge()),
                    DictionaryLookupQueryService(NoOpDictionaryNativeBridge),
                    FakeDictionaryRemoteDataSource(
                        indexes = mapOf(installed.indexUrl to remoteIndex),
                        archives = mapOf(remoteIndex.downloadUrl to dictionaryArchive(remoteIndex)),
                    ),
                ),
                dictionarySettingsRepository = settingsHandle.repository,
                ankiSettingsRepository = ankiRepository,
                ioDispatcher = Dispatchers.Unconfined,
                clock = FakeDictionaryUpdateClock(1_900_000_000_000L),
                mutationCoordinator = coordinator,
            )

            val summary = service.updateDictionaries()

            assertEquals(1, summary.successfulCount)
            assertEquals(1L, coordinator.state.value.completedChangeVersion)
            val savedSettings = settingsHandle.repository.settings.first()
            assertEquals(1_900_000_000_000L, savedSettings.lastDictionaryUpdateEpochMillis)
            assertEquals(setOf(remoteIndex.title, "Other"), savedSettings.collapsedDictionaries)
            assertEquals(
                "{single-glossary-${remoteIndex.title}}",
                ankiRepository.settings.first().fieldMappings["MainDefinition"],
            )
            assertEquals(
                "{single-glossary-${remoteIndex.title}-brief}",
                ankiRepository.settings.first().fieldMappings["BriefDefinition"],
            )
            assertEquals(
                "{single-glossary-${remoteIndex.title}-no-dictionary}",
                ankiRepository.settings.first().fieldMappings["CleanDefinition"],
            )
        }
    }

    @Test
    fun failedUpdateDoesNotRecordLastUpdate() = runBlocking {
        settingsRepository().use { settingsHandle ->
            val filesDir = temporaryFolder.newFolder("service-failure-files")
            val storage = DictionaryStorageDataSource(filesDir)
            val installed = updatableIndex("JMdict [2026-01-01]", "rev-2026")
            writeDictionary(storage.typeDirectory(DictionaryType.Term), installed.title, installed)
            storage.saveConfigFromStorage()
            val coordinator = DictionaryMutationCoordinator()
            val service = DictionaryUpdateService(
                dictionaryRepository = DictionaryRepository(
                    filesDir,
                    storage,
                    DictionaryImportDataSource(ImportingDictionaryNativeBridge()),
                    DictionaryLookupQueryService(NoOpDictionaryNativeBridge),
                    FakeDictionaryRemoteDataSource(
                        indexes = emptyMap(),
                        archives = emptyMap(),
                        failedIndexUrls = setOf(installed.indexUrl),
                    ),
                ),
                dictionarySettingsRepository = settingsHandle.repository,
                ankiSettingsRepository = InMemoryAnkiSettingsRepository(),
                ioDispatcher = Dispatchers.Unconfined,
                clock = FakeDictionaryUpdateClock(1_900_000_000_000L),
                mutationCoordinator = coordinator,
            )

            val summary = service.updateDictionaries()

            assertEquals(0, summary.successfulCount)
            assertEquals(0L, coordinator.state.value.completedChangeVersion)
            assertEquals(null, settingsHandle.repository.settings.first().lastDictionaryUpdateEpochMillis)
        }
    }

    @Test
    fun autoUpdatePublishesMutationStateAndProgress() = runBlocking {
        settingsRepository().use { settingsHandle ->
            val filesDir = temporaryFolder.newFolder("service-auto-files")
            val storage = DictionaryStorageDataSource(filesDir)
            val installed = updatableIndex("JMdict [2026-01-01]", "rev-2026")
            val remoteIndex = installed.copy(
                title = "JMdict [2099-01-01]",
                revision = "rev-2099",
                downloadUrl = "https://example.invalid/jmdict-2099.zip",
            )
            writeDictionary(storage.typeDirectory(DictionaryType.Term), installed.title, installed)
            storage.saveConfigFromStorage()
            val coordinator = DictionaryMutationCoordinator()
            val service = DictionaryUpdateService(
                dictionaryRepository = DictionaryRepository(
                    filesDir,
                    storage,
                    DictionaryImportDataSource(ImportingDictionaryNativeBridge()),
                    DictionaryLookupQueryService(NoOpDictionaryNativeBridge),
                    FakeDictionaryRemoteDataSource(
                        indexes = mapOf(installed.indexUrl to remoteIndex),
                        archives = mapOf(remoteIndex.downloadUrl to dictionaryArchive(remoteIndex)),
                    ),
                ),
                dictionarySettingsRepository = settingsHandle.repository,
                ankiSettingsRepository = InMemoryAnkiSettingsRepository(),
                ioDispatcher = Dispatchers.Unconfined,
                clock = FakeDictionaryUpdateClock(1_900_000_000_000L),
                mutationCoordinator = coordinator,
            )
            val observedOperations = mutableListOf<DictionaryMutationOperation?>()
            val observedProgressTitles = mutableListOf<String?>()

            service.updateDictionaries(
                operation = DictionaryMutationOperation.AutoUpdate,
                onProgress = {
                observedOperations += coordinator.state.value.operation
                observedProgressTitles += coordinator.state.value.progress?.title
                },
            )

            assertTrue(observedOperations.all { it == DictionaryMutationOperation.AutoUpdate })
            assertTrue(observedProgressTitles.contains(installed.title))
            assertTrue(observedProgressTitles.contains(remoteIndex.title))
            assertEquals(DictionaryMutationState(completedChangeVersion = 1L), coordinator.state.value)
        }
    }

    @Test
    fun busyMutationSkipsUpdateWithoutTouchingRepository() = runBlocking {
        settingsRepository().use { settingsHandle ->
            val filesDir = temporaryFolder.newFolder("service-busy-files")
            val storage = DictionaryStorageDataSource(filesDir)
            val installed = updatableIndex("JMdict [2026-01-01]", "rev-2026")
            writeDictionary(storage.typeDirectory(DictionaryType.Term), installed.title, installed)
            storage.saveConfigFromStorage()
            val remote = FakeDictionaryRemoteDataSource(
                indexes = mapOf(installed.indexUrl to installed),
                archives = emptyMap(),
            )
            val coordinator = DictionaryMutationCoordinator()
            val service = DictionaryUpdateService(
                dictionaryRepository = DictionaryRepository(
                    filesDir,
                    storage,
                    DictionaryImportDataSource(ImportingDictionaryNativeBridge()),
                    DictionaryLookupQueryService(NoOpDictionaryNativeBridge),
                    remote,
                ),
                dictionarySettingsRepository = settingsHandle.repository,
                ankiSettingsRepository = InMemoryAnkiSettingsRepository(),
                ioDispatcher = Dispatchers.Unconfined,
                clock = FakeDictionaryUpdateClock(1_900_000_000_000L),
                mutationCoordinator = coordinator,
            )
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val running = async {
                coordinator.runExclusive(DictionaryMutationOperation.Import) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()

            val summary = service.updateDictionaries()

            release.complete(Unit)
            running.await()
            assertEquals(0, summary.checkedCount)
            assertEquals(0, remote.fetchCount)
            assertEquals(null, settingsHandle.repository.settings.first().lastDictionaryUpdateEpochMillis)
        }
    }

    @Test
    fun unchangedPartialSuccessRecordsLastUpdateWithoutCompletedChangeVersion() = runBlocking {
        settingsRepository().use { settingsHandle ->
            val filesDir = temporaryFolder.newFolder("service-partial-files")
            val storage = DictionaryStorageDataSource(filesDir)
            val unchanged = updatableIndex("JMdict [2026-01-01]", "rev-2026")
            val failing = updatableIndex("Jiten [2026-01-01]", "rev-failing")
            writeDictionary(storage.typeDirectory(DictionaryType.Term), unchanged.title, unchanged)
            writeDictionary(storage.typeDirectory(DictionaryType.Frequency), failing.title, failing)
            storage.saveConfigFromStorage()
            val coordinator = DictionaryMutationCoordinator()
            val service = DictionaryUpdateService(
                dictionaryRepository = DictionaryRepository(
                    filesDir,
                    storage,
                    DictionaryImportDataSource(ImportingDictionaryNativeBridge()),
                    DictionaryLookupQueryService(NoOpDictionaryNativeBridge),
                    FakeDictionaryRemoteDataSource(
                        indexes = mapOf(unchanged.indexUrl to unchanged),
                        archives = emptyMap(),
                        failedIndexUrls = setOf(failing.indexUrl),
                    ),
                ),
                dictionarySettingsRepository = settingsHandle.repository,
                ankiSettingsRepository = InMemoryAnkiSettingsRepository(),
                ioDispatcher = Dispatchers.Unconfined,
                clock = FakeDictionaryUpdateClock(1_900_000_000_000L),
                mutationCoordinator = coordinator,
            )

            val summary = service.updateDictionaries()

            assertEquals(1, summary.successfulCount)
            assertEquals(0, summary.updatedCount)
            assertEquals(1, summary.failures.size)
            assertEquals(1_900_000_000_000L, settingsHandle.repository.settings.first().lastDictionaryUpdateEpochMillis)
            assertEquals(0L, coordinator.state.value.completedChangeVersion)
        }
    }

    private fun settingsRepository(): SettingsHandle {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { temporaryFolder.newFile("dictionary-update-service.preferences_pb") },
        )
        return SettingsHandle(DictionarySettingsRepository(dataStore), scope)
    }

    private class SettingsHandle(
        val repository: DictionarySettingsRepository,
        private val scope: CoroutineScope,
    ) : AutoCloseable {
        override fun close() {
            scope.cancel()
        }
    }

    private fun updatableIndex(title: String, revision: String): DictionaryIndex =
        DictionaryIndex(
            title = title,
            format = 3,
            revision = revision,
            isUpdatable = true,
            indexUrl = "https://example.invalid/$revision.json",
            downloadUrl = "https://example.invalid/$revision.zip",
        )

    private fun writeDictionary(typeDirectory: File, fileName: String, index: DictionaryIndex) {
        val dictionaryDir = typeDirectory.resolve(fileName)
        dictionaryDir.mkdirs()
        dictionaryDir.resolve("index.json").writeText(
            kotlinx.serialization.json.Json.encodeToString(DictionaryIndex.serializer(), index),
        )
    }

    private fun dictionaryArchive(index: DictionaryIndex): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("index.json"))
            zip.write(kotlinx.serialization.json.Json.encodeToString(DictionaryIndex.serializer(), index).toByteArray())
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private class FakeDictionaryRemoteDataSource(
        private val indexes: Map<String, DictionaryIndex>,
        private val archives: Map<String, ByteArray>,
        private val failedIndexUrls: Set<String> = emptySet(),
    ) : DictionaryRemoteDataSource {
        var fetchCount = 0
            private set

        override fun fetchIndex(url: String): DictionaryIndex {
            fetchCount += 1
            if (url in failedIndexUrls) error("fetch failed")
            return indexes.getValue(url)
        }

        override fun downloadArchive(url: String): InputStream =
            ByteArrayInputStream(archives.getValue(url))
    }

    private class ImportingDictionaryNativeBridge : DictionaryNativeBridge {
        override fun importDictionary(zipPath: String, outputDir: String, lowRam: Boolean): NativeDictionaryImportResult {
            val index = ZipFile(File(zipPath)).use { zip ->
                zip.getInputStream(zip.getEntry("index.json")).use { input ->
                    kotlinx.serialization.json.Json.decodeFromString<DictionaryIndex>(input.readBytes().decodeToString())
                }
            }
            File(outputDir, "${index.title}/index.json").also { file ->
                file.parentFile!!.mkdirs()
                file.writeText(kotlinx.serialization.json.Json.encodeToString(DictionaryIndex.serializer(), index))
            }
            return NativeDictionaryImportResult(
                success = true,
                title = index.title,
                termCount = 1,
                metaCount = 0,
                freqCount = 0,
                pitchCount = 0,
                mediaCount = 0,
            )
        }

        override fun rebuildQuery(session: Long, termPaths: Array<String>, freqPaths: Array<String>, pitchPaths: Array<String>) = Unit
    }

    private object NoOpDictionaryNativeBridge : DictionaryNativeBridge {
        override fun importDictionary(zipPath: String, outputDir: String, lowRam: Boolean): NativeDictionaryImportResult =
            error("No imports expected.")

        override fun rebuildQuery(session: Long, termPaths: Array<String>, freqPaths: Array<String>, pitchPaths: Array<String>) = Unit
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
