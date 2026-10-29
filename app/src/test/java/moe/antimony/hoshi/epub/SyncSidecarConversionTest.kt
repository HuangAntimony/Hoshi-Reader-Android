package moe.antimony.hoshi.epub

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import moe.antimony.hoshi.features.sync.Timestamped
import org.junit.Assert.*
import org.junit.Test

class SyncSidecarConversionTest {
    @Test fun collidingShelfNamesAreRejectedBeforeMembershipOrOrderWrites() = runBlocking {
        val files = Files.createTempDirectory("shelf-name-collision").toFile()
        try {
            val repository = BookRepository(files)
            val root = repository.createBookDirectory("book")
            val id = "80CC1266-83A1-4A41-89E5-76EF980BDB66"
            repository.saveMetadata(root, BookMetadata(id, "Book", folder = "book", lastAccess = 0.0))
            repository.saveShelves(listOf(BookShelf("で", emptyList()), BookShelf("Other", listOf(id))))
            val shelfBytes = files.resolve("Books/shelves.json").readBytes()
            val metadataBytes = root.resolve("metadata.json").readBytes()
            var notifications = 0
            repository.onBookChange = { _, _ -> notifications++ }
            repository.onShelvesChange = { notifications++ }

            val failure = runCatching {
                repository.saveShelves(listOf(BookShelf("で", emptyList()), BookShelf("て\u3099", listOf(id))))
            }.exceptionOrNull()

            assertTrue("Canonical collisions must be rejected", failure is IllegalArgumentException)
            assertArrayEquals(shelfBytes, files.resolve("Books/shelves.json").readBytes())
            assertArrayEquals(metadataBytes, root.resolve("metadata.json").readBytes())
            assertEquals(0, notifications)
            assertEquals(
                listOf(BookShelf("で", emptyList()), BookShelf("Other", listOf(id))),
                BookRepository(files).loadShelves(),
            )
        } finally {
            files.deleteRecursively()
        }
    }

    @Test fun createdAndRenamedShelfNamesAgreeWithStorageAfterReload() = runBlocking {
        val files = Files.createTempDirectory("shelf-name-roundtrip").toFile()
        try {
            val repository = BookRepository(files)
            val root = repository.createBookDirectory("book")
            val id = "80CC1266-83A1-4A41-89E5-76EF980BDB66"
            repository.saveMetadata(root, BookMetadata(id, "Book", folder = "book", lastAccess = 0.0))
            val secondRoot = repository.createBookDirectory("second-book")
            val secondId = "8F6AA9BE-E66F-4670-B4B0-BB9BCE630D58"
            repository.saveMetadata(secondRoot, BookMetadata(secondId, "Second book", folder = "second-book", lastAccess = 0.0))
            repository.saveShelves(listOf(BookShelf("Other", listOf(id, secondId))))
            val created = requireNotNull(moe.antimony.hoshi.features.bookshelf.createShelfAndMoveBooksList(
                repository.loadShelves(), " て\u3099 ", setOf(id),
            ))
            repository.saveShelves(created)
            assertEquals(listOf(BookShelf("Other", listOf(secondId)), BookShelf("で", listOf(id))), created)
            assertEquals(created, BookRepository(files).loadShelves())

            val renamed = moe.antimony.hoshi.features.bookshelf.renameShelfList(created, "て\u3099", " Cafe\u0301 ")
            repository.saveShelves(renamed)
            assertEquals(listOf(BookShelf("Other", listOf(secondId)), BookShelf("Café", listOf(id))), renamed)
            assertEquals(renamed, BookRepository(files).loadShelves())
            assertNull(repository.loadShelfList().getValue("で").value)
            assertEquals(false, repository.loadMetadata(root)!!.shelves!!.getValue("で").value)
            assertEquals(true, repository.loadMetadata(root)!!.shelves!!.getValue("Café").value)

            val recreated = requireNotNull(moe.antimony.hoshi.features.bookshelf.createShelfAndMoveBooksList(
                BookRepository(files).loadShelves(), "て\u3099", setOf(secondId),
            ))
            repository.saveShelves(recreated)
            assertEquals(
                listOf(BookShelf("Other", emptyList()), BookShelf("Café", listOf(id)), BookShelf("で", listOf(secondId))),
                recreated,
            )
            assertEquals(recreated, BookRepository(files).loadShelves())
            assertEquals(false, repository.loadMetadata(root)!!.shelves!!.getValue("で").value)
            assertEquals(true, repository.loadMetadata(secondRoot)!!.shelves!!.getValue("で").value)
        } finally {
            files.deleteRecursively()
        }
    }

