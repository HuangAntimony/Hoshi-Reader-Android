package moe.antimony.hoshi.epub

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import moe.antimony.hoshi.features.sync.Timestamped
import org.junit.Assert.*
import org.junit.Test

class SyncSidecarConversionTest {
    @Test fun legacyHighlightsKeepCreationStampsAndDeletedIdsCannotReturn() = runBlocking {
        val root = Files.createTempDirectory("highlights").toFile()
        val id = "80CC1266-83A1-4A41-89E5-76EF980BDB66"
        root.resolve("highlights.json").writeText("""[{"id":"$id","character":42,"offset":3,"text":"漢字","color":"pink","createdAt":0}]""")
        val source = BookSidecarDataSource()
        val highlight = source.loadHighlights(root).single()
        assertEquals(978307200000L, source.loadHighlightRecords(root).getValue(id).modified)
        source.saveHighlights(root, listOf(highlight))
        assertTrue(Json.parseToJsonElement(root.resolve("highlights.json").readText()) is JsonObject)
        assertEquals(978307200000L, source.loadHighlightRecords(root).getValue(id).modified)
        source.saveHighlights(root, emptyList())
        source.saveHighlights(root, listOf(highlight.copy(color = HighlightColor.Blue)))
        assertNull(source.loadHighlightRecords(root).getValue(id).value)
        root.deleteRecursively()
        Unit
    }

    @Test fun legacyShelvesMoveMembershipsToMetadataWithBaselineStamps() = runBlocking {
        val files = Files.createTempDirectory("shelves").toFile()
        val repository = BookRepository(files)
        val root = repository.createBookDirectory("book")
        val id = "80CC1266-83A1-4A41-89E5-76EF980BDB66"
        repository.saveMetadata(root, BookMetadata(id, "Book", folder = "book", lastAccess = 0.0))
        files.resolve("Books/shelves.json").writeText("""[{"name":"で","bookIds":["$id"]},{"name":"Empty","bookIds":[]}]""")
        assertEquals(listOf(BookShelf("で", listOf(id)), BookShelf("Empty", emptyList())), repository.loadShelves())
        assertEquals(mapOf("で" to Timestamped(0, true)), repository.loadMetadata(root)!!.shelves)
        assertEquals(mapOf("で" to Timestamped<Int?>(0, 0), "Empty" to Timestamped<Int?>(0, 1)), repository.loadShelfList())
        repository.saveShelves(listOf(BookShelf("Empty", emptyList())))
        assertNull(repository.loadShelfList().getValue("で").value)
        assertEquals(false, repository.loadMetadata(root)!!.shelves!!.getValue("で").value)
        files.deleteRecursively()
        Unit
    }

    @Test fun changingOnlyLocalAudioSelectionPreservesPlaybackStamp() = runBlocking {
        val files = Files.createTempDirectory("playback").toFile()
        val repository = BookRepository(files)
        val root = repository.createBookDirectory("book")
        val stored = SasayakiPlaybackData(5.0, modified = 42)
        repository.applySasayakiPlayback(root, stored)
        repository.saveSasayakiPlayback(root, stored.copy(audioUri = "content://audio/1"))
        assertEquals(42L, repository.loadSasayakiPlayback(root)!!.modified)
        repository.saveSasayakiPlayback(root, stored.copy(lastPosition = 6.0))
        assertTrue(repository.loadSasayakiPlayback(root)!!.modified!! > 42)
        files.deleteRecursively()
        Unit
    }
}
