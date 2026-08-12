package moe.antimony.hoshi.features.audio

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

class LocalAudioRepositoryTest {
    @Test
    fun exposesPrivateDatabasePath() {
        val filesDir = Files.createTempDirectory("hoshi-local-audio-internal").toFile()
        val repository = LocalAudioRepository(filesDir)

        assertEquals(filesDir.resolve(AudioSettings.LocalAudioPath), repository.dbFile)
        assertNull(repository.databaseSizeBytes())
    }

    @Test
    fun readsPrivateDatabaseSizeWhenPresent() {
        val filesDir = Files.createTempDirectory("hoshi-local-audio-internal").toFile()
        val database = filesDir.resolve(AudioSettings.LocalAudioPath)
        database.parentFile?.mkdirs()
        database.writeBytes("private database".toByteArray())

        val repository = LocalAudioRepository(filesDir)

        assertEquals(database, repository.dbFile)
        assertEquals(database.length(), repository.databaseSizeBytes())
    }

    @Test
    fun replacesDatabaseOnlyAfterCompleteCopy() {
        val filesDir = Files.createTempDirectory("hoshi-local-audio-internal").toFile()
        val repository = LocalAudioRepository(filesDir)
        val progress = mutableListOf<LocalAudioImportProgress>()

        val copied = repository.replacePrivateDatabase(
            input = ByteArrayInputStream("new database".toByteArray()),
            expectedSizeBytes = "new database".length.toLong(),
            onProgress = { progress += it },
        )

        assertEquals("new database".length.toLong(), copied)
        assertEquals("new database", repository.dbFile.readText())
        assertEquals(copied, repository.databaseSizeBytes())
        assertTrue(progress.any { it.copiedBytes == copied && it.totalBytes == copied })
        assertNull(repository.dbFile.parentFile?.resolve("${repository.dbFile.name}.tmp")?.takeIf { it.exists() })
    }

    @Test
    fun keepsExistingDatabaseWhenCopyIsIncomplete() {
        val filesDir = Files.createTempDirectory("hoshi-local-audio-internal").toFile()
        val repository = LocalAudioRepository(filesDir)
        repository.dbFile.parentFile?.mkdirs()
        repository.dbFile.writeText("old database")

        val result = runCatching {
            repository.replacePrivateDatabase(
                input = ByteArrayInputStream("short".toByteArray()),
                expectedSizeBytes = 10,
            )
        }

        assertTrue(result.isFailure)
        assertEquals("old database", repository.dbFile.readText())
        assertNull(repository.dbFile.parentFile?.resolve("${repository.dbFile.name}.tmp")?.takeIf { it.exists() })
    }

    @Test
    fun updatingSourceOrderPreservesDisabledSources() {
        val filesDir = Files.createTempDirectory("hoshi-local-audio-source-order").toFile()
        val configFile = filesDir.resolve(AudioSettings.LocalAudioSourceConfigPath)
        configFile.parentFile?.mkdirs()
        configFile.writeText(
            """{"version":1,"sourceOrder":["nhk16","forvo"],"disabledSources":["forvo"]}""",
        )
        val repository = LocalAudioRepository(filesDir)

        val updated = repository.updateSourceOrder(listOf("forvo", "nhk16"))

        assertEquals(listOf("forvo", "nhk16"), updated.sourceOrder)
        assertEquals(setOf("forvo"), updated.disabledSources)
    }

    @Test
    fun sourceEnabledUpdatesPersistAndAllowAllSourcesToBeDisabled() {
        val filesDir = Files.createTempDirectory("hoshi-local-audio-source-enabled").toFile()
        val configFile = filesDir.resolve(AudioSettings.LocalAudioSourceConfigPath)
        configFile.parentFile?.mkdirs()
        configFile.writeText(
            """{"version":1,"sourceOrder":["nhk16","forvo"],"disabledSources":[]}""",
        )
        val repository = LocalAudioRepository(filesDir)

        repository.updateSourceEnabled("nhk16", enabled = false)
        val allDisabled = repository.updateSourceEnabled("forvo", enabled = false)

        assertEquals(setOf("nhk16", "forvo"), allDisabled.disabledSources)

        val reenabled = repository.updateSourceEnabled("forvo", enabled = true)
        val persisted = Json.decodeFromString<LocalAudioSourceConfig>(configFile.readText())

        assertEquals(setOf("nhk16"), reenabled.disabledSources)
        assertEquals(reenabled, persisted)
    }
}
