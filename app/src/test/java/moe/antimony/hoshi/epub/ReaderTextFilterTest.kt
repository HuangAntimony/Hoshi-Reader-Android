package moe.antimony.hoshi.epub

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderTextFilterTest {
    @Test
    fun koreanRangesMatchWebNormalizationWithoutChangingOtherScripts() {
        // Keep this literal and expected text paired with reader-text-semantics.test.mjs.
        val text = "가힣ㄱㆎ 한글 日本語 Aｚ9、! 𠮟🙂\uABFF\uD7A4\u3130\u318F\u1100\u1161"
        val filtered = text.filteredReaderText()

        assertEquals("가힣ㄱㆎ한글日本語Aｚ9𠮟", filtered)
        assertEquals(13, filtered.codePointCount(0, filtered.length))
        for (codePoint in listOf(0xAC00, 0xD7A3, 0x3131, 0x318E)) {
            assertEquals(true, codePoint.isReaderMatchableCodePoint())
        }
        for (codePoint in listOf(0xABFF, 0xD7A4, 0x3130, 0x318F, 0x1100, 0x1161)) {
            assertEquals(false, codePoint.isReaderMatchableCodePoint())
        }
    }

    @Test
    fun visibleTextExcludesRubyReadingsAndFallbackContents() {
        val html = "<body>가 <ruby>漢<rp class=\"fallback\">fallback</rp>" +
            "<rt>かん</rt><rp>한글</rp></ruby>、𠮟A</body>"

        assertEquals("가 漢、𠮟A", html.visibleReaderText())
        assertEquals("가漢𠮟A", html.filteredReaderText())
    }

    @Test
    fun numericHtmlEntitiesDoNotContributeEncodedDigitsToReaderOffsets() {
        val html = "<html><body>𠮟&#12354;猫&#x3042;犬&#X2000B;A</body></html>"

        val filtered = html.filteredReaderText()

        assertEquals("𠮟猫犬A", filtered)
        assertEquals(4, filtered.codePointCount(0, filtered.length))
    }

    @Test
    fun paragraphTextIgnoresSourceLineBreaksInsideMarkup() {
        // Ruby written across source lines (as in some publishers' EPUBs) is one word, not three lines.
        val html = "<body><p>彼は<ruby>\r\n<rb>完</rb>\r\n <rt>かん</rt>\r\n <rb>膚</rb>\r\n <rt>ぷ</rt>\r\n</ruby>なきまでに。</p>\n" +
            "<p>次<br/>の行。</p></body>"

        val lines = html.visibleReaderText(preserveParagraphs = true).split('\n')
            .map { line -> line.filterNot(Char::isWhitespace) }
            .filter(String::isNotEmpty)

        assertEquals(listOf("彼は完膚なきまでに。", "次", "の行。"), lines)
        assertEquals("彼は完膚なきまでに。次の行。", html.visibleReaderText().filterNot(Char::isWhitespace))
    }
}
