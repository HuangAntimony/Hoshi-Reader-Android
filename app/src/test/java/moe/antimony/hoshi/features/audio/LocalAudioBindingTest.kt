package moe.antimony.hoshi.features.audio

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalAudioBindingTest {
    @Test fun linkingKeepsOriginalAndPersistsOnlyAReference() = runBlocking {
        val f = Fixture()
        val info = f.repository.importDatabase("content://audio/one", LocalAudioImportMode.Link)
        assertTrue(info.isExternal)
        assertEquals(listOf("content://audio/one"), f.access.acquired)
        assertEquals(0, f.access.streamOpens)
        assertFalse(f.root.resolve("Audio").exists())
        assertEquals("first", f.repository.loadAudio(LocalAudioFile("nhk16", "clip.mp3"))!!.decodeToString())
        assertEquals(info, f.restarted().databaseInfo())
        assertEquals("first", f.restarted().loadAudio(LocalAudioFile("nhk16", "clip.mp3"))!!.decodeToString())
    }

    @Test fun copyStillWorksWithoutPersistentPermissionAndSurvivesSourceRemoval() = runBlocking {
        val f = Fixture()
        val progress = mutableListOf<LocalAudioImportProgress>()
        val info = f.repository.importDatabase("content://audio/one", LocalAudioImportMode.Copy, progress::add)
        assertFalse(info.isExternal)
        assertTrue(f.access.acquired.isEmpty())
        assertEquals(1, f.access.streamOpens)
        assertEquals(5L, progress.last().copiedBytes)
        f.access.documents.clear()
        assertEquals("first", f.restarted().loadAudio(LocalAudioFile("nhk16", "clip.mp3"))!!.decodeToString())
    }

    @Test fun failedReplacementKeepsOldCopyAndReleasesOnlyNewGrant() = runBlocking {
        val f = Fixture()
        val before = f.repository.importDatabase("content://audio/one", LocalAudioImportMode.Copy)
        f.access.documents["content://audio/broken"] = "invalid"
        val result = runCatching { f.repository.importDatabase("content://audio/broken", LocalAudioImportMode.Link) }
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(before, f.repository.databaseInfo())
        assertEquals(listOf("content://audio/broken"), f.access.released)
        assertEquals("first", f.repository.loadAudio(LocalAudioFile("nhk16", "clip.mp3"))!!.decodeToString())
        f.access.grants += "content://audio/broken"
        f.access.released.clear()
        runCatching { f.repository.importDatabase("content://audio/broken", LocalAudioImportMode.Link) }
        assertTrue(f.access.released.isEmpty())
    }

    @Test fun incompleteOrCancelledCopyLeavesActiveDatabaseAndNoStagingFile() = runBlocking {
        val f = Fixture()
        val before = f.repository.importDatabase("content://audio/one", LocalAudioImportMode.Copy)
        f.access.declaredSize = 100
        assertTrue(runCatching { f.repository.importDatabase("content://audio/one", LocalAudioImportMode.Copy) }.isFailure)
        assertEquals(before, f.repository.databaseInfo())
        f.access.declaredSize = null
        val result = runCatching {
            f.repository.importDatabase("content://audio/one", LocalAudioImportMode.Copy) { throw CancellationException("cancel copy") }
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(before, f.restarted().databaseInfo())
        assertEquals(1, f.root.resolve("Audio").listFiles()!!.count { it.extension == "db" })
        assertTrue(f.root.resolve("Audio").listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test fun queriesReuseSessionAndOldCandidatesCannotReadNewDatabase() = runBlocking {
        val f = Fixture()
        f.repository.importDatabase("content://audio/one", LocalAudioImportMode.Link)
        val candidate = f.repository.findAudioCandidates("食べる", "タベル").single()
        val opens = f.access.databaseOpens
        repeat(8) {
            assertEquals(1, f.repository.findAudioCandidates("食べる", "たべる").size)
            assertNotNull(f.repository.loadAudio(LocalAudioResolver.parseAudioUrl(candidate.url)!!))
        }
        assertEquals(opens, f.access.databaseOpens)
        f.repository.importDatabase("content://audio/two", LocalAudioImportMode.Link)
        assertNull(f.repository.loadAudio(LocalAudioResolver.parseAudioUrl(candidate.url)!!))
        assertEquals("second", f.repository.loadAudio(LocalAudioFile("nhk16", "clip.mp3"))!!.decodeToString())
    }

    @Test fun unlinkNeverDeletesOriginalOrResurrectsRetainedPrivateCopy() = runBlocking {
        val f = Fixture()
        f.repository.importDatabase("content://audio/one", LocalAudioImportMode.Copy)
        f.repository.importDatabase("content://audio/two", LocalAudioImportMode.Link)
        assertTrue(f.repository.hasRetainedPrivateDatabase())
        f.repository.deleteDatabase()
        assertNull(f.restarted().databaseInfo())
        assertEquals("second", f.access.documents["content://audio/two"])
        assertTrue(f.repository.hasRetainedPrivateDatabase())
        f.repository.deleteRetainedPrivateDatabase()
        assertFalse(f.repository.hasRetainedPrivateDatabase())
        assertTrue(f.root.resolve("Audio").listFiles().orEmpty().none { it.extension == "db" })
    }

    @Test fun reopeningChangedSourceFailsInsteadOfReadingNewContentWithOldBinding() = runBlocking {
        val f = Fixture()
        val info = f.repository.importDatabase("content://audio/one", LocalAudioImportMode.Link)
        f.access.documents["content://audio/one"] = "second"
        assertTrue(runCatching { f.restarted().ensureSourceConfig() }.exceptionOrNull() is IOException)
        assertEquals(info, f.restarted().databaseInfo())
    }

    @Test fun sourceOrderingAndDisabledSourcesPersistWithBinding() = runBlocking {
        val f = Fixture()
        f.repository.importDatabase("content://audio/one", LocalAudioImportMode.Link)
        f.repository.updateSourceEnabled("nhk16", false)
        f.repository.updateSourceOrder(listOf("forvo", "nhk16"))
        assertEquals(listOf("forvo", "nhk16"), f.restarted().ensureSourceConfig().sourceOrder)
        assertEquals(setOf("nhk16"), f.restarted().ensureSourceConfig().disabledSources)
        assertTrue(f.restarted().findAudioCandidates("食べる", "たべる").isEmpty())
    }

    @Test fun legacyPrivateDatabaseIsAdoptedWithoutCopying() = runBlocking {
        val f = Fixture()
        val legacy = f.root.resolve(AudioSettings.LocalAudioPath)
        requireNotNull(legacy.parentFile).mkdirs()
        legacy.writeText("first")
        f.root.resolve(AudioSettings.LocalAudioSourceConfigPath).writeText("""{"version":1,"sourceOrder":["forvo","nhk16"],"disabledSources":["forvo"]}""")
        assertFalse(f.repository.databaseInfo()!!.isExternal)
        assertEquals(listOf("forvo", "nhk16"), f.repository.ensureSourceConfig().sourceOrder)
        assertEquals(0, f.access.streamOpens)
        assertEquals("first", legacy.readText())
    }

    @Test fun malformedLegacySourceConfigDoesNotPreventAdoptionOrReplacement() = runBlocking {
        val f = Fixture()
        val legacy = f.root.resolve(AudioSettings.LocalAudioPath)
        requireNotNull(legacy.parentFile).mkdirs()
        legacy.writeText("first")
        f.root.resolve(AudioSettings.LocalAudioSourceConfigPath).writeText("{broken")
        assertFalse(f.repository.databaseInfo()!!.isExternal)
        assertEquals(setOf("nhk16", "forvo"), f.repository.ensureSourceConfig().sourceOrder.toSet())
        assertEquals("first", legacy.readText())
        assertTrue(f.repository.importDatabase("content://audio/two", LocalAudioImportMode.Link).isExternal)
        assertTrue(f.repository.hasRetainedPrivateDatabase())
    }

    @Test fun restartCleansAbandonedCopiesButKeepsActiveRetainedAndUnrelatedFiles() = runBlocking {
        val f = Fixture()
        f.repository.importDatabase("content://audio/one", LocalAudioImportMode.Copy)
        f.repository.importDatabase("content://audio/two", LocalAudioImportMode.Link)
        val directory = f.root.resolve("Audio")
        val retained = directory.listFiles()!!.single { it.extension == "db" }
        val unfinished = directory.resolve("android-00000000-0000-0000-0000-000000000001.db.tmp").apply { writeText("partial") }
        val unpublished = directory.resolve("android-00000000-0000-0000-0000-000000000002.db").apply { writeText("first") }
        val unrelated = directory.resolve("notes.db").apply { writeText("keep") }
        f.restarted().databaseInfo()
        assertTrue(retained.isFile)
        assertTrue(unrelated.isFile)
        assertFalse(unfinished.exists())
        assertFalse(unpublished.exists())
        assertTrue(f.restarted().hasRetainedPrivateDatabase())
    }

    private class Fixture {
        val root = Files.createTempDirectory("local-audio-repository").toFile()
        val state = root.resolve("binding")
        val access = FakeAccess()
        val repository = restarted()
        fun restarted() = LocalAudioRepository(root, state, access, Dispatchers.Unconfined)
    }

    private class FakeAccess : LocalAudioAccess {
        val documents = mutableMapOf("content://audio/one" to "first", "content://audio/two" to "second")
        val grants = mutableSetOf<String>()
        val acquired = mutableListOf<String>()
        val released = mutableListOf<String>()
        var streamOpens = 0
        var databaseOpens = 0
        var declaredSize: Long? = null
        override fun validateDocument(uri: String) = "android.db"
        override fun documentSize(uri: String) = declaredSize ?: documents.getValue(uri).length.toLong()
        override fun openStream(uri: String): InputStream { streamOpens++; return ByteArrayInputStream(documents.getValue(uri).toByteArray()) }
        override fun takeReadPermission(uri: String): Boolean = grants.add(uri).also { if (it) acquired += uri }
        override fun releaseReadPermission(uri: String) { grants.remove(uri); released += uri }
        override fun openDocument(uri: String) = database(documents.getValue(uri))
        override fun openPrivate(file: File) = database(file.readText())
        private fun database(content: String): LocalAudioDatabaseSession {
            databaseOpens++
            if (content == "invalid") throw IOException("unsupported fixture")
            return object : LocalAudioDatabaseSession {
                override val sizeBytes = content.length.toLong()
                override val fingerprint = content
                override fun queryEntries(term: String, reading: String) = listOf(LocalAudioEntry("nhk16", "食べる", "たべる", "clip.mp3"))
                override fun querySources() = listOf("nhk16", "forvo")
                override fun loadAudio(source: String, file: String) = content.toByteArray()
                override fun close() = Unit
            }
        }
    }
}
