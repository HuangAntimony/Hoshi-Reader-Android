package moe.antimony.hoshi.epub


import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.nio.file.Files
import java.util.UUID

class BookMetadataStorageTest {
    @Test
    fun saveMetadataWritesIosCompatibleMetadataJson() = runBlocking {
        val storage = BookStorage(Files.createTempDirectory("hoshi-metadata").toFile())
        val bookRoot = storage.createBookDirectory("book-a")
        val bookId = UUID.randomUUID().toString()
        val metadata = BookMetadata(
            id = bookId,
            title = "屍人荘の殺人",
            epub = "book-a.epub",
            renamedTitle = "Custom Shelf Title",
            cover = "Books/book-a/cover.jpg",
            folder = "book-a",
            lastAccess = 798720000.0,
        )

        storage.saveMetadata(bookRoot, metadata)

        val saved = Json.parseToJsonElement(bookRoot.resolve("metadata.json").readText()).jsonObject
        assertEquals(bookId, saved.getValue("id").jsonPrimitive.content)
        UUID.fromString(saved.getValue("id").jsonPrimitive.content)
        assertEquals("屍人荘の殺人", saved.getValue("title").jsonPrimitive.content)
        assertEquals("book-a.epub", saved.getValue("epub").jsonPrimitive.content)
        assertEquals("Custom Shelf Title", saved.getValue("renamedTitle").jsonPrimitive.content)
        assertEquals("Books/book-a/cover.jpg", saved.getValue("cover").jsonPrimitive.content)
        assertEquals("book-a", saved.getValue("folder").jsonPrimitive.content)
        assertEquals(798720000.0, saved.getValue("lastAccess").jsonPrimitive.double, 0.0)
    }

    @Test
    fun metadataWithoutRenamedTitleStillLoadsFromOldSidecar() = runBlocking {
        val storage = BookStorage(Files.createTempDirectory("hoshi-metadata-old-title").toFile())
        val bookRoot = storage.createBookDirectory("book")
        bookRoot.resolve("metadata.json").writeText(
            """
            {
                "id": "00000000-0000-0000-0000-000000000001",
                "title": "Original Title",
                "cover": null,
                "folder": "book",
                "lastAccess": 42.0
            }
            """.trimIndent(),
        )

        val metadata = requireNotNull(storage.loadMetadata(bookRoot))

        assertEquals("Original Title", metadata.title)
        assertEquals(null, metadata.epub)
        assertEquals(null, metadata.renamedTitle)
        assertEquals("Original Title", metadata.displayTitle)
    }

    @Test
    fun loadBookEntriesMigratesLegacyExtractedBookToPackedEpub() = runBlocking {
        val storage = BookStorage(Files.createTempDirectory("hoshi-packed-migration").toFile())
        val root = storage.createBookDirectory("legacy-book")
        writeMinimalExtractedEpub(root, title = "Legacy Book")
        root.resolve("cover.jpg").writeBytes(byteArrayOf(9, 8, 7))
        root.resolve("bookmark.json").writeText("""{"chapterIndex":0,"progress":0.0,"characterCount":0}""")
        val id = UUID.randomUUID().toString()
        storage.saveMetadata(
            root,
            BookMetadata(
                id = id,
                title = "Legacy Book",
                cover = "Books/legacy-book/cover.jpg",
                folder = "legacy-book",
                lastAccess = 1.0,
            ),
        )

        val entry = storage.loadBookEntries().single()

        assertEquals("legacy-book.epub", entry.metadata.epub)
        assertEquals("legacy-book.epub", storage.loadMetadata(root)?.epub)
        assertTrue(root.resolve("legacy-book.epub").isFile)
        assertTrue(root.resolve("metadata.json").isFile)
        assertTrue(root.resolve("bookmark.json").isFile)
        assertTrue(root.resolve("cover.jpg").isFile)
        assertEquals(listOf(9, 8, 7), root.resolve("cover.jpg").readBytes().map(Byte::toInt))
        assertFalse(root.resolve("META-INF").exists())
        assertFalse(root.resolve("OPS").exists())
        ZipFile(root.resolve("legacy-book.epub")).use { zip ->
            val first = zip.entries().nextElement()
            assertEquals("mimetype", first.name)
            assertEquals(ZipEntry.STORED, first.method)
            assertEquals("application/epub+zip", zip.getInputStream(first).readBytes().decodeToString())
        }
    }