    @Test fun renamingFirstShelfKeepsOrderMembershipAndDeletionAcrossReload() = runBlocking {
        val files = Files.createTempDirectory("shelf-rename").toFile()
        try {
            val repository = BookRepository(files)
            val root = repository.createBookDirectory("book")
            val id = "80CC1266-83A1-4A41-89E5-76EF980BDB66"
            repository.saveMetadata(root, BookMetadata(id, "Book", folder = "book", lastAccess = 0.0))
            repository.saveShelves(listOf(BookShelf("Manga", listOf(id)), BookShelf("Novels", emptyList())))
            val renamed = moe.antimony.hoshi.features.bookshelf.renameShelfList(repository.loadShelves(), "Manga", "Mystery")
            repository.saveShelves(renamed)
            val reloaded = BookRepository(files)
            assertEquals(listOf(BookShelf("Mystery", listOf(id)), BookShelf("Novels", emptyList())), reloaded.loadShelves())
            assertNull(reloaded.loadShelfList().getValue("Manga").value)
            assertEquals(false, reloaded.loadMetadata(root)!!.shelves!!.getValue("Manga").value)
            val stamps = reloaded.loadShelfList()
            reloaded.saveShelves(reloaded.loadShelves())
            assertEquals(stamps, reloaded.loadShelfList())
            reloaded.saveShelves(reloaded.loadShelves().reversed())
            assertEquals(listOf("Novels", "Mystery"), reloaded.loadShelves().map { it.name })
        } finally {
            files.deleteRecursively()
        }
    }

    @Test fun savedSasayakiMatchesIncludeTheImagesArrayRequiredByIos() = runBlocking {
        val root = Files.createTempDirectory("sasayaki-images").toFile()
        val source = BookSidecarDataSource()
        val cue = SasayakiMatch("1", 1.0, 2.0, "本文", 0, 0, 2)
        SasayakiMatchSource.entries.forEach { matchSource ->
            val match = SasayakiMatchData(listOf(cue), 0, matchSource)
            source.saveSasayakiMatch(root, match)
            val json = Json.parseToJsonElement(root.resolve("sasayaki_match.json").readText()).jsonObject
            assertEquals(JsonArray(emptyList()), json["images"])
            assertEquals(match, source.loadSasayakiMatch(root))
        }
        root.deleteRecursively()
        Unit
    }

    @Test fun legacySasayakiMatchesGainImagesWhenSavedAndIosImagesSurviveRoundTrip() = runBlocking {
        val root = Files.createTempDirectory("sasayaki-legacy-images").toFile()
        val source = BookSidecarDataSource()
        val file = root.resolve("sasayaki_match.json")
        val images = Json.parseToJsonElement("""[{"chapterIndex":0,"imageIndex":1,"offset":42}]""") as JsonArray
        val cases = listOf(
            """{"matches":[],"unmatched":0}""" to JsonArray(emptyList()),
            """{"matches":[],"unmatched":0,"images":$images}""" to images,
        )
        for ((stored, expected) in cases) {
            file.writeText(stored)
            val match = source.loadSasayakiMatch(root)!!
            source.saveSasayakiMatch(root, match)
            assertEquals(expected, Json.parseToJsonElement(file.readText()).jsonObject["images"])
        }
        root.deleteRecursively()
        Unit
    }

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
