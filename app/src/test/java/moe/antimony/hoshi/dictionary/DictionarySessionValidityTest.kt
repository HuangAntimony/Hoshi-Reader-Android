package moe.antimony.hoshi.dictionary

import de.manhhao.hoshi.DictionaryStyle
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.features.dictionary.DictionaryMutationCoordinator
import moe.antimony.hoshi.features.dictionary.DictionaryMutationOperation
import moe.antimony.hoshi.profiles.ProfileRepository
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DictionarySessionValidityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun sameLanguageProfileSwitchDuringRebuildDoesNotReuseThePreviousConfiguration() = runBlocking {
        val root = temporary.newFolder()
        val profiles = ProfileRepository(root, ioDispatcher = Dispatchers.Unconfined)
        val second = profiles.createProfile("Second Japanese", "ja")
        val storage = DictionaryStorageDataSource(root, profiles)
        writeIndex(storage.typeDirectory(DictionaryType.Term).resolve("First"), "First")
        storage.saveConfigFromStorage()
        val bridge = SessionBridge()
        val repository = repository(root, storage, profiles, bridge)
        val rebuilding = CountDownLatch(1)
        val release = CountDownLatch(1)
        bridge.beforeRebuild = { session ->
            if (session == 1L) {
                rebuilding.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val firstLookup = workers.submit { repository.ensureLookupQueryReady() }
            assertTrue(rebuilding.await(5, TimeUnit.SECONDS))
            profiles.activateGlobal(second.id)
            val secondRequested = CountDownLatch(1)
            val secondLookup = workers.submit {
                secondRequested.countDown()
                repository.ensureLookupQueryReady()
            }
            assertTrue(secondRequested.await(5, TimeUnit.SECONDS))
            release.countDown()
            firstLookup.get(5, TimeUnit.SECONDS)
            secondLookup.get(5, TimeUnit.SECONDS)

            repeat(3) {
                repository.lookup("猫")
                assertEquals(emptyMap<String, String>(), repository.dictionaryStyles())
            }
            assertEquals(listOf(listOf("First"), emptyList<String>()), bridge.paths.values.toList())
            assertEquals(2, bridge.creates)
        } finally {
            release.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun ensureFinishesOnTheCurrentProfileEvenWithoutAnotherQueuedLookup() = runBlocking {
        val root = temporary.newFolder()
        val profiles = ProfileRepository(root, ioDispatcher = Dispatchers.Unconfined)
        val second = profiles.createProfile("Second Japanese", "ja")
        val storage = DictionaryStorageDataSource(root, profiles)
        writeIndex(storage.typeDirectory(DictionaryType.Term).resolve("First"), "First")
        storage.saveConfigFromStorage()
        val bridge = SessionBridge()
        bridge.beforeRebuild = { session ->
            if (session == 1L) runBlocking { profiles.activateGlobal(second.id) }
        }
        val repository = repository(root, storage, profiles, bridge)

        repository.ensureLookupQueryReady()

        assertEquals(listOf(listOf("First"), emptyList<String>()), bridge.paths.values.toList())
        assertEquals(2, bridge.creates)
    }

    @Test
    fun aFailedRebuildIsRetriedBeforeReusingPersistedDictionaryChanges() {
        val root = temporary.newFolder()
        val profiles = ProfileRepository(root)
        val storage = DictionaryStorageDataSource(root, profiles)
        writeIndex(storage.typeDirectory(DictionaryType.Term).resolve("First"), "First")
        storage.saveConfigFromStorage()
        val bridge = SessionBridge()
        val repository = repository(root, storage, profiles, bridge)
        assertEquals(mapOf("First" to "session-1"), repository.dictionaryStyles())
        val failure = IOException("rebuild failed")
        bridge.beforeRebuild = { if (it == 2L) throw failure }

        assertSame(failure, assertThrows(IOException::class.java) {
            repository.setDictionaryEnabled(DictionaryType.Term, "First", false)
        })
        repeat(3) {
            repository.lookup("猫")
            assertEquals(emptyMap<String, String>(), repository.dictionaryStyles())
        }
        assertEquals(3, bridge.creates)
        assertEquals(2, bridge.styleReads)
    }

    @Test
    fun completedImportsRemainQueryableWhenALaterDownloadFailsOrIsCancelled() {
        for (failure in listOf(IOException("second download failed"), CancellationException("cancelled"))) {
            val root = temporary.newFolder()
            val profiles = ProfileRepository(root)
            val storage = DictionaryStorageDataSource(root, profiles)
            val bridge = SessionBridge()
            val remote = object : DictionaryRemoteDataSource {
                override fun fetchIndex(url: String): DictionaryIndex = error("unused")
                override fun downloadArchive(url: String): InputStream {
                    if (url == "second") throw failure
                    return archive("Imported").inputStream()
                }
            }
            val repository = repository(root, storage, profiles, bridge, remote)
            val mutations = DictionaryMutationCoordinator()
            assertEquals(emptyMap<String, String>(), repository.dictionaryStyles())

            assertSame(failure, assertThrows(failure.javaClass) {
                runBlocking {
                    mutations.runExclusive(DictionaryMutationOperation.RecommendedImport) {
                        repository.importRecommendedDictionaries(
                            listOf(recommendation("first"), recommendation("second")),
                            onDictionariesChanged = { markDictionariesChanged() },
                        )
                    }
                }
            })
            assertEquals(1L, mutations.state.value.completedChangeVersion)
            assertFalse(mutations.state.value.isInProgress)
            assertTrue(repository.loadDictionaries(DictionaryType.Term).single().isEnabled)
            repeat(3) {
                repository.lookup("猫")
                assertEquals(mapOf("Imported" to "session-2"), repository.dictionaryStyles())
            }
            assertEquals(2, bridge.creates)
            assertEquals(2, bridge.styleReads)
        }
    }

    @Test
    fun skippedImportsAndFailedDownloadsWithoutChangesKeepTheReadySession() {
        val root = temporary.newFolder()
        val profiles = ProfileRepository(root)
        val storage = DictionaryStorageDataSource(root, profiles)
        writeIndex(storage.typeDirectory(DictionaryType.Term).resolve("Imported"), "Imported")
        storage.saveConfigFromStorage()
        val bridge = SessionBridge()
        val failure = IOException("download failed")
        val remote = object : DictionaryRemoteDataSource {
            override fun fetchIndex(url: String): DictionaryIndex = error("unused")
            override fun downloadArchive(url: String): InputStream {
                if (url == "second") throw failure
                return archive("Imported").inputStream()
            }
        }
        val repository = repository(root, storage, profiles, bridge, remote)
        val originalStyles = repository.dictionaryStyles()
        var changes = 0
        assertEquals(0, repository.importRecommendedDictionaries(
            listOf(recommendation("first")), onDictionariesChanged = { changes++ },
        ))
        assertSame(failure, assertThrows(IOException::class.java) {
            repository.importRecommendedDictionaries(
                listOf(recommendation("second")), onDictionariesChanged = { changes++ },
            )
        })
        assertEquals(0, changes)
        assertSame(originalStyles, repository.dictionaryStyles())
        assertEquals(1, bridge.creates)
        assertEquals(1, bridge.styleReads)
    }

    private fun repository(
        root: File,
        storage: DictionaryStorageDataSource,
        profiles: ProfileRepository,
        bridge: SessionBridge,
        remote: DictionaryRemoteDataSource = UrlDictionaryRemoteDataSource(),
    ) = DictionaryRepository(root, storage, DictionaryImportDataSource(bridge), DictionaryLookupQueryService(bridge), remote, profiles)

    private fun recommendation(id: String) = RecommendedDictionary(id, id, DictionaryType.Term, "", downloadUrl = id)

    private fun archive(title: String): ByteArray = ByteArrayOutputStream().apply {
        ZipOutputStream(this).use { zip ->
            zip.putNextEntry(ZipEntry("index.json"))
            zip.write("""{"title":"$title","revision":"1"}""".toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()

    private class SessionBridge : DictionaryNativeBridge {
        var creates = 0
        var styleReads = 0
        val paths = linkedMapOf<Long, List<String>>()
        var beforeRebuild: (Long) -> Unit = {}

        override fun createLookupObject(languageId: String): Long = (++creates).toLong()

        override fun rebuildQuery(session: Long, termPaths: Array<String>, freqPaths: Array<String>, pitchPaths: Array<String>, kanjiPaths: Array<String>) {
            beforeRebuild(session)
            paths[session] = termPaths.map { File(it).name }
        }

        override fun getStyles(session: Long): List<DictionaryStyle> {
            styleReads++
            return paths.getValue(session).map { DictionaryStyle(it, "session-$session") }
        }

        override fun importDictionary(zipPath: String, outputDir: String, lowRam: Boolean): NativeDictionaryImportResult {
            writeIndex(File(outputDir, "Imported"), "Imported")
            return NativeDictionaryImportResult(true, "Imported", 1, 0, 0, 0, mediaCount = 0)
        }
    }

    companion object {
        private fun writeIndex(directory: File, title: String) {
            directory.mkdirs()
            directory.resolve("index.json").writeText("""{"title":"$title","revision":"1"}""")
        }
    }
}
