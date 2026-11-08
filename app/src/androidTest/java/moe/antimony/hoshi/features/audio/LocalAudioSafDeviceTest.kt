package moe.antimony.hoshi.features.audio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run the three methods separately, with a process stop between each invocation. */
@RunWith(AndroidJUnit4::class)
class LocalAudioSafDeviceTest {
    @Test
    fun prepareLinkForProcessRestart(): Unit = runBlocking {
        val fixture = RealLocalAudioFixture()
        val directory = fixture.evidenceDirectory.resolve("restart")
        assertTrue("A previous restart fixture exists at $directory", directory.mkdir())
        val repository = fixture.repository(directory)
        try {
            fixture.requirePersistedReadGrant()
            val linked = repository.importDatabase(fixture.uri, LocalAudioImportMode.Link)
            assertTrue(linked.isExternal)
            assertFalse(directory.resolve("files/Audio").exists())
            val (file, bytes) = repository.readFixtureAudio()
            assertEquals(linked.generation, file.generation)
            directory.resolve("prepared.json").writeText(JSONObject().apply {
                put("pid", Process.myPid())
                put("uri", fixture.uri.toString())
                put("generation", linked.generation)
                put("source", file.source)
                put("file", file.file)
                put("sha256", bytes.sha256())
            }.toString())
            // Leave only this scratch binding for the separate process to reopen.
        } catch (failure: Throwable) {
            try { repository.deleteDatabase() } finally { directory.deleteRecursively() }
            throw failure
        }
    }

    @Test
    fun reopenLinkAndReleaseFixtureGrant(): Unit = runBlocking {
        val fixture = RealLocalAudioFixture()
        fixture.requirePersistedReadGrant()
        val directory = fixture.evidenceDirectory.resolve("restart")
        val prepared = JSONObject(directory.resolve("prepared.json").readText())
        assertEquals(prepared.getString("uri"), fixture.uri.toString())
        assertNotEquals(prepared.getInt("pid"), Process.myPid())
        val repository = fixture.repository(directory)
        val info = requireNotNull(repository.databaseInfo())
        assertEquals(prepared.getString("generation"), info.generation)
        assertTrue(info.isExternal)
        val file = LocalAudioFile(prepared.getString("source"), prepared.getString("file"), info.generation)
        assertEquals(prepared.getString("sha256"), requireNotNull(repository.loadAudio(file)).sha256())

        val ownedGrant = fixture.evidenceDirectory.resolve("owned-grant-uri.txt")
        assertTrue("Select a test-owned database with LocalAudioValidationActivity first", ownedGrant.isFile)
        assertEquals("Only the picker fixture's own grant can be released", fixture.uri.toString(), ownedGrant.readText())
        fixture.context.contentResolver.releasePersistableUriPermission(fixture.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertFalse(fixture.hasPersistedReadGrant())
        assertTrue(ownedGrant.delete())
        directory.resolve("revoked-pid.txt").writeText(Process.myPid().toString())
    }

    @Test
    fun revokedDocumentCannotReopenAfterProcessRestart(): Unit = runBlocking {
        val fixture = RealLocalAudioFixture()
        val directory = fixture.evidenceDirectory.resolve("restart")
        val prepared = JSONObject(directory.resolve("prepared.json").readText())
        assertEquals(prepared.getString("uri"), fixture.uri.toString())
        assertNotEquals(directory.resolve("revoked-pid.txt").readText().toInt(), Process.myPid())
        assertFalse(fixture.hasPersistedReadGrant())
        val repository = fixture.repository(directory)
        try {
            assertEquals(prepared.getString("generation"), repository.databaseInfo()?.generation)
            val failure = runCatching { repository.ensureSourceConfig() }.exceptionOrNull()
            assertTrue("Expected denied access to the revoked document, got $failure", failure is SecurityException || failure is IOException)
        } finally {
            try {
                repository.deleteDatabase()
                assertNull(repository.databaseInfo())
            } finally {
                assertTrue(directory.deleteRecursively())
            }
        }
    }
}

internal class RealLocalAudioFixture {
    init {
        assumeTrue(
            "Opt in with -e localAudioRealDatabase true",
            InstrumentationRegistry.getArguments().getString("localAudioRealDatabase") == "true",
        )
    }

    val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    val evidenceDirectory = context.cacheDir.resolve("local-audio-validation").also { it.mkdirs() }
    val uri: Uri = Uri.parse(
        InstrumentationRegistry.getArguments().getString("audioDatabaseUri")
            ?: evidenceDirectory.resolve("selected-uri.txt").readText().trim(),
    ).also { check(it.scheme == "content") { "Select a database through DocumentsUI." } }

    fun repository(directory: File) = LocalAudioRepository(
        directory.resolve("files"),
        directory.resolve("state"),
        AndroidLocalAudioAccess(context.contentResolver),
        Dispatchers.IO,
    )

    fun hasPersistedReadGrant() = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    fun requirePersistedReadGrant() = assertTrue("Use LocalAudioValidationActivity to select the document", hasPersistedReadGrant())
}

internal suspend fun LocalAudioRepository.readFixtureAudio(): Pair<LocalAudioFile, ByteArray> {
    for (candidate in findAudioCandidates("食べる", "たべる")) {
        val file = requireNotNull(LocalAudioResolver.parseAudioUrl(candidate.url))
        val bytes = loadAudio(file)
        if (bytes != null && bytes.isNotEmpty()) return file to bytes
    }
    error("The selected Japanese database has no audio payload for 食べる")
}
