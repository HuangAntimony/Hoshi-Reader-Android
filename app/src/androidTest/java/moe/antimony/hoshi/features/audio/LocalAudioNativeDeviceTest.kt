package moe.antimony.hoshi.features.audio

import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.antimony.hoshi.features.anki.readAnkiAudioBytes
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalAudioNativeDeviceTest {
    @Test
    fun popupLookupReturnsLowercaseRecordingForCapitalizedTerms() = withFixture(
        seedExtraRows = { database ->
            database.execSQL("INSERT INTO entries(expression, source, file) VALUES ('hello', 'english', 'hello.mp3')")
            database.execSQL("INSERT INTO android(file, source, data) VALUES (?, ?, ?)", arrayOf("hello.mp3", "english", fixtureAudio()))
        },
    ) { file ->
        val audio = fixtureAudio()
        val repository = LocalAudioRepository(file.parentFile!!.resolve("repository"))
        check(repository.dbFile.parentFile!!.mkdirs())
        file.copyTo(repository.dbFile)
        val handler = AudioRequestHandler(
            localAudioRepository = repository,
            remoteAudioRepository = RemoteWordAudioRepository(AudioHttpClient { _, _ -> error("Unexpected remote request") }, Dispatchers.IO),
            fetchRemoteAudioList = { error("Unexpected custom audio request") },
        )

        try {
            for (term in listOf("hello", "Hello", "HELLO")) {
                val target = "hoshi-local-audio-source://get/?term=$term&reading=$term"
                val query = URLEncoder.encode(target, "UTF-8")
                val response = requireNotNull(handler.handleAudioRequest("https://appassets.androidplatform.net/audio?url=$query"))
                val body = response.data.bufferedReader().use { it.readText() }
                val candidates = Json.parseToJsonElement(body).jsonObject.getValue("audioSources").jsonArray
                assertEquals(term, 1, candidates.size)
                val url = candidates.single().jsonObject.getValue("url").jsonPrimitive.content
                val bytes = runBlocking { readAnkiAudioBytes(url, repository::loadAudio) { error("Unexpected remote audio URL") } }
                assertArrayEquals(audio, bytes)
            }
        } finally {
            runBlocking { repository.deleteDatabase() }
        }
    }

    @Test
    fun readsFrameworkDatabaseAfterCallerClosesDescriptorWithoutWritingFiles() = withFixture { file ->
        val source = "話者𠮷"
        val directory = requireNotNull(file.parentFile)
        val originalDigest = file.readBytes().sha256()
        val originalMtime = file.lastModified()
        val expectedRows = openFrameworkAudioDatabase(file).use { database ->
            frameworkAudioEntries(database, "𠮷野", "よしの").groupingBy { it }.eachCount()
        }
        assertEquals("Fixture files before JNI open", listOf(file.name), directory.list()?.toList())
        val session = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
            LocalAudioNativeDatabase.open(it.fd, 0, -1)
        }
        session.use { database ->
            assertEquals(file.length(), database.sizeBytes)
            val rows = database.queryEntries("𠮷野", "よしの")
            assertEquals(expectedRows, rows.groupingBy { it }.eachCount())
            assertEquals(4, rows.size)
            assertEquals(2, rows.count { it.expression == "𠮷野" && it.reading == "よしの" })
            assertEquals(listOf(LocalAudioEntry(source, "食べる", null, "食べる.MP3")), database.queryEntries("食べる", ""))
            assertEquals(setOf(source, "別の話者"), database.querySources().toSet())
            assertArrayEquals(fixtureAudio(), database.loadAudio(source, "𠮷野.opus"))
            assertArrayEquals(byteArrayOf(2), database.loadAudio("別の話者", "𠮷野.opus"))
            assertNull(database.loadAudio("別の話者", "欠損.opus"))
            assertTrue(database.queryEntries("見つからない", "").isEmpty())
        }
        assertEquals(originalDigest, file.readBytes().sha256())
        assertEquals(originalMtime, file.lastModified())
        assertEquals("Fixture files after JNI close", listOf(file.name), directory.list()?.toList())
    }

    @Test
    fun readsBoundedDatabaseSliceAndReusesPreparedStatements() = withFixture { file ->
        val container = File(file.parentFile, "container.bin")
        val prefix = ByteArray(137) { 42 }
        container.outputStream().use { output ->
            output.write(prefix)
            file.inputStream().use { it.copyTo(output) }
            output.write(ByteArray(512) { 99 })
        }
        ParcelFileDescriptor.open(container, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            LocalAudioNativeDatabase.open(descriptor.fd, prefix.size.toLong(), file.length()).use { database ->
                assertEquals(file.length(), database.sizeBytes)
                val preparedAtOpen = database.statistics()[2]
                assertTrue(preparedAtOpen > 0)
                repeat(12) {
                    assertEquals(3, database.queryEntries("𠮷野", "").size)
                    assertEquals(4, database.queryEntries("𠮷野", "よしの").size)
                    assertEquals(2, database.querySources().size)
                    assertArrayEquals(fixtureAudio(), database.loadAudio("話者𠮷", "𠮷野.opus"))
                }
                val (reads, bytes, prepared, queries) = database.statistics()
                assertTrue(reads > 0 && bytes > 0)
                assertEquals(preparedAtOpen, prepared)
                assertEquals(48L, queries)
            }
        }
    }

    @Test
    fun rejectsStreamingPipeBeforeReading() {
        val (reader, writer) = ParcelFileDescriptor.createPipe()
        reader.use { descriptor ->
            writer.close()
            try {
                LocalAudioNativeDatabase.open(descriptor.fd, 0, -1).use {
                    fail("A streaming descriptor was accepted as a SQLite database")
                }
            } catch (expected: IOException) {
                assertTrue("Expected rejection of the descriptor type, got $expected", expected.message.orEmpty().contains("regular file"))
            }
        }
    }

    private fun withFixture(seedExtraRows: (SQLiteDatabase) -> Unit = {}, test: (File) -> Unit) {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val directory = Files.createTempDirectory(cache.toPath(), "local-audio-native-").toFile()
        try {
            val file = directory.resolve("fixture.db")
            SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
                database.disableWriteAheadLogging()
                assertEquals("delete", DatabaseUtils.stringForQuery(database, "PRAGMA journal_mode=DELETE", null))
                database.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY, expression TEXT, reading TEXT, source TEXT, speaker TEXT, display TEXT, file TEXT)")
                database.execSQL("CREATE TABLE android(id INTEGER PRIMARY KEY, file TEXT, source TEXT, data BLOB)")
                database.execSQL("CREATE INDEX expression_idx ON entries(expression, reading, source)")
                database.execSQL("CREATE INDEX reading_idx ON entries(reading)")
                database.execSQL("CREATE INDEX audio_idx ON android(file, source)")
                database.execSQL("""
                    INSERT INTO entries(expression, reading, source, display, file) VALUES
                    ('𠮷野', 'よしの', '話者𠮷', '発音𠮷', '𠮷野.opus'),
                    ('𠮷野', 'よしの', '話者𠮷', '発音𠮷', '𠮷野.opus'),
                    ('吉野', 'よしの', '別の話者', NULL, '吉野.ogg'),
                    ('𠮷野', NULL, '話者𠮷', NULL, '𠮷野.mp3'),
                    ('食べる', NULL, '話者𠮷', NULL, '食べる.MP3'),
                    ('対象外', NULL, '無音', NULL, '対象外.txt')
                """.trimIndent())
                database.execSQL("INSERT INTO android(file, source, data) VALUES (?, ?, ?)", arrayOf("𠮷野.opus", "話者𠮷", fixtureAudio()))
                database.execSQL("INSERT INTO android(file, source, data) VALUES (?, ?, ?)", arrayOf("𠮷野.opus", "別の話者", byteArrayOf(2)))
                seedExtraRows(database)
            }
            test(file)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun fixtureAudio() = ByteArray(96 * 1024) { index -> (index * 19 xor (index ushr 7)).toByte() }
}

internal fun openFrameworkAudioDatabase(file: File): SQLiteDatabase = SQLiteDatabase.openDatabase(
    file.absolutePath,
    null,
    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
)

internal fun frameworkAudioEntries(database: SQLiteDatabase, term: String, reading: String): List<LocalAudioEntry> {
    val columns = arrayOf("source", "expression", "reading", "file", "display")
    val selection = if (reading.isBlank()) "expression = ?" else "expression = ? OR reading = ?"
    val args = if (reading.isBlank()) arrayOf(term) else arrayOf(term, reading)
    return database.query("entries", columns, selection, args, null, null, null).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(LocalAudioEntry(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4).orEmpty()))
            }
        }
    }
}

internal fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this)
    .joinToString("") { "%02x".format(it) }
