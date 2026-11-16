package moe.antimony.hoshi.features.sasayaki

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.SasayakiPlaybackData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SasayakiPlaybackRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun gainOnlyWritePreservesFieldsFromOtherPlatforms() = runTest {
        val files = temporaryFolder.newFolder()
        val book = files.resolve("book").apply { mkdirs() }
        val repository = BookSasayakiPlaybackRepository(book, BookRepository(files, Dispatchers.Unconfined))
        val original = Json.parseToJsonElement(
            """{"lastPosition":24.5,"audioBookmark":"dGVzdA==","audioUri":"content://local/audio","modified":1234}""",
        ).jsonObject
        val sidecar = book.resolve("sasayaki_playback.json").apply { writeText(original.toString()) }

        repository.saveVolumeGainDb(6)

        assertEquals(original + ("volumeGainDb" to JsonPrimitive(6)), Json.parseToJsonElement(sidecar.readText()).jsonObject)
    }

    @Test
    fun reopenLoadsSavedGainWhileBookmarkSaveIsStillPending() = runTest {
        val files = temporaryFolder.newFolder()
        val root = files.resolve("book").apply { mkdirs() }
        val books = BookRepository(files, Dispatchers.Unconfined)
        val repository = BookSasayakiPlaybackRepository(root, books)
        repository.save(SasayakiPlaybackData(lastPosition = 24.5))
        repository.saveVolumeGainDb(-6)
        val finishBookmarkSave = CompletableDeferred<Unit>()
        val bookmarkSave = launch { finishBookmarkSave.await() }
        books.trackReaderSave(root, bookmarkSave)
        val reopen = async { BookSasayakiPlaybackRepository(root, books).load() }
        runCurrent()

        assertTrue(reopen.isCompleted)
        finishBookmarkSave.complete(Unit)
        val playback = requireNotNull(reopen.await())
        assertEquals(-6, playback.volumeGainDb)
        assertEquals(24.5, playback.lastPosition, 0.0)
    }
}
