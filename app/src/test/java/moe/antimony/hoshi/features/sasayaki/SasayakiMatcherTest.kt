package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import moe.antimony.hoshi.epub.EpubBookParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipInputStream

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
        assertEquals(0, match.unmatched)
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
    fun matchesBundledSasayakiFixtureAboveNinetyNinePercentWithDefaultWindow() {
        val testData = listOf(File("testdata"), File("../testdata")).first { it.isDirectory }
        val root = tempFolder.newFolder("test-book")
        unzip(testData.resolve("test.epub"), root)
        val book = EpubBookParser().parse(root)
        val cues = SasayakiParser.parseCues(testData.resolve("test.srt").readBytes())

        val match = SasayakiMatcher.match(book = book, cues = cues, searchWindow = 200)

        val total = match.matches.size + match.unmatched
        val rate = match.matches.size.toDouble() / total.toDouble()
        assertTrue("Expected >= 99%, got ${rate * 100.0}%", rate >= 0.99)
    }

    private fun unzip(zipFile: File, targetRoot: File) {
        ZipInputStream(zipFile.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val output = targetRoot.resolve(entry.name).canonicalFile
                val root = targetRoot.canonicalFile
                require(output.path == root.path || output.path.startsWith(root.path + File.separator))
                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile?.mkdirs()
                    output.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }
}
