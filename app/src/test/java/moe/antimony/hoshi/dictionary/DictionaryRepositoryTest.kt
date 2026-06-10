package moe.antimony.hoshi.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertThrows

class DictionaryRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun repositoryMutationsPersistStorageConfigAndRebuildLookupPaths() {
        val filesDir = temporaryFolder.newFolder("files")
        val storage = DictionaryStorageDataSource(filesDir)
        val bridge = RecordingDictionaryNativeBridge()
        val repository = DictionaryRepository(
            filesDir,
            storage,
            DictionaryImportDataSource(bridge),
            DictionaryLookupQueryService(bridge),
        )
        writeDictionary(storage.typeDirectory(DictionaryType.Term), "First", "First")
        writeDictionary(storage.typeDirectory(DictionaryType.Term), "Second", "Second")
        writeDictionary(storage.typeDirectory(DictionaryType.Frequency), "Freq", "Freq")
        writeDictionary(storage.typeDirectory(DictionaryType.Pitch), "Pitch", "Pitch")

        repository.setDictionaryEnabled(DictionaryType.Term, "Second", enabled = false)

        val termDictionaries = repository.loadDictionaries(DictionaryType.Term).associateBy { it.path.name }
        assertEquals(setOf("First", "Second"), termDictionaries.keys)
        assertEquals(true, termDictionaries.getValue("First").isEnabled)
        assertEquals(false, termDictionaries.getValue("Second").isEnabled)
        assertEquals(listOf(filesDir.resolve("Dictionaries/Term/First").absolutePath), bridge.termPaths.toList())
        assertEquals(listOf(filesDir.resolve("Dictionaries/Frequency/Freq").absolutePath), bridge.freqPaths.toList())
        assertEquals(listOf(filesDir.resolve("Dictionaries/Pitch/Pitch").absolutePath), bridge.pitchPaths.toList())

        repository.deleteDictionary(DictionaryType.Frequency, "Freq")

