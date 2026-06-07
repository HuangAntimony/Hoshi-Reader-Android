package moe.antimony.hoshi.features.sync

import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookInfo
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.EpubBookParser
import moe.antimony.hoshi.epub.EpubTocItem
import moe.antimony.hoshi.epub.writeMinimalExtractedEpub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TtuBookDataConverterTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun exportBookDataWritesIosCompatibleArchiveShape() = runBlocking {
        val repository = BookRepository(tempFolder.root)
        val root = repository.createBookDirectory("export-book")
        val extracted = tempFolder.newFolder("export-extracted")
        writeMinimalExtractedEpub(
            extracted,
            title = "Export Book",
            firstChapterHtml = """
                <html><body><p>First<img src="../images/cover.jpg"/></p><br class="clear"/></body></html>
            """.trimIndent(),
        )
        val epub = root.resolve("export-book.epub")
        moe.antimony.hoshi.epub.EpubArchiveExtractor().createArchive(extracted, epub)
        root.resolve("cover.jpg").writeBytes(byteArrayOf(9, 8, 7))
        val parsed = EpubBookParser().parse(root)
        val metadata = BookMetadata(
            id = UUID.randomUUID().toString(),
            title = parsed.title,
            cover = "Books/export-book/cover.jpg",
            folder = root.name,
            lastAccess = 1.0,
            epub = epub.name,
        )
        repository.saveMetadata(root, metadata)
        repository.saveBookInfo(root, parsed.bookInfo)
        val converter = TtuBookDataConverter(repository, EpubBookParser(), tempFolder.root)

        val bookData = requireNotNull(
            converter.exportBookData(moe.antimony.hoshi.epub.BookEntry(root, metadata), tempFolder.newFolder("ttu-export")),
        )

        ZipFile(bookData).use { zip ->
            assertNotNull(zip.getEntry("staticdata.json"))
            assertNotNull(zip.getEntry("blobs/OPS/images/cover.jpg"))
            assertNotNull(zip.getEntry("cover.jpg"))
            val staticData = Json.parseToJsonElement(zip.readText("staticdata.json")).jsonObject
            val elementHtml = staticData.getValue("elementHtml").jsonPrimitive.content
            assertEquals("Export Book", staticData.getValue("title").jsonPrimitive.content)
            assertTrue(staticData.getValue("styleSheet").jsonPrimitive.content.contains("body {}"))
            assertTrue(elementHtml, elementHtml.contains("ttu-book-html-wrapper"))
            assertTrue(elementHtml, elementHtml.contains("data:image/gif;ttu:OPS/images/cover.jpg;"))
            assertEquals(2, staticData.getValue("sections").jsonArray.size)
            assertTrue(bookData.name.startsWith("bookdata_1_6_${parsed.bookInfo.characterCount}_"))
        }
    }

    @Test
    fun exportBookDataReturnsNullWhenBookCannotBeConvertedLikeIos() = runBlocking {
        val repository = BookRepository(tempFolder.root)
        val missingBookInfoRoot = repository.createBookDirectory("missing-book-info")
        val missingBookInfoMetadata = BookMetadata(
            id = UUID.randomUUID().toString(),
            title = "Missing Book Info",
            cover = null,
            folder = missingBookInfoRoot.name,
            lastAccess = 1.0,
            epub = "Missing Book Info.epub",
        )
        repository.saveMetadata(missingBookInfoRoot, missingBookInfoMetadata)

        val missingEpubRoot = repository.createBookDirectory("missing-epub")
        val missingEpubMetadata = BookMetadata(
            id = UUID.randomUUID().toString(),
            title = "Missing EPUB",
            cover = null,
            folder = missingEpubRoot.name,
            lastAccess = 1.0,
            epub = null,
        )
        repository.saveMetadata(missingEpubRoot, missingEpubMetadata)
        repository.saveBookInfo(missingEpubRoot, BookInfo(characterCount = 1, chapterInfo = emptyMap()))

        val converter = TtuBookDataConverter(repository, EpubBookParser(), tempFolder.root)

        assertNull(
            converter.exportBookData(
                moe.antimony.hoshi.epub.BookEntry(missingBookInfoRoot, missingBookInfoMetadata),
                tempFolder.newFolder("missing-book-info-export"),
            ),
        )
        assertNull(
            converter.exportBookData(
                moe.antimony.hoshi.epub.BookEntry(missingEpubRoot, missingEpubMetadata),
                tempFolder.newFolder("missing-epub-export"),
            ),
        )
    }

    @Test
    fun importBookDataGeneratesPackedEpubAndPreservesWrapperEdgeCases() = runBlocking {
        val repository = BookRepository(tempFolder.root)
        val source = tempFolder.newFile("bookdata_1_6_10_1000_1000.zip")
        writeTtuBookDataFixture(source)
        val converter = TtuBookDataConverter(repository, EpubBookParser(), tempFolder.root)

        val entry = converter.importBookData(source)

        assertEquals("Imported Book", entry.metadata.title)
        assertEquals("Imported Book.epub", entry.metadata.epub)
        assertTrue(entry.root.resolve("Imported Book.epub").isFile)
        assertTrue(entry.root.resolve("cover.png").isFile)
        val unpacked = tempFolder.newFolder("imported-unpacked")
        moe.antimony.hoshi.epub.EpubArchiveExtractor().extract(entry.root.resolve("Imported Book.epub"), unpacked)
        val first = unpacked.resolve("item/xhtml/chapter-1.xhtml").readText()
        val second = unpacked.resolve("item/xhtml/chapter-2.xhtml").readText()
        assertTrue(first.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(second.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(first.contains("""<br class="clear"/>"""))
        assertTrue(first.contains("""<hr data-x="1"/>"""))
        assertTrue(first.contains("""<img src="../image/pic.png"/>"""))
        assertTrue(first.contains("""<body class="body-class">"""))
        assertTrue(first.contains("""<html"""))
        assertTrue(first.contains("class=\"html-class\""))
        assertTrue(second.contains("<p>No wrappers</p>"))
        assertTrue((repository.loadBookInfo(entry.root)?.characterCount ?: 0) > 0)
    }

    @Test
    fun importBookDataReturnsExistingTitleFolderBeforeComparingCharacterCountLikeIos() = runBlocking {
        val repository = BookRepository(tempFolder.root)
        val existingRoot = repository.createBookDirectoryForImportedTitle("Imported Book")
        val existingMetadata = BookMetadata(
            id = "existing-book",
            title = "Imported Book",
            cover = null,
            folder = existingRoot.name,
            lastAccess = 1.0,
            epub = "Imported Book.epub",
        )
        repository.saveMetadata(existingRoot, existingMetadata)
        repository.saveBookInfo(existingRoot, BookInfo(characterCount = 999, chapterInfo = emptyMap()))
        existingRoot.resolve("Imported Book.epub").writeText("existing epub")
        val source = tempFolder.newFile("existing-bookdata.zip")
        writeTtuBookDataFixture(source)
        val converter = TtuBookDataConverter(repository, EpubBookParser(), tempFolder.root)

        val entry = converter.importBookData(source)

        assertEquals(existingRoot.canonicalFile, entry.root.canonicalFile)
        assertEquals(existingMetadata, entry.metadata)
        assertEquals("existing epub", existingRoot.resolve("Imported Book.epub").readText())
        assertFalse(tempFolder.root.resolve("Books/.ttu-import-Imported Book").exists())
    }

    @Test
    fun importBookDataReturnsExistingTitleFolderBeforeGeneratingEpubLikeIos() = runBlocking {
        val repository = BookRepository(tempFolder.root)
        val existingRoot = repository.createBookDirectoryForImportedTitle("Existing Book")
        val existingMetadata = BookMetadata(
            id = "existing-book",
            title = "Existing Book",
            cover = null,
            folder = existingRoot.name,
            lastAccess = 1.0,
            epub = "Existing Book.epub",
        )
        repository.saveMetadata(existingRoot, existingMetadata)
        existingRoot.resolve("Existing Book.epub").writeText("existing epub")
        val source = tempFolder.newFile("existing-invalid-bookdata.zip")
        writeTtuBookDataWithNoReadableSections(source, title = "Existing Book")
        val converter = TtuBookDataConverter(repository, EpubBookParser(), tempFolder.root)

        val entry = converter.importBookData(source)

        assertEquals(existingRoot.canonicalFile, entry.root.canonicalFile)
        assertEquals(existingMetadata, entry.metadata)
        assertEquals("existing epub", existingRoot.resolve("Existing Book.epub").readText())
    }

    @Test
    fun importBookDataGeneratesSafeUniqueOpfIdsForUnsafeTtuNames() = runBlocking {
        val repository = BookRepository(tempFolder.root)
        val source = tempFolder.newFile("unsafe-opf-names-bookdata.zip")
        writeTtuBookDataWithUnsafeOpfNames(source)
        val converter = TtuBookDataConverter(repository, EpubBookParser(), tempFolder.root)

        val entry = converter.importBookData(source)

        val unpacked = tempFolder.newFolder("unsafe-opf-unpacked")
        moe.antimony.hoshi.epub.EpubArchiveExtractor().extract(entry.root.resolve("Manifest Id Book.epub"), unpacked)
        val opf = unpacked.resolve("item/standard.opf").readText()
        assertTrue(opf.contains("""id="image-0" href="image/a/pic 1.png""""))
        assertTrue(opf.contains("""id="image-1" href="image/b/pic 1.png""""))
        assertTrue(opf.contains("""id="xhtml-0" href="xhtml/1.xhtml""""))
        assertTrue(opf.contains("""idref="xhtml-0""""))
        assertFalse(opf.contains("""id="i-pic 1""""))
        assertFalse(opf.contains("""id="1""""))
        assertEquals("Manifest Id Book", EpubBookParser().parse(entry.root).title)
    }

    @Test
    fun importBookDataPreservesIosSectionFileNamesForDistinctTtuReferences() = runBlocking {
        val repository = BookRepository(tempFolder.root)
        val source = tempFolder.newFile("reversible-section-refs-bookdata.zip")
        writeTtuBookDataWithDistinctSectionReferences(source)
        val converter = TtuBookDataConverter(repository, EpubBookParser(), tempFolder.root)

        val entry = converter.importBookData(source)

        val unpacked = tempFolder.newFolder("reversible-section-refs-unpacked")
        moe.antimony.hoshi.epub.EpubArchiveExtractor().extract(entry.root.resolve("Reference Book.epub"), unpacked)
        val colonFile = unpacked.resolve("item/xhtml/chap:1.xhtml")
        val hyphenFile = unpacked.resolve("item/xhtml/chap-1.xhtml")
        assertTrue(colonFile.isFile)
        assertTrue(hyphenFile.isFile)
        assertTrue(colonFile.readText().contains("Colon reference"))
        assertTrue(hyphenFile.readText().contains("Hyphen reference"))
        val opf = unpacked.resolve("item/standard.opf").readText()
        assertTrue(opf.contains("""href="xhtml/chap:1.xhtml""""))
        assertTrue(opf.contains("""href="xhtml/chap-1.xhtml""""))
    }

    @Test
    fun tocLabelSkipsGroupingItemsWithoutHrefLikeIos() {
        val toc = listOf(
            EpubTocItem(
                label = "Part 1",
                href = null,
                children = listOf(
                    EpubTocItem(label = "Chapter 1", href = "OPS/text/chapter-1.xhtml"),
                ),
            ),
        )

        assertEquals("Chapter 1", ttuTocLabel("OPS/text/chapter-1.xhtml", toc))
        assertEquals(null, ttuTocLabel("OPS/text/chapter-2.xhtml", toc))
    }

    @Test
    fun importBookDataRejectsUnsafeSectionReferenceWithoutLeavingBookFolder() = runBlocking {
        val repository = BookRepository(tempFolder.root)
        val source = tempFolder.newFile("unsafe-bookdata.zip")
        writeUnsafeTtuBookDataFixture(source)
        val converter = TtuBookDataConverter(repository, EpubBookParser(), tempFolder.root)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { converter.importBookData(source) }
        }
        assertFalse(tempFolder.root.resolve("Books/Unsafe Book").exists())
        assertFalse(tempFolder.root.resolve("escape.xhtml").exists())
    }

    private fun writeTtuBookDataFixture(destination: File) {
        ZipOutputStream(destination.outputStream()).use { zip ->
            zip.writeTextEntry(
                "staticdata.json",
                """
                {
                  "title": "Imported Book",
                  "styleSheet": "body { color: black; }",
                  "elementHtml": "<div id=\"ttu-chapter-1\"><div class=\"ttu-book-html-wrapper html-class\" data-extra=\"1\"><div class=\"ttu-book-body-wrapper body-class\" data-extra=\"2\">\\n<div class=\"main\">\\n<p>First<br class=\"clear\"><hr data-x=\"1\"><img src=\"../image/pic.png\"></p>\\n</div>\\n</div></div></div><div id=\"ttu-chapter-2\"><p>No wrappers</p></div>",
                  "sections": [
                    {"reference":"ttu-chapter-1","charactersWeight":5,"label":"Chapter 1","startCharacter":0,"characters":5,"parentChapter":null},
                    {"reference":"ttu-chapter-2","charactersWeight":5,"label":"Chapter 2","startCharacter":5,"characters":5,"parentChapter":null}
                  ]
                }
                """.trimIndent(),
            )
            zip.writeTextEntry("blobs/image/pic.png", "image")
            zip.writeTextEntry("cover.png", "cover")
        }
    }

    private fun writeTtuBookDataWithUnsafeOpfNames(destination: File) {
        ZipOutputStream(destination.outputStream()).use { zip ->
            zip.writeTextEntry(
                "staticdata.json",
                """
                {
                  "title": "Manifest Id Book",
                  "styleSheet": "",
                  "elementHtml": "<div id=\"ttu-1\"><p><img src=\"../image/a/pic 1.png\"><img src=\"../image/b/pic 1.png\"></p></div>",
                  "sections": [
                    {"reference":"ttu-1","charactersWeight":1,"label":"1","startCharacter":0,"characters":1,"parentChapter":null}
                  ]
                }
                """.trimIndent(),
            )
            zip.writeTextEntry("blobs/image/a/pic 1.png", "image-a")
            zip.writeTextEntry("blobs/image/b/pic 1.png", "image-b")
        }
    }

    private fun writeTtuBookDataWithDistinctSectionReferences(destination: File) {
        ZipOutputStream(destination.outputStream()).use { zip ->
            zip.writeTextEntry(
                "staticdata.json",
                """
                {
                  "title": "Reference Book",
                  "styleSheet": "",
                  "elementHtml": "<div id=\"ttu-chap:1\"><p>Colon reference</p></div><div id=\"ttu-chap-1\"><p>Hyphen reference</p></div>",
                  "sections": [
                    {"reference":"ttu-chap:1","charactersWeight":1,"label":"Colon","startCharacter":0,"characters":1,"parentChapter":null},
                    {"reference":"ttu-chap-1","charactersWeight":1,"label":"Hyphen","startCharacter":1,"characters":1,"parentChapter":null}
                  ]
                }
                """.trimIndent(),
            )
        }
    }

    private fun writeUnsafeTtuBookDataFixture(destination: File) {
        ZipOutputStream(destination.outputStream()).use { zip ->
            zip.writeTextEntry(
                "staticdata.json",
                """
                {
                  "title": "Unsafe Book",
                  "styleSheet": "",
                  "elementHtml": "<div id=\"ttu-../escape\"><p>Bad</p></div>",
                  "sections": [
                    {"reference":"ttu-../escape","charactersWeight":1,"label":"Bad","startCharacter":0,"characters":1,"parentChapter":null}
                  ]
                }
                """.trimIndent(),
            )
        }
    }

    private fun writeTtuBookDataWithNoReadableSections(destination: File, title: String) {
        ZipOutputStream(destination.outputStream()).use { zip ->
            zip.writeTextEntry(
                "staticdata.json",
                """
                {
                  "title": "$title",
                  "styleSheet": "",
                  "elementHtml": "<p>No TTU section wrapper</p>",
                  "sections": [
                    {"reference":"ttu-missing","charactersWeight":1,"label":"Missing","startCharacter":0,"characters":1,"parentChapter":null}
                  ]
                }
                """.trimIndent(),
            )
        }
    }

    private fun ZipOutputStream.writeTextEntry(path: String, value: String) {
        putNextEntry(ZipEntry(path))
        write(value.toByteArray())
        closeEntry()
    }

    private fun ZipFile.readText(path: String): String =
        getInputStream(getEntry(path)).use { it.readBytes().decodeToString() }
}