    @Test
    fun loadBookEntriesMigratesLegacyBookWithoutPackingOrDeletingSasayakiAudio() = runBlocking {
        val storage = BookStorage(Files.createTempDirectory("hoshi-packed-migration-sasayaki").toFile())
        val root = storage.createBookDirectory("legacy-book")
        writeMinimalExtractedEpub(root, title = "Legacy Book")
        root.resolve("Sasayaki").mkdirs()
        root.resolve("Sasayaki/sasayaki_audio.m4b").writeBytes(byteArrayOf(1, 2, 3))
        root.resolve("sasayaki_playback.json").writeText("""{"lastPosition":12.0,"audioFileName":"sasayaki_audio.m4b"}""")
        storage.saveMetadata(
            root,
            BookMetadata(
                id = UUID.randomUUID().toString(),
                title = "Legacy Book",
                cover = null,
                folder = "legacy-book",
                lastAccess = 1.0,
            ),
        )

        storage.loadBookEntries().single()

        assertTrue(root.resolve("legacy-book.epub").isFile)
        assertTrue(root.resolve("Sasayaki/sasayaki_audio.m4b").isFile)
        assertTrue(root.resolve("sasayaki_playback.json").isFile)
        ZipFile(root.resolve("legacy-book.epub")).use { zip ->
            assertFalse(zip.entries().asSequence().any { it.name.startsWith("Sasayaki/") })
            assertFalse(zip.entries().asSequence().any { it.name == "sasayaki_playback.json" })
        }
    }

    @Test
    fun loadBookEntriesLeavesLegacyBookUntouchedWhenPackedMigrationCannotParse() = runBlocking {
        val storage = BookStorage(Files.createTempDirectory("hoshi-packed-migration-fail").toFile())
        val root = storage.createBookDirectory("broken-book")
        root.resolve("OPS/text").mkdirs()
        root.resolve("OPS/text/chapter.xhtml").writeText("<html><body>Broken</body></html>")
        storage.saveMetadata(
            root,
            BookMetadata(
                id = UUID.randomUUID().toString(),
                title = "Broken Book",
                cover = null,
                folder = "broken-book",
                lastAccess = 1.0,
            ),
        )

        val entry = storage.loadBookEntries().single()

        assertEquals(null, entry.metadata.epub)
        assertEquals(null, storage.loadMetadata(root)?.epub)
        assertFalse(root.resolve("broken-book.epub").exists())
        assertTrue(root.resolve("OPS/text/chapter.xhtml").isFile)
    }

    @Test
    fun loadBookEntriesDoesNotTrustExistingPackedEpubUntilItCanParse() = runBlocking {
        val storage = BookStorage(Files.createTempDirectory("hoshi-packed-existing-corrupt").toFile())
        val root = storage.createBookDirectory("legacy-book")
        writeMinimalExtractedEpub(root, title = "Legacy Book")
        root.resolve("legacy-book.epub").writeText("not an epub")
        storage.saveMetadata(
            root,
            BookMetadata(
                id = UUID.randomUUID().toString(),
                title = "Legacy Book",
                cover = null,
                folder = "legacy-book",
                lastAccess = 1.0,
            ),
        )

        val entry = storage.loadBookEntries().single()

        assertEquals(null, entry.metadata.epub)
        assertEquals(null, storage.loadMetadata(root)?.epub)
        assertEquals("not an epub", root.resolve("legacy-book.epub").readText())
        assertTrue(root.resolve("META-INF/container.xml").isFile)
    }