        assertEquals(emptyList<String>(), repository.loadDictionaries(DictionaryType.Frequency).map { it.path.name })
        assertEquals(emptyList<String>(), bridge.freqPaths.toList())
    }

    @Test
    fun lookupQueryReadyRebuildsPersistedDictionariesAfterProcessStart() {
        val filesDir = temporaryFolder.newFolder("ready-files")
        val storage = DictionaryStorageDataSource(filesDir)
        val bridge = RecordingDictionaryNativeBridge()
        val repository = DictionaryRepository(
            filesDir,
            storage,
            DictionaryImportDataSource(bridge),
            DictionaryLookupQueryService(bridge),
        )
        writeDictionary(storage.typeDirectory(DictionaryType.Term), "First", "First")
        writeDictionary(storage.typeDirectory(DictionaryType.Frequency), "Freq", "Freq")
        writeDictionary(storage.typeDirectory(DictionaryType.Pitch), "Pitch", "Pitch")
        storage.saveConfigFromStorage()

        repository.ensureLookupQueryReady()
        repository.ensureLookupQueryReady()

        assertEquals(listOf(filesDir.resolve("Dictionaries/Term/First").absolutePath), bridge.termPaths.toList())
        assertEquals(listOf(filesDir.resolve("Dictionaries/Frequency/Freq").absolutePath), bridge.freqPaths.toList())
        assertEquals(listOf(filesDir.resolve("Dictionaries/Pitch/Pitch").absolutePath), bridge.pitchPaths.toList())
        assertEquals(1, bridge.rebuildCount)
    }

    @Test
    fun repositoryMoveRewritesIosOrderThroughStorage() {
        val filesDir = temporaryFolder.newFolder("move-files")
        val storage = DictionaryStorageDataSource(filesDir)
        val repository = DictionaryRepository(
            filesDir,
            storage,
            DictionaryImportDataSource(RecordingDictionaryNativeBridge()),
            DictionaryLookupQueryService(RecordingDictionaryNativeBridge()),
        )
        writeDictionary(storage.typeDirectory(DictionaryType.Term), "First", "First")
        writeDictionary(storage.typeDirectory(DictionaryType.Term), "Second", "Second")
        writeDictionary(storage.typeDirectory(DictionaryType.Term), "Third", "Third")
        val initialOrder = repository.loadDictionaries(DictionaryType.Term).map { it.path.name }
        val expectedOrder = initialOrder.toMutableList().also { names ->
            val moved = names.removeAt(2)
            names.add(0, moved)
        }

        repository.moveDictionary(DictionaryType.Term, fromIndex = 2, toIndex = 0)

        assertEquals(expectedOrder, repository.loadDictionaries(DictionaryType.Term).map { it.path.name })
        assertEquals(listOf(0, 1, 2), repository.loadDictionaries(DictionaryType.Term).map { it.order })
    }

    @Test
    fun updateDictionariesDownloadsNewRevisionAndPreservesOrderAndEnabledState() {
        val filesDir = temporaryFolder.newFolder("update-files")
        val storage = DictionaryStorageDataSource(filesDir)
        val bridge = ImportingDictionaryNativeBridge()
        val installedIndex = updatableTestDictionaryIndex()
        val remoteIndex = installedIndex.copy(
            title = "JMdict [2099-01-01]",
            revision = "JMdict.2099-01-01",
            downloadUrl = "https://example.invalid/JMdict_english.zip",
        )
        val remote = FakeDictionaryRemoteDataSource(
            indexes = mapOf(installedIndex.indexUrl to remoteIndex),
            archives = mapOf(remoteIndex.downloadUrl to dictionaryArchive(remoteIndex)),
        )
        val repository = DictionaryRepository(
            filesDir,
            storage,
            DictionaryImportDataSource(bridge),
            DictionaryLookupQueryService(bridge),
            remote,
        )
        writeDictionary(storage.typeDirectory(DictionaryType.Term), "First", "First")
        writeDictionary(storage.typeDirectory(DictionaryType.Term), installedIndex.title, installedIndex)
        writeDictionary(storage.typeDirectory(DictionaryType.Term), "Third", "Third")
        storage.saveConfig(
            DictionaryConfig(
                termDictionaries = listOf(
                    DictionaryConfig.DictionaryEntry("First", isEnabled = true, order = 0),
                    DictionaryConfig.DictionaryEntry(installedIndex.title, isEnabled = true, order = 1),
                    DictionaryConfig.DictionaryEntry("Third", isEnabled = true, order = 2),
                ),
                frequencyDictionaries = emptyList(),
                pitchDictionaries = emptyList(),
            ),
        )
        repository.setDictionaryEnabled(DictionaryType.Term, installedIndex.title, enabled = false)

        val progress = mutableListOf<String>()
        val summary = repository.updateDictionaries(lowRamImport = true) { update ->
            progress += "${update.stage}:${update.title}"
        }

        assertEquals(1, summary.updatedCount)
        assertEquals(1, summary.successfulCount)
        assertEquals(emptyList<DictionaryUpdateFailure>(), summary.failures)
        assertEquals(
            listOf(
                "Checking:${installedIndex.title}",
                "Downloading:${remoteIndex.title}",
                "Importing:${remoteIndex.title}",
            ),
            progress,
        )
        assertEquals(listOf(remoteIndex.downloadUrl), remote.downloadedUrls)
        assertEquals(listOf(true), bridge.lowRamModes)
        assertFalse(storage.typeDirectory(DictionaryType.Term).resolve(installedIndex.title).exists())
        val updated = repository.loadDictionaries(DictionaryType.Term)
        assertEquals(listOf("First", remoteIndex.title, "Third"), updated.map { it.index.title })
        assertEquals(listOf(true, false, true), updated.map { it.isEnabled })
        assertEquals(listOf(0, 1, 2), updated.map { it.order })
        assertEquals(
            listOf(
                filesDir.resolve("Dictionaries/Term/First").absolutePath,
                filesDir.resolve("Dictionaries/Term/Third").absolutePath,
            ),
            bridge.termPaths.toList(),
        )
    }

    @Test
    fun updateDictionariesSkipsDownloadWhenRemoteRevisionMatchesInstalledRevision() {
        val filesDir = temporaryFolder.newFolder("same-revision-files")
        val storage = DictionaryStorageDataSource(filesDir)
        val installedIndex = updatableTestDictionaryIndex()
        val remote = FakeDictionaryRemoteDataSource(
            indexes = mapOf(installedIndex.indexUrl to installedIndex),
            archives = emptyMap(),
        )
        val repository = DictionaryRepository(
            filesDir,
            storage,
            DictionaryImportDataSource(ImportingDictionaryNativeBridge()),
            DictionaryLookupQueryService(RecordingDictionaryNativeBridge()),
            remote,
        )
        writeDictionary(storage.typeDirectory(DictionaryType.Term), installedIndex.title, installedIndex)

        val summary = repository.updateDictionaries()

        assertEquals(0, summary.updatedCount)
        assertEquals(1, summary.successfulCount)
        assertEquals(emptyList<DictionaryUpdateFailure>(), summary.failures)
        assertEquals(emptyList<String>(), remote.downloadedUrls)
        assertEquals(listOf(installedIndex.title), repository.loadDictionaries(DictionaryType.Term).map { it.index.title })
    }

    @Test
    fun updateDictionariesKeepsGoingAfterOneDictionaryFails() {
        val filesDir = temporaryFolder.newFolder("partial-update-files")
        val storage = DictionaryStorageDataSource(filesDir)
        val bridge = ImportingDictionaryNativeBridge()
        val installedSuccess = updatableTestDictionaryIndex()
        val remoteSuccess = installedSuccess.copy(
            title = "JMdict [2099-01-01]",
            revision = "JMdict.2099-01-01",
            downloadUrl = "https://example.invalid/JMdict_english_2099.zip",
        )
        val installedFailure = DictionaryIndex(
            title = "Jiten",
            format = 3,
            revision = "Jiten.2026-01-01",
            isUpdatable = true,
            indexUrl = "https://example.invalid/jiten.json",
            downloadUrl = "https://example.invalid/jiten.zip",
        )
        val remote = FakeDictionaryRemoteDataSource(
            indexes = mapOf(installedSuccess.indexUrl to remoteSuccess),
            archives = mapOf(remoteSuccess.downloadUrl to dictionaryArchive(remoteSuccess)),
            failedIndexUrls = setOf(installedFailure.indexUrl),
        )
        val repository = DictionaryRepository(
            filesDir,
            storage,
            DictionaryImportDataSource(bridge),
            DictionaryLookupQueryService(bridge),
            remote,
        )
        writeDictionary(storage.typeDirectory(DictionaryType.Term), installedSuccess.title, installedSuccess)
        writeDictionary(storage.typeDirectory(DictionaryType.Frequency), installedFailure.title, installedFailure)
        storage.saveConfigFromStorage()

        val summary = repository.updateDictionaries()

        assertEquals(2, summary.checkedCount)
        assertEquals(1, summary.successfulCount)
        assertEquals(1, summary.updatedCount)
        assertEquals(listOf("Jiten: fetch failed"), summary.failures.map { "${it.title}: ${it.message}" })
        assertEquals(listOf(remoteSuccess.title), repository.loadDictionaries(DictionaryType.Term).map { it.index.title })
        assertEquals(listOf(installedFailure.title), repository.loadDictionaries(DictionaryType.Frequency).map { it.index.title })
    }

    @Test
    fun updateDictionariesReportsAllFailuresWithoutRebuildingLookup() {
        val filesDir = temporaryFolder.newFolder("failed-update-files")
        val storage = DictionaryStorageDataSource(filesDir)
        val bridge = ImportingDictionaryNativeBridge()
        val installedFailure = updatableTestDictionaryIndex()
        val remote = FakeDictionaryRemoteDataSource(
            indexes = emptyMap(),
            archives = emptyMap(),
            failedIndexUrls = setOf(installedFailure.indexUrl),
        )
        val repository = DictionaryRepository(
            filesDir,
            storage,
            DictionaryImportDataSource(bridge),
            DictionaryLookupQueryService(bridge),
            remote,
        )
        writeDictionary(storage.typeDirectory(DictionaryType.Term), installedFailure.title, installedFailure)
        storage.saveConfigFromStorage()

        val summary = repository.updateDictionaries()

        assertEquals(1, summary.checkedCount)
        assertEquals(0, summary.successfulCount)
        assertEquals(0, summary.updatedCount)
        assertEquals(listOf("JMdict [2026-01-01]: fetch failed"), summary.failures.map { "${it.title}: ${it.message}" })
        assertEquals(listOf(installedFailure.title), repository.loadDictionaries(DictionaryType.Term).map { it.index.title })
        assertEquals(0, bridge.rebuildCount)
    }

    @Test
    fun updateDictionariesRethrowsCancellation() {
        val filesDir = temporaryFolder.newFolder("cancelled-update-files")
        val storage = DictionaryStorageDataSource(filesDir)
        val installed = updatableTestDictionaryIndex()
        val repository = DictionaryRepository(
            filesDir,
            storage,
            DictionaryImportDataSource(ImportingDictionaryNativeBridge()),
            DictionaryLookupQueryService(RecordingDictionaryNativeBridge()),
            FakeDictionaryRemoteDataSource(
                indexes = emptyMap(),
                archives = emptyMap(),
                cancelledIndexUrls = setOf(installed.indexUrl),
            ),
        )
        writeDictionary(storage.typeDirectory(DictionaryType.Term), installed.title, installed)
        storage.saveConfigFromStorage()

        assertThrows(CancellationException::class.java) {
            repository.updateDictionaries()
        }
        assertEquals(listOf(installed.title), repository.loadDictionaries(DictionaryType.Term).map { it.index.title })
    }

    @Test
    fun importRecommendedDictionariesDownloadsSelectedIndexesByType() {
        val filesDir = temporaryFolder.newFolder("recommended-files")
        val storage = DictionaryStorageDataSource(filesDir)
        val bridge = ImportingDictionaryNativeBridge()
        val jmdictIndex = DictionaryIndex(
            title = "JMdict [2099-01-01]",
            format = 3,
            revision = "JMdict.2099-01-01",
            isUpdatable = true,
            indexUrl = "https://example.invalid/jmdict.json",
            downloadUrl = "https://example.invalid/jmdict.zip",
        )
        val jitenIndex = DictionaryIndex(
            title = "Jiten",
            format = 3,
            revision = "Jiten 99-01-01",
            isUpdatable = true,
            indexUrl = "https://example.invalid/jiten.json",
            downloadUrl = "https://example.invalid/jiten.zip",
        )
        val jitendexIndex = DictionaryIndex(
            title = "Jitendex.org [2026-05-05]",
            format = 3,
            revision = "2026.05.05.0",
            isUpdatable = true,
            indexUrl = "https://jitendex.org/static/yomitan.json",
            downloadUrl = "https://example.invalid/jitendex.zip",
        )
        val remote = FakeDictionaryRemoteDataSource(
            indexes = mapOf(
                jmdictIndex.indexUrl to jmdictIndex,
                jitenIndex.indexUrl to jitenIndex,
                jitendexIndex.indexUrl to jitendexIndex,
            ),
            archives = mapOf(
                jmdictIndex.downloadUrl to dictionaryArchive(jmdictIndex),
                jitenIndex.downloadUrl to dictionaryArchive(jitenIndex),
                jitendexIndex.downloadUrl to dictionaryArchive(jitendexIndex),
            ),
        )
        val repository = DictionaryRepository(
            filesDir,
            storage,
            DictionaryImportDataSource(bridge),
            DictionaryLookupQueryService(bridge),
            remote,
        )
        val selected = listOf(
            RecommendedDictionary(
                id = "jmdict",
                name = "JMdict",
                type = DictionaryType.Term,
                indexUrl = jmdictIndex.indexUrl,
            ),
            RecommendedDictionary(
                id = "jiten",
                name = "Jiten",
                type = DictionaryType.Frequency,
                indexUrl = jitenIndex.indexUrl,
            ),
            RecommendedDictionary(
                id = "jitendex",
                name = "Jitendex",
                type = DictionaryType.Term,
                indexUrl = jitendexIndex.indexUrl,
            ),
        )
        val progress = mutableListOf<String>()

        repository.importRecommendedDictionaries(selected, lowRamImport = true) { update ->
            progress += "${update.stage}:${update.title}"
        }

        assertEquals(
            listOf(
                "Fetching:JMdict",
                "Downloading:${jmdictIndex.title}",
                "Importing:${jmdictIndex.title}",
                "Fetching:Jiten",
                "Downloading:${jitenIndex.title}",
                "Importing:${jitenIndex.title}",
                "Fetching:Jitendex",
                "Downloading:${jitendexIndex.title}",
                "Importing:${jitendexIndex.title}",
            ),
            progress,
        )
        assertEquals(
            listOf(jmdictIndex.downloadUrl, jitenIndex.downloadUrl, jitendexIndex.downloadUrl),
            remote.downloadedUrls,
        )
        assertEquals(listOf(true, true, true), bridge.lowRamModes)
        assertEquals(listOf(jmdictIndex.title, jitendexIndex.title), repository.loadDictionaries(DictionaryType.Term).map { it.index.title })
        assertEquals(listOf(jitenIndex.title), repository.loadDictionaries(DictionaryType.Frequency).map { it.index.title })
        assertEquals(
            listOf(
                filesDir.resolve("Dictionaries/Term/${jmdictIndex.title}").absolutePath,
                filesDir.resolve("Dictionaries/Term/${jitendexIndex.title}").absolutePath,
            ),
            bridge.termPaths.toList(),
        )
        assertEquals(
            listOf(filesDir.resolve("Dictionaries/Frequency/${jitenIndex.title}").absolutePath),
            bridge.freqPaths.toList(),
        )
    }

    @Test
    fun manualImportUsesNativeResultCountsToCommitEachDictionaryType() {
        val filesDir = temporaryFolder.newFolder("detected-import-files")
        val storage = DictionaryStorageDataSource(filesDir)
        val bridge = ImportingDictionaryNativeBridge(
            result = NativeDictionaryImportResult(
                success = true,
                title = "Mixed",
                termCount = 10,
                metaCount = 0,
                freqCount = 3,
                pitchCount = 2,
                mediaCount = 0,
            ),
        )
        val repository = DictionaryRepository(
            filesDir,
            storage,
            DictionaryImportDataSource(bridge),
            DictionaryLookupQueryService(bridge),
        )

        repository.importDictionary(
            input = ByteArrayInputStream(dictionaryArchive(DictionaryIndex("Mixed", 3, "rev"))),
            lowRamImport = true,
        )

        assertEquals(listOf("Mixed"), repository.loadDictionaries(DictionaryType.Term).map { it.index.title })
        assertEquals(listOf("Mixed"), repository.loadDictionaries(DictionaryType.Frequency).map { it.index.title })
        assertEquals(listOf("Mixed"), repository.loadDictionaries(DictionaryType.Pitch).map { it.index.title })
        assertEquals(listOf(true), bridge.lowRamModes)
        assertEquals(listOf(filesDir.resolve("Dictionaries/Term/Mixed").absolutePath), bridge.termPaths.toList())
        assertEquals(listOf(filesDir.resolve("Dictionaries/Frequency/Mixed").absolutePath), bridge.freqPaths.toList())
        assertEquals(listOf(filesDir.resolve("Dictionaries/Pitch/Mixed").absolutePath), bridge.pitchPaths.toList())
    }

    @Test
    fun recommendedDictionariesMatchIosList() {
        assertEquals(
            listOf("jmdict", "jmnedict", "jiten", "jitendex"),
            RecommendedDictionaries.map { it.id },
        )
        assertEquals(
            "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/JMdict_english_without_proper_names.json",
            RecommendedDictionaries.first { it.id == "jmdict" }.indexUrl,
        )
    }

    private fun writeDictionary(typeDirectory: File, fileName: String, title: String) {
        writeDictionary(
            typeDirectory = typeDirectory,
            fileName = fileName,
            index = DictionaryIndex(title = title, format = 3, revision = "rev"),
        )
    }

    private fun writeDictionary(typeDirectory: File, fileName: String, index: DictionaryIndex) {
        val dictionaryDir = typeDirectory.resolve(fileName)
        dictionaryDir.mkdirs()
        dictionaryDir.resolve("index.json").writeText(dictionaryIndexJson(index))
    }

    private fun updatableTestDictionaryIndex(): DictionaryIndex =
        DictionaryIndex(
            title = "JMdict [2026-01-01]",
            format = 3,
            revision = "JMdict.2026-01-01",
            isUpdatable = true,
            indexUrl = "https://example.invalid/JMdict_english.json",
            downloadUrl = "https://example.invalid/JMdict_english.zip",
        )

    private fun dictionaryArchive(index: DictionaryIndex): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("index.json"))
            zip.write(dictionaryIndexJson(index).toByteArray())
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun dictionaryIndexJson(index: DictionaryIndex): String =
        kotlinx.serialization.json.Json.encodeToString(DictionaryIndex.serializer(), index)

    private class FakeDictionaryRemoteDataSource(
        private val indexes: Map<String, DictionaryIndex>,
        private val archives: Map<String, ByteArray>,
        private val failedIndexUrls: Set<String> = emptySet(),
        private val failedArchiveUrls: Set<String> = emptySet(),
        private val cancelledIndexUrls: Set<String> = emptySet(),
    ) : DictionaryRemoteDataSource {
        val downloadedUrls = mutableListOf<String>()

        override fun fetchIndex(url: String): DictionaryIndex {
            if (url in cancelledIndexUrls) throw CancellationException("cancelled")
            if (url in failedIndexUrls) error("fetch failed")
            return indexes.getValue(url)
        }

        override fun downloadArchive(url: String): InputStream {
            if (url in failedArchiveUrls) error("download failed")
            downloadedUrls += url
            return ByteArrayInputStream(archives.getValue(url))
        }
    }

    private class ImportingDictionaryNativeBridge(
        private val result: NativeDictionaryImportResult = NativeDictionaryImportResult(
            success = true,
            title = "",
            termCount = 1,
            metaCount = 0,
            freqCount = 0,
            pitchCount = 0,
            mediaCount = 0,
        ),
    ) : RecordingDictionaryNativeBridge() {
        override fun importDictionary(zipPath: String, outputDir: String, lowRam: Boolean): NativeDictionaryImportResult {
            lowRamModes += lowRam
            val index = ZipFile(File(zipPath)).use { zip ->
                val entry = zip.getEntry("index.json")
                val json = zip.getInputStream(entry).use { it.readBytes().decodeToString() }
                kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<DictionaryIndex>(json)
            }
            File(outputDir, "${index.title}/index.json").also { file ->
                file.parentFile!!.mkdirs()
                file.writeText(kotlinx.serialization.json.Json.encodeToString(DictionaryIndex.serializer(), index))
            }
            return result.copy(title = result.title.ifBlank { index.title })
        }
    }

    private open class RecordingDictionaryNativeBridge : DictionaryNativeBridge {
        var termPaths: Array<String> = emptyArray()
            private set
        var freqPaths: Array<String> = emptyArray()
            private set
        var pitchPaths: Array<String> = emptyArray()
            private set
        val lowRamModes = mutableListOf<Boolean>()
        var rebuildCount = 0
            private set

        override fun importDictionary(zipPath: String, outputDir: String, lowRam: Boolean): NativeDictionaryImportResult {
            lowRamModes += lowRam
            return NativeDictionaryImportResult(
                success = true,
                title = "",
                termCount = 1,
                metaCount = 0,
                freqCount = 0,
                pitchCount = 0,
                mediaCount = 0,
            )
        }

        override fun rebuildQuery(
            termPaths: Array<String>,
            freqPaths: Array<String>,
            pitchPaths: Array<String>,
        ) {
            rebuildCount += 1
            this.termPaths = termPaths
            this.freqPaths = freqPaths
            this.pitchPaths = pitchPaths
        }
    }
}
