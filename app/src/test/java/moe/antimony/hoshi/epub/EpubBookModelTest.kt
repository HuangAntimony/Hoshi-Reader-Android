package moe.antimony.hoshi.epub

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EpubBookModelTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun exposesOnlyReaderResourcesNeededByWebView() {
        val css = "body {}".toByteArray()
        val book = EpubBook(
            title = "Title",
            chapters = listOf(
                EpubChapter(
                    id = "reading-order-0",
                    href = "item/xhtml/p-001.xhtml",
                    mediaType = "application/xhtml+xml",
                    html = "<html></html>",
                ),
            ),
            resources = mapOf("item/style/book.css" to EpubResource("text/css", css)),
        )

        assertEquals("Title", book.title)
        assertEquals("item/xhtml/p-001.xhtml", book.chapters.single().href)
        assertArrayEquals(css, book.readResource("/item/style/book.css"))
        assertEquals("text/css", book.mediaType("item/style/book.css"))
        assertNull(book.readResource("missing.css"))
    }

    @Test
    fun bookInfoCountsFilteredChapterCharactersLikeIos() {
        val book = EpubBook(
            title = "Title",
            chapters = listOf(
                EpubChapter(
                    id = "a",
                    href = "item/xhtml/a.xhtml",
                    mediaType = "application/xhtml+xml",
                    html = "<html><body><ruby>漢<rt>かん</rt></ruby>字<script>bad</script> A&nbsp;!</body></html>",
                ),
                EpubChapter(
                    id = "b",
                    href = "item/xhtml/b.xhtml",
                    mediaType = "application/xhtml+xml",
                    html = "<html><body>猫と犬</body></html>",
                ),
            ),
        )

        assertEquals(6, book.bookInfo.characterCount)
        assertEquals(
            BookInfo.ChapterInfo(spineIndex = 0, currentTotal = 0, chapterCount = 3),
            book.bookInfo.chapterInfo.getValue("item/xhtml/a.xhtml"),
        )
        assertEquals(
            BookInfo.ChapterInfo(spineIndex = 1, currentTotal = 3, chapterCount = 3),
            book.bookInfo.chapterInfo.getValue("item/xhtml/b.xhtml"),
        )
        assertEquals(4, book.characterCountAt(chapterIndex = 1, progress = 0.5))
    }

    @Test
    fun bookInfoMatchesFilteredChapterOrderWhenRawSpineSkipsEntries() {
        val bookInfo = BookInfo(
            characterCount = 6,
            chapterInfo = mapOf(
                "item/xhtml/a.xhtml" to BookInfo.ChapterInfo(spineIndex = 0, currentTotal = 0, chapterCount = 3),
                "item/xhtml/b.xhtml" to BookInfo.ChapterInfo(spineIndex = 1, currentTotal = 3, chapterCount = 3),
            ),
        )
        val chapterShells = listOf(
            EpubChapter(
                id = "a",
                href = "item/xhtml/a.xhtml",
                mediaType = "application/xhtml+xml",
                html = "",
                spineIndex = 1,
            ),
            EpubChapter(
                id = "b",
                href = "item/xhtml/b.xhtml",
                mediaType = "application/xhtml+xml",
                html = "",
                spineIndex = 3,
            ),
        )

        assertTrue(bookInfo.matchesChapterShells(chapterShells))
    }

    @Test
    fun bookInfoIndexesUniqueReaderImagesAndTrueTocFragmentOffsets() {
        val root = temporaryFolder.newFolder("reader-facts")
        root.resolve("OPS/text").mkdirs()
        root.resolve("OPS/images").mkdirs()
        root.resolve("OPS/images/first.jpg").writeBytes(byteArrayOf(1))
        root.resolve("OPS/images/second.PNG").writeBytes(byteArrayOf(2))
        root.resolve("OPS/images/gaiji.png").writeBytes(byteArrayOf(3))
        root.resolve("OPS/images/vector.svg").writeText("<svg/>")
        val chapter = EpubChapter(
            id = "chapter",
            href = "OPS/text/chapter.xhtml",
            mediaType = "application/xhtml+xml",
            html = """
                <html><body>
                  <p>一<ruby>二<rt>に</rt></ruby></p>
                  <section id="part 2">三四</section>
                  <img src="../images/first.jpg" />
                  <img src="../images/first.jpg" />
                  <img class="ornament gaiji" src="../images/gaiji.png" />
                  <svg><image xlink:href="../images/second.PNG" /></svg>
                  <img src="../images/vector.svg" />
                  <img src="../images/missing.jpeg" />
                </body></html>
            """.trimIndent(),
        )
        val toc = listOf(
            EpubTocItem(
                label = "Chapter",
                href = chapter.href,
                children = listOf(
                    EpubTocItem(label = "Part 2", href = "${chapter.href}#part%202"),
                    EpubTocItem(label = "Missing", href = "${chapter.href}#missing"),
                ),
            ),
        )

        val bookInfo = buildBookInfo(
            chapters = listOf(chapter),
            toc = toc,
            rootDirectory = root,
        )

        assertEquals(listOf("OPS/images/first.jpg", "OPS/images/second.PNG"), bookInfo.images)
        assertEquals(
            mapOf("part%202" to 2, "missing" to 0),
            bookInfo.chapterInfo.getValue(chapter.href).fragmentOffsets,
        )
        assertEquals(4, bookInfo.characterCount)
    }

    @Test
    fun cachedBookInfoRequiresPersistedReaderFactsForTocFragments() {
        val chapter = EpubChapter(
            id = "chapter",
            href = "chapter.xhtml",
            mediaType = "application/xhtml+xml",
            html = "",
        )
        val toc = listOf(EpubTocItem(label = "Part", href = "chapter.xhtml#part"))
        val legacy = BookInfo(
            characterCount = 10,
            chapterInfo = mapOf(
                chapter.href to BookInfo.ChapterInfo(
                    spineIndex = 0,
                    currentTotal = 0,
                    chapterCount = 10,
                ),
            ),
        )
        val complete = legacy.copy(
            images = emptyList(),
            chapterInfo = legacy.chapterInfo.mapValues { (_, info) ->
                info.copy(fragmentOffsets = mapOf("part" to 4))
            },
        )
        val incomplete = complete.copy(
            chapterInfo = complete.chapterInfo.mapValues { (_, info) ->
                info.copy(fragmentOffsets = mapOf("another-part" to 0))
            },
        )

        assertFalse(legacy.matchesReaderFacts(listOf(chapter), toc))
        assertFalse(incomplete.matchesReaderFacts(listOf(chapter), toc))
        assertTrue(complete.matchesReaderFacts(listOf(chapter), toc))
    }
}