    @Test
    fun loadBookEntriesReturnsMetadataBackedBooksSortedByLastAccessDescending() = runBlocking {
        val storage = BookStorage(Files.createTempDirectory("hoshi-metadata-list").toFile())
        val olderRoot = storage.createBookDirectory("older")
        val newerRoot = storage.createBookDirectory("newer")
        val olderId = UUID.randomUUID().toString()
        val newerId = UUID.randomUUID().toString()
        storage.saveMetadata(
            olderRoot,
            BookMetadata(id = olderId, title = "Older", cover = null, folder = "older", lastAccess = 10.0),
        )
        storage.saveMetadata(
            newerRoot,
            BookMetadata(id = newerId, title = "Newer", cover = null, folder = "newer", lastAccess = 20.0),
        )

        val entries = storage.loadBookEntries()

        assertEquals(listOf(newerId, olderId), entries.map { it.metadata.id })
        assertEquals(listOf(newerRoot, olderRoot).map { it.canonicalFile }, entries.map { it.root.canonicalFile })
    }

    @Test
    fun loadBookEntriesCanSortByTitleLikeIos() = runBlocking {
        val storage = BookStorage(Files.createTempDirectory("hoshi-metadata-title").toFile())
        val zRoot = storage.createBookDirectory("z")
        val aRoot = storage.createBookDirectory("a")
        storage.saveMetadata(
            zRoot,
            BookMetadata(id = UUID.randomUUID().toString(), title = "Zeta", cover = null, folder = "z", lastAccess = 20.0),
        )
        storage.saveMetadata(
            aRoot,
            BookMetadata(id = UUID.randomUUID().toString(), title = "Alpha", cover = null, folder = "a", lastAccess = 10.0),
        )

        val entries = storage.loadBookEntries(BookSortOption.Title)

        assertEquals(listOf("Alpha", "Zeta"), entries.map { it.metadata.title })
    }

    @Test
    fun loadBookEntryFindsBooksByStableMetadataId() = runBlocking {
        val storage = BookStorage(Files.createTempDirectory("hoshi-metadata-id").toFile())
        val root = storage.createBookDirectory("folder-a")
        val bookId = UUID.randomUUID().toString()
        storage.saveMetadata(
            root,
            BookMetadata(id = bookId, title = "Stable", cover = null, folder = "folder-a", lastAccess = 20.0),
        )

        val entry = storage.loadBookEntry(bookId)

        assertEquals(root.canonicalFile, entry?.root?.canonicalFile)
        assertEquals(bookId, entry?.metadata?.id)
    }

    @Test
    fun loadBookEntryMigratesLegacyFolderLookupToUuidMetadata() = runBlocking {
        val storage = BookStorage(Files.createTempDirectory("hoshi-metadata-fallback-id").toFile())
        val root = storage.createBookDirectory("folder-only")
        storage.saveShelves(listOf(BookShelf(name = "Legacy", bookIds = listOf("folder-only"))))

        val entry = storage.loadBookEntry("folder-only")

        assertEquals(root.canonicalFile, entry?.root?.canonicalFile)
        val migratedId = requireNotNull(entry).metadata.id
        UUID.fromString(migratedId)
        assertFalse(migratedId == "folder-only")
        assertEquals(entry.metadata, storage.loadMetadata(root))
        assertEquals(listOf(BookShelf(name = "Legacy", bookIds = listOf(migratedId))), storage.loadShelves())
    }

    @Test
    fun deleteBookRemovesBookDirectory() = runBlocking {
        val storage = BookStorage(Files.createTempDirectory("hoshi-metadata-delete").toFile())
        val root = storage.createBookDirectory("delete-me")
        root.resolve("metadata.json").writeText("{}")

        storage.deleteBook(root)

        assertFalse(root.exists())
        assertEquals(emptyList<BookEntry>(), storage.loadBookEntries())
    }

