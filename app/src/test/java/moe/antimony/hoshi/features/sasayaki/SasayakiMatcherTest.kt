package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import moe.antimony.hoshi.epub.EpubBookParser
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SasayakiMatcherTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun matchesCuesAgainstFilteredChapterTextAndSavesChapterOffsets() {
        val book = EpubBook(
            title = "Book",
            chapters = listOf(
                EpubChapter(
                    id = "chapter-1",
                    href = "chapter-1.xhtml",
                    mediaType = "application/xhtml+xml",
                    html = """
                        <html><body>
                          <p><ruby>渚<rt>なぎさ</rt></ruby>　それはある日の、あたし達にとっては日常の光景だった。</p>
                        </body></html>
                    """.trimIndent(),
                ),
                EpubChapter(
                    id = "chapter-2",
                    href = "chapter-2.xhtml",
                    mediaType = "application/xhtml+xml",
                    html = "<html><body><p>次の章の本文です。</p></body></html>",
                ),
            ),
        )
        val cues = listOf(
            SasayakiCue(id = "0", startTime = 1.0, endTime = 2.0, text = "＊スキップ"),
            SasayakiCue(id = "1", startTime = 24.148, endTime = 28.468, text = "渚　それはある日の、あたし達にとっては日常の光景だった。"),
            SasayakiCue(id = "2", startTime = 29.0, endTime = 31.0, text = "次の章の本文です。"),
        )

        val match = SasayakiMatcher.match(book = book, cues = cues, searchWindow = 200)

        assertEquals(2, match.matches.size)
        assertEquals(1, match.unmatched)
        assertEquals("1", match.matches[0].id)
        assertEquals(0, match.matches[0].chapterIndex)
        assertEquals(0, match.matches[0].start)
        assertEquals("渚それはある日のあたし達にとっては日常の光景だった".length, match.matches[0].length)
        assertEquals("2", match.matches[1].id)
        assertEquals(1, match.matches[1].chapterIndex)
        assertEquals(0, match.matches[1].start)
    }

    @Test
    fun doesNotMatchAcrossChapterBoundariesLikeIos() {
        val book = EpubBook(
            title = "Book",
            chapters = listOf(
                EpubChapter("a", "a.xhtml", "application/xhtml+xml", "<html><body>前半</body></html>"),
                EpubChapter("b", "b.xhtml", "application/xhtml+xml", "<html><body>後半</body></html>"),
            ),
        )

        val match = SasayakiMatcher.match(
            book = book,
            cues = listOf(SasayakiCue("0", 0.0, 1.0, "前半後半")),
            searchWindow = 50,
        )

        assertEquals(0, match.matches.size)
        assertEquals(1, match.unmatched)
    }

    @Test
    fun starPrefixedCuesStillAdvanceCursorLikeIos() {
        val book = EpubBook(
            title = "Book",
            chapters = listOf(
                EpubChapter(
                    id = "chapter",
                    href = "chapter.xhtml",
                    mediaType = "application/xhtml+xml",
                    html = "<html><body>最初の文章です星一番星二番星三番東条さんの言葉だった</body></html>",
                ),
            ),
        )
        val cues = listOf(
            SasayakiCue("0", 0.0, 1.0, "最初の文章です"),
            SasayakiCue("1", 1.0, 2.0, "＊星一番"),
            SasayakiCue("2", 2.0, 3.0, "＊星二番"),
            SasayakiCue("3", 3.0, 4.0, "＊星三番"),
            SasayakiCue("4", 4.0, 5.0, "東条さんの言葉だった"),
        )

        val match = SasayakiMatcher.match(book = book, cues = cues, searchWindow = 10)

        assertEquals(listOf("0", "1", "2", "3", "4"), match.matches.map { it.id })
        assertEquals(0, match.unmatched)
    }

    @Test
    fun skipsGuideTocSpineItemsLikeIosDuringMatch() {
        val root = tempFolder.newFolder("guide-toc-book")
        writeGuideTocExtractedEpub(root)
        val book = EpubBookParser().parse(root)
        val cues = listOf(
            SasayakiCue("0", 0.0, 1.0, "目次第一部"),
            SasayakiCue("1", 1.0, 2.0, "本文一番長い文章"),
        )

        val match = SasayakiMatcher.match(book = book, cues = cues, searchWindow = 50)

        assertEquals(listOf("1"), match.matches.map { it.id })
        assertEquals(1, match.unmatched)
    }

    @Test
    fun skipsNonLinearAndNavSpineItemsLikeIosDuringMatch() {
        val root = tempFolder.newFolder("non-reader-spine-book")
        writeNonReaderSpineExtractedEpub(root)
        val book = EpubBookParser().parse(root)
        val cues = listOf(
            SasayakiCue("0", 0.0, 1.0, "ナビ見出し本文"),
            SasayakiCue("1", 1.0, 2.0, "付録非線形本文"),
            SasayakiCue("2", 2.0, 3.0, "読書本文一番長い文章"),
        )

        val match = SasayakiMatcher.match(book = book, cues = cues, searchWindow = 80)

        assertEquals(listOf("2"), match.matches.map { it.id })
        assertEquals(2, match.unmatched)
    }

    private fun writeGuideTocExtractedEpub(root: File) {
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
        root.resolve("OPS/text/toc.html").writeText("<html><body>目次第一部</body></html>")
        root.resolve("OPS/text/chapter.xhtml").writeText("<html><body>本文一番長い文章</body></html>")
        root.resolve("OPS/package.opf").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Guide TOC Book</dc:title>
              </metadata>
              <manifest>
                <item id="toc" href="text/toc.html" media-type="application/xhtml+xml"/>
                <item id="chapter" href="text/chapter.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>
                <itemref idref="toc"/>
                <itemref idref="chapter"/>
              </spine>
              <guide>
                <reference type="toc" title="Table of Contents" href="text/toc.html"/>
              </guide>
            </package>
            """.trimIndent(),
        )
    }

    private fun writeNonReaderSpineExtractedEpub(root: File) {
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
        root.resolve("OPS/text/nav.xhtml").writeText("<html><body>ナビ見出し本文</body></html>")
        root.resolve("OPS/text/appendix.xhtml").writeText("<html><body>付録非線形本文</body></html>")
        root.resolve("OPS/text/chapter.xhtml").writeText("<html><body>読書本文一番長い文章</body></html>")
        root.resolve("OPS/package.opf").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Non Reader Spine Book</dc:title>
              </metadata>
              <manifest>
                <item id="nav" href="text/nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="appendix" href="text/appendix.xhtml" media-type="application/xhtml+xml"/>
                <item id="chapter" href="text/chapter.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>
                <itemref idref="nav"/>
                <itemref idref="appendix" linear="no"/>
                <itemref idref="chapter"/>
              </spine>
            </package>
            """.trimIndent(),
        )
    }
}
