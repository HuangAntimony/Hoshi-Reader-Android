package moe.antimony.hoshi.epub

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubBookParserTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun parsesExtractedEpubUsingContainerManifestAndSpineLikePoc() {
        val root = tempFolder.newFolder("book")
        writeExtractedEpub(root)

        val book = EpubBookParser().parse(root)

        assertEquals("Sample Book", book.title)
        assertEquals(
            listOf("OPS/text/chapter-2.xhtml", "OPS/text/chapter-1.xhtml"),
            book.chapters.map { it.href },
        )
        assertEquals("chapter-2", book.chapters.first().id)
        assertEquals("application/xhtml+xml", book.chapters.first().mediaType)
        assertEquals("<html><body>Second</body></html>", book.chapters.first().html)
        assertEquals("OPS/images/cover.jpg", book.coverHref)
        assertArrayEquals("body {}".toByteArray(), book.readResource("/OPS/styles/book.css"))
        assertEquals("text/css", book.mediaType("OPS/styles/book.css"))
        assertEquals("image/jpeg", book.mediaType("OPS/images/cover.jpg"))
    }

    @Test
    fun preservesSelfClosingExternalScriptsForDirectXhtmlLoading() {
        val root = tempFolder.newFolder("self-closing-script-book")
        writeExtractedEpub(
            root = root,
            firstChapterHtml = """
                <html>
                <head>
                  <script type="text/javascript" src="../js/kobo.js"/>
                </head>
                <body><p>Visible text</p></body>
                </html>
            """.trimIndent(),
        )

        val chapter = EpubBookParser().parse(root).chapters.last()

        assertEquals(
            """
            <html>
            <head>
              <script type="text/javascript" src="../js/kobo.js"/>
            </head>
            <body><p>Visible text</p></body>
            </html>
            """.trimIndent(),
            chapter.html,
        )
    }

    @Test
    fun parsesGeneratedFixtureWithReadableBookInfoAndXhtmlScriptsPreserved() {
        val root = tempFolder.newFolder("realistic-script-book")
        writeExtractedEpub(
            root = root,
            title = "Script Fixture",
            firstChapterHtml = """
                <html>
                <head>
                  <script type="text/javascript" src="../js/kobo.js"/>
                </head>
                <body>
                  <p>Alpha 123</p>
                  <rt>ignored ruby</rt>
                  <script>HiddenText</script>
                </body>
                </html>
            """.trimIndent(),
            secondChapterHtml = "<html><body><p>Second</p></body></html>",
        )

        val book = EpubBookParser().parse(root)

        assertEquals("Script Fixture", book.title)
        assertEquals("OPS/images/cover.jpg", book.coverHref)
        assertEquals(2, book.chapters.size)
        assertEquals("OPS/text/chapter-2.xhtml", book.chapters.first().href)
        assertEquals(14, book.bookInfo.characterCount)
        assertEquals(true, book.chapters.any { chapter -> selfClosingScriptRegex.containsMatchIn(chapter.html) })
        assertEquals("image/jpeg", book.mediaType(book.coverHref.orEmpty()))
    }

    @Test
    fun parsesPackedEpubByExtractingToManagedCache() {
        val archive = tempFolder.newFile("packed.epub")
        val cacheRoot = tempFolder.newFolder("packed-cache")
        writeMinimalEpubArchive(archive, title = "Packed Book")

        val book = EpubBookParser().parsePacked(archive, cacheRoot)

        assertEquals("Packed Book", book.title)
        assertEquals(listOf("OPS/text/chapter-1.xhtml", "OPS/text/chapter-2.xhtml"), book.chapters.map { it.href })
        assertArrayEquals("body {}".toByteArray(), book.readResource("OPS/styles/book.css"))
        assertTrue(cacheRoot.walkTopDown().any { it.name == "container.xml" })
    }

    @Test
    fun parsesPackedEpubIntoStableCacheDirectoryForRepeatedReads() {
        val archive = tempFolder.newFile("stable-packed.epub")
        val cacheRoot = tempFolder.newFolder("stable-cache")
        writeMinimalEpubArchive(archive, title = "Stable Packed Book")

        val first = EpubBookParser().parsePacked(archive, cacheRoot)
        val firstRoot = first.rootDirectory
        val second = EpubBookParser().parsePacked(archive, cacheRoot)

        assertEquals(firstRoot?.canonicalFile, second.rootDirectory?.canonicalFile)
        assertEquals(1, cacheRoot.listFiles().orEmpty().count { it.isDirectory })
    }

    @Test
    fun parsesPackedEpubKeepsOtherExtractionRootsReadableLikeIosTempStorage() {
        val firstArchive = tempFolder.newFile("first-packed.epub")
        val secondArchive = tempFolder.newFile("second-packed.epub")
        val cacheRoot = tempFolder.newFolder("single-working-cache")
        writeMinimalEpubArchive(firstArchive, title = "First Packed Book")
        writeMinimalEpubArchive(secondArchive, title = "Second Packed Book")

        val first = EpubBookParser().parsePacked(firstArchive, cacheRoot)
        val firstRoot = first.rootDirectory?.canonicalFile
        val second = EpubBookParser().parsePacked(secondArchive, cacheRoot)
        val secondRoot = second.rootDirectory?.canonicalFile

        assertTrue(secondRoot?.isDirectory == true)
        assertTrue(firstRoot?.isDirectory == true)
        assertArrayEquals("body {}".toByteArray(), first.readResource("OPS/styles/book.css"))
        assertEquals(
            setOf(firstRoot, secondRoot),
            cacheRoot.listFiles().orEmpty().filter { it.isDirectory }.map { it.canonicalFile }.toSet(),
        )
    }

    @Test
    fun metadataEpubPathOutsideBookRootIsIgnored() {
        val root = tempFolder.newFolder("book-root")
        writeMinimalExtractedEpub(root, title = "Inside Book")
        val outside = tempFolder.newFile("outside.epub")
        writeMinimalEpubArchive(outside, title = "Outside Book")
        root.resolve("metadata.json").writeText(
            """
            {
              "id":"book",
              "title":"Book",
              "cover":null,
              "folder":"book-root",
              "lastAccess":0.0,
              "epub":"../outside.epub"
            }
            """.trimIndent(),
        )

        val book = EpubBookParser().parse(root)

        assertEquals("Inside Book", book.title)
    }

    @Test
    fun parsesGeneratedJapaneseEpubFixtureWithoutCrashing() {
        val root = tempFolder.newFolder("same-dream-book")
        writeSameDreamLikeExtractedEpub(root)

        val book = EpubBookParser().parse(root)

        assertEquals("また、同じ夢を見ていた", book.title)
        assertEquals(9, book.chapters.size)
        assertEquals("item/xhtml/p-cover.xhtml", book.chapters.first().href)
        assertEquals("item/image/cover.jpg", book.coverHref)
    }

    @Test
    fun missingEpubTitleUsesProvidedImportFileNameFallback() {
        val root = tempFolder.newFolder("missing-title-book")
        writeExtractedEpub(root, title = " ")

        val book = EpubBookParser().parse(root, fallbackTitle = "Source File")

        assertEquals("Source File", book.title)
    }

    @Test
    fun parsesKadokawaEpubWithSpineItemProperties() {
        val root = tempFolder.newFolder("kadokawa-test5")
        val archive = tempFolder.newFile("kadokawa-test5.epub")
        writeKadokawaLikeEpubArchive(archive)
        EpubArchiveExtractor().extract(archive, root)

        val book = EpubBookParser().parse(root)

        assertEquals("他校の氷姫を助けたら、お友達から始める事になりました", book.title)
        assertEquals(16, book.chapters.size)
        assertEquals("item/xhtml/p-cover.xhtml", book.chapters.first().href)
        assertEquals("rendition:layout-pre-paginated", book.chapters.first().properties)
        assertEquals("item/image/cover.jpg", book.coverHref)
    }

    @Test
    fun epubArchiveExtractorRejectsEntriesOutsideDestination() {
        val archive = tempFolder.newFile("unsafe.epub")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../outside.txt"))
            zip.write("outside".toByteArray())
            zip.closeEntry()
        }
        val root = tempFolder.newFolder("unsafe-root")

        assertThrows(IllegalArgumentException::class.java) {
            EpubArchiveExtractor().extract(archive, root)
        }
    }

    private fun writeExtractedEpub(
        root: File,
        title: String = "Sample Book",
        firstChapterHtml: String = "<html><body>First</body></html>",
        secondChapterHtml: String = "<html><body>Second</body></html>",
    ) {
        root.resolve("META-INF").mkdirs()
        root.resolve("META-INF/container.xml").writeText(
            """
            <?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OPS/package.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
            """.trimIndent(),
        )

        root.resolve("OPS/text").mkdirs()
        root.resolve("OPS/styles").mkdirs()
        root.resolve("OPS/images").mkdirs()
        root.resolve("OPS/js").mkdirs()
        root.resolve("OPS/text/chapter-1.xhtml").writeText(firstChapterHtml)
        root.resolve("OPS/text/chapter-2.xhtml").writeText(secondChapterHtml)
        root.resolve("OPS/styles/book.css").writeText("body {}")
        root.resolve("OPS/images/cover.jpg").writeBytes(byteArrayOf(1, 2, 3))
        root.resolve("OPS/js/kobo.js").writeText("")
        root.resolve("OPS/package.opf").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <dc:title>$title</dc:title>
              </metadata>
              <manifest>
                <item id="chapter-1" href="text/chapter-1.xhtml" media-type="application/xhtml+xml"/>
                <item id="chapter-2" href="text/chapter-2.xhtml" media-type="application/xhtml+xml"/>
                <item id="style" href="styles/book.css" media-type="text/css"/>
                <item id="cover" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                <item id="script" href="js/kobo.js" media-type="application/javascript"/>
              </manifest>
              <spine page-progression-direction="rtl">
                <itemref idref="chapter-2"/>
                <itemref idref="chapter-1"/>
              </spine>
            </package>
            """.trimIndent(),
        )
    }

    private fun writeSameDreamLikeExtractedEpub(root: File) {
        root.resolve("META-INF").mkdirs()
        root.resolve("META-INF/container.xml").writeText(
            """
            <?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="item/standard.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
            """.trimIndent(),
        )

        root.resolve("item/xhtml").mkdirs()
        root.resolve("item/style").mkdirs()
        root.resolve("item/image").mkdirs()
        val chapterHrefs = listOf(
            "p-cover.xhtml",
            "p-001.xhtml",
            "p-002.xhtml",
            "p-003.xhtml",
            "p-004.xhtml",
            "p-005.xhtml",
            "p-006.xhtml",
            "p-007.xhtml",
            "p-colophon.xhtml",
        )
        chapterHrefs.forEachIndexed { index, href ->
            root.resolve("item/xhtml/$href").writeText(
                """
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head>
                  <link rel="stylesheet" type="text/css" href="../style/book-style.css"/>
                  <link rel="stylesheet" type="text/css" href="../style/style-chuo.css"/>
                </head>
                <body><p>Chapter ${index + 1}</p></body>
                </html>
                """.trimIndent(),
            )
        }
        root.resolve("item/style/book-style.css").writeText("body { writing-mode: vertical-rl; }")
        root.resolve("item/style/style-chuo.css").writeText(".h-valign-width { -epub-writing-mode: horizontal-tb; }")
        root.resolve("item/image/cover.jpg").writeBytes(byteArrayOf(1, 2, 3))
        root.resolve("item/standard.opf").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>また、同じ夢を見ていた</dc:title>
              </metadata>
              <manifest>
                ${chapterHrefs.joinToString("\n                ") { href ->
                    """<item id="${href.substringBefore('.')}" href="xhtml/$href" media-type="application/xhtml+xml"/>"""
                }}
                <item id="book-style" href="style/book-style.css" media-type="text/css"/>
                <item id="style-chuo" href="style/style-chuo.css" media-type="text/css"/>
                <item id="cover" href="image/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
              </manifest>
              <spine page-progression-direction="rtl">
                ${chapterHrefs.joinToString("\n                ") { href ->
                    """<itemref idref="${href.substringBefore('.')}"/>"""
                }}
              </spine>
            </package>
            """.trimIndent(),
        )
    }

    private fun writeKadokawaLikeEpubArchive(archive: File) {
        val chapterHrefs = listOf("p-cover.xhtml") +
            (1..14).map { index -> "p-${index.toString().padStart(3, '0')}.xhtml" } +
            listOf("p-colophon.xhtml")

        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.writeTextEntry(
                "META-INF/container.xml",
                """
                <?xml version="1.0"?>
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="item/standard.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """.trimIndent(),
            )
            chapterHrefs.forEachIndexed { index, href ->
                zip.writeTextEntry(
                    "item/xhtml/$href",
                    """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                    <body><p>Kadokawa chapter ${index + 1}</p></body>
                    </html>
                    """.trimIndent(),
                )
            }
            zip.writeTextEntry("item/image/cover.jpg", "cover")
            zip.writeTextEntry(
                "item/standard.opf",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>他校の氷姫を助けたら、お友達から始める事になりました</dc:title>
                  </metadata>
                  <manifest>
                    ${chapterHrefs.joinToString("\n                    ") { href ->
                        val id = href.substringBefore('.')
                        val properties = if (href == "p-cover.xhtml") {
                            " properties=\"rendition:layout-pre-paginated\""
                        } else {
                            ""
                        }
                        """<item id="$id" href="xhtml/$href" media-type="application/xhtml+xml"$properties/>"""
                    }}
                    <item id="cover-image" href="image/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                  </manifest>
                  <spine page-progression-direction="rtl">
                    ${chapterHrefs.joinToString("\n                    ") { href ->
                        """<itemref idref="${href.substringBefore('.')}"/>"""
                    }}
                  </spine>
                </package>
                """.trimIndent(),
            )
        }
    }

    private fun ZipOutputStream.writeTextEntry(path: String, value: String) {
        putNextEntry(ZipEntry(path))
        write(value.toByteArray())
        closeEntry()
    }

    private companion object {
        val selfClosingScriptRegex = Regex("<script\\b[^>]*/>", RegexOption.IGNORE_CASE)
    }
}