    @Test
    fun saveShelvesWritesIosCompatibleShelvesJsonAtBooksRoot() = runBlocking {
        val filesDir = Files.createTempDirectory("hoshi-shelves").toFile()
        val storage = BookStorage(filesDir)
        val bookA = UUID.randomUUID().toString()
        val bookB = UUID.randomUUID().toString()
        val shelves = listOf(
            BookShelf(name = "Manga", bookIds = listOf(bookA, bookB)),
            BookShelf(name = "Novels", bookIds = emptyList()),
        )

        storage.saveShelves(shelves)

        val shelvesFile = filesDir.resolve("Books/shelves.json")
        val saved = Json.parseToJsonElement(shelvesFile.readText()).jsonArray
        assertEquals("Manga", saved[0].jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals(bookA, saved[0].jsonObject.getValue("bookIds").jsonArray[0].jsonPrimitive.content)
        assertEquals(bookB, saved[0].jsonObject.getValue("bookIds").jsonArray[1].jsonPrimitive.content)
        assertEquals("Novels", saved[1].jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals(shelves, storage.loadShelves())
    }

    @Test
    fun loadBookEntriesMigratesLegacyNonUuidBookIdsAndShelfMembershipsForIosBackupCompatibility() = runBlocking {
        val filesDir = Files.createTempDirectory("hoshi-metadata-uuid-migration").toFile()
        val storage = BookStorage(filesDir)
        val root = storage.createBookDirectory("屍人荘の殺人")
        storage.saveMetadata(
            root,
            BookMetadata(
                id = "屍人荘の殺人",
                title = "屍人荘の殺人",
                cover = null,
                folder = root.name,
                lastAccess = 10.0,
            ),
        )
        storage.saveShelves(listOf(BookShelf(name = "Manga", bookIds = listOf("屍人荘の殺人"))))

        val entry = storage.loadBookEntries().single()

        UUID.fromString(entry.metadata.id)
        assertFalse(entry.metadata.id == "屍人荘の殺人")
        assertEquals(listOf(BookShelf(name = "Manga", bookIds = listOf(entry.metadata.id))), storage.loadShelves())
        assertEquals(entry.metadata.id, storage.loadMetadata(root)?.id)
    }

    @Test
    fun loadBookEntriesCreatesIosCompatibleFallbackMetadataWhenMetadataIsMissing() = runBlocking {
        val storage = BookStorage(Files.createTempDirectory("hoshi-metadata-fallback-migration").toFile())
        val root = storage.createBookDirectory("folder-only")

        val entry = storage.loadBookEntries().single()

        UUID.fromString(entry.metadata.id)
        assertEquals("folder-only", entry.metadata.folder)
        assertEquals(entry.metadata, storage.loadMetadata(root))
    }

    @Test
    fun loadBookEntriesMigratesLegacyRootRelativeCoverPathForIosBackupCompatibility() = runBlocking {
        val storage = BookStorage(Files.createTempDirectory("hoshi-cover-path-migration").toFile())
        val root = storage.createBookDirectory("book")
        root.resolve("OPS/images").mkdirs()
        root.resolve("OPS/images/cover.jpg").writeBytes(byteArrayOf(1, 2, 3))
        storage.saveMetadata(
            root,
            BookMetadata(
                id = UUID.randomUUID().toString(),
                title = "Book",
                cover = "OPS/images/cover.jpg",
                folder = "book",
                lastAccess = 1.0,
            ),
        )

        val entry = storage.loadBookEntries().single()

        assertEquals("Books/book/cover.jpg", entry.metadata.cover)
        assertEquals("Books/book/cover.jpg", storage.loadMetadata(root)?.cover)
        assertTrue(root.resolve("cover.jpg").isFile)
    }

    @Test
    fun metadataCoverPathCopiesCoverToIosStyleBookRelativePath() = runBlocking {
        val storage = BookStorage(Files.createTempDirectory("hoshi-cover-metadata-path").toFile())
        val root = storage.createBookDirectory("book")
        root.resolve("OPS/images").mkdirs()
        root.resolve("OPS/images/cover.jpg").writeBytes(byteArrayOf(1, 2, 3))

        val coverPath = storage.metadataCoverPath(root, "OPS/images/cover.jpg")

        assertEquals("Books/book/cover.jpg", coverPath)
        assertTrue(root.resolve("cover.jpg").isFile)
    }

    @Test
    fun importedBookDirectoryNameMatchesIosSanitizedTitleAndDeduplicates() = runBlocking {
        val storage = BookStorage(Files.createTempDirectory("hoshi-metadata-dedupe").toFile())

        val first = storage.createBookDirectoryForImportedTitle("屍人荘/の:殺人")
        first.resolve("metadata.json").writeText("{}")
        val second = storage.createBookDirectoryForImportedTitle("屍人荘/の:殺人")

        assertEquals("屍人荘_の_殺人", first.name)
        assertEquals(first.canonicalFile, second.canonicalFile)
    }

    @Test
    fun savesAndLoadsIosCompatibleSasayakiSidecars() = runBlocking {
        val storage = BookStorage(Files.createTempDirectory("hoshi-sasayaki-sidecars").toFile())
        val root = storage.createBookDirectory("book")
        val match = SasayakiMatchData(
            matches = listOf(SasayakiMatch("0", 1.0, 2.0, "本文", chapterIndex = 3, start = 10, length = 2)),
            unmatched = 4,
        )
        val playback = SasayakiPlaybackData(
            lastPosition = 12.5,
            delay = -0.15,
            rate = 1.1f,
            audioUri = "content://audio/test.m4b",
        )
        val copiedPlayback = SasayakiPlaybackData(
            lastPosition = 42.0,
            delay = 0.2,
            rate = 0.95f,
            audioFileName = "sasayaki_audio.m4b",
        )

        storage.saveSasayakiMatch(root, match)
        storage.saveSasayakiPlayback(root, playback)

        assertEquals(match, storage.loadSasayakiMatch(root))
        assertEquals(playback, storage.loadSasayakiPlayback(root))
        assertEquals(true, root.resolve("sasayaki_match.json").isFile)
        assertEquals(true, root.resolve("sasayaki_playback.json").isFile)

        storage.saveSasayakiPlayback(root, copiedPlayback)

        assertEquals(copiedPlayback, storage.loadSasayakiPlayback(root))
    }

    @Test
    fun savesAndLoadsIosCompatibleHighlightSidecar() = runBlocking {
        val storage = BookStorage(Files.createTempDirectory("hoshi-highlight-sidecars").toFile())
        val root = storage.createBookDirectory("book")
        val highlightId = UUID.randomUUID().toString()
        val highlights = listOf(
            ReaderHighlight(
                id = highlightId,
                character = 42,
                offset = 7,
                text = "食べる",
                color = HighlightColor.Green,
                createdAt = 801187200.5,
            ),
        )

        storage.saveHighlights(root, highlights)

        val saved = Json.parseToJsonElement(root.resolve("highlights.json").readText()).jsonArray.single().jsonObject
        assertEquals(highlightId, saved.getValue("id").jsonPrimitive.content)
        assertEquals(42, saved.getValue("character").jsonPrimitive.content.toInt())
        assertEquals(7, saved.getValue("offset").jsonPrimitive.content.toInt())
        assertEquals("食べる", saved.getValue("text").jsonPrimitive.content)
        assertEquals("green", saved.getValue("color").jsonPrimitive.content)
        assertEquals(801187200.5, saved.getValue("createdAt").jsonPrimitive.double, 0.0)
        assertEquals(highlights, storage.loadHighlights(root))
    }

    @Test
    fun loadsExistingIosSasayakiSidecarJsonWithoutMigration() = runBlocking {
        val storage = BookStorage(Files.createTempDirectory("hoshi-ios-sasayaki-sidecars").toFile())
        val root = storage.createBookDirectory("book")
        root.resolve("sasayaki_match.json").writeText(
            """
            {
                "matches": [
                    {
                        "id": "0",
                        "startTime": 1.0,
                        "endTime": 2.0,
                        "text": "本文",
                        "chapterIndex": 3,
                        "start": 10,
                        "length": 2
                    }
                ],
                "unmatched": 4
            }
            """.trimIndent(),
        )
        root.resolve("sasayaki_playback.json").writeText(
            """
            {
                "lastPosition": 12.5,
                "audioBookmark": "AAEC"
            }
            """.trimIndent(),
        )

        assertEquals(
            SasayakiMatchData(
                matches = listOf(SasayakiMatch("0", 1.0, 2.0, "本文", chapterIndex = 3, start = 10, length = 2)),
                unmatched = 4,
            ),
            storage.loadSasayakiMatch(root),
        )
        assertEquals(
            SasayakiPlaybackData(
                lastPosition = 12.5,
                delay = 0.0,
                rate = 1f,
            ),
            storage.loadSasayakiPlayback(root),
        )
    }
}
